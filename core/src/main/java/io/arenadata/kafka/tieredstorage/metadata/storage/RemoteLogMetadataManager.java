/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.arenadata.kafka.tieredstorage.metadata.storage;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadataUpdate;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentState;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteStorageException;

import io.arenadata.kafka.tieredstorage.config.RemoteMetadataStorageManagerConfig;
import io.arenadata.kafka.tieredstorage.metadata.MetadataKeyFactory;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackend;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackendException;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageOperation;
import io.arenadata.kafka.tieredstorage.metadata.storage.serde.MetadataMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteLogMetadataManager implements org.apache.kafka.server.log.remote.storage.RemoteLogMetadataManager {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteLogMetadataManager.class);

    private MetadataStorageBackend remoteStorage;

    private RemoteLogMetadataHandler metadataHandler;

    private MetadataMapper serializer;

    private MetadataKeyFactory keyFactory;

    private volatile boolean isClosing;

    @Override
    public void configure(final Map<String, ?> configs) {
        final RemoteMetadataStorageManagerConfig config =
            new RemoteMetadataStorageManagerConfig(configs);

        remoteStorage = config.metadataStorageBackend();
        serializer = config.remoteMetadataManagerSerDe();
        keyFactory = config.metadataKeyFactory();

        metadataHandler = new RemoteLogMetadataHandler(new RemoteSegmentMetadataFetcher());
    }

    /**
     * <ol>
     *     <li>Adds segment metadata to the in-memory storage
     *     <li>Adds segment metadata to the remote storage
     *     <li>Adds partition metadata (offsets, leader epochs, see
     *     {@link org.apache.kafka.server.log.remote.metadata.storage.RemoteLogMetadataCache RemoteLogMetadataCache})
     *     to the remote storage
     * </ol>
     */
    @Override
    public CompletableFuture<Void> addRemoteLogSegmentMetadata(final RemoteLogSegmentMetadata logSegmentMetadata) {
        Objects.requireNonNull(logSegmentMetadata, "logSegmentMetadata can't be null");

        ensureNotClosed();

        if (logSegmentMetadata.state() != RemoteLogSegmentState.COPY_SEGMENT_STARTED) {
            throw new IllegalArgumentException(
                "Given remoteLogSegmentMetadata should have state as " + RemoteLogSegmentState.COPY_SEGMENT_STARTED
                    + " but it contains state as: " + logSegmentMetadata.state());
        }

        return CompletableFuture.completedFuture(metadataHandler.addRemoteLogSegmentMetadata(logSegmentMetadata))
                .thenCompose(partitionMetadata -> putLogSegmentMetadataWithEpochs(
                    partitionMetadata, logSegmentMetadata, MetadataStorageOperation.Type.ADD));
    }

    /**
     * <ol>
     *     <li>Updates segment metadata in the in-memory storage
     *     <li>Updates segment metadata in the remote storage
     *     <li>Updates partition metadata (offsets, leader epochs, see
     *     {@link org.apache.kafka.server.log.remote.metadata.storage.RemoteLogMetadataCache RemoteLogMetadataCache})
     *     in the remote storage
     * </ol>
     */
    @Override
    public CompletableFuture<Void> updateRemoteLogSegmentMetadata(
        final RemoteLogSegmentMetadataUpdate segmentMetadataUpdate) throws RemoteStorageException {
        Objects.requireNonNull(segmentMetadataUpdate, "segmentMetadataUpdate can't be null");

        ensureNotClosed();

        if (segmentMetadataUpdate.state() == RemoteLogSegmentState.COPY_SEGMENT_STARTED) {
            throw new IllegalArgumentException("Given remoteLogSegmentMetadata should not have the state as: "
                + RemoteLogSegmentState.COPY_SEGMENT_STARTED);
        }

        final RemoteLogSegmentMetadata updatedSegmentMetadata =
            metadataHandler.buildUpdatedSegmentMetadata(segmentMetadataUpdate);

        return CompletableFuture.completedFuture(metadataHandler.updateRemoteLogSegmentMetadata(segmentMetadataUpdate))
                .thenCompose(partitionMetadata -> putLogSegmentMetadataWithEpochs(
                        partitionMetadata, updatedSegmentMetadata, MetadataStorageOperation.Type.UPDATE));
    }

    /**
     * If the current instance is the leader for a partition with an id equal to {@code topicIdPartition},
     * then the log segment metadata is fetched directly from the in-memory store. Otherwise,
     * the id of the remote log segment corresponding to this offset is obtained from the topic partition metadata,
     * that is fetched from the remote storage, and only then the corresponding remote log segment metadata itself
     * is fetched from the remote storage.
     */
    @Override
    public Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(
        final TopicIdPartition topicIdPartition, final int epochForOffset, final long offset)
        throws RemoteStorageException {
        Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");

        ensureNotClosed();

        return metadataHandler.remoteLogSegmentMetadata(topicIdPartition, epochForOffset, offset);
    }

    @Override
    public Optional<Long> highestOffsetForEpoch(final TopicIdPartition topicIdPartition, final int leaderEpoch)
        throws RemoteStorageException {
        Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");

        ensureNotClosed();

        return metadataHandler.highestOffsetForEpoch(topicIdPartition, leaderEpoch);
    }

    @Override
    public CompletableFuture<Void> putRemotePartitionDeleteMetadata(
        final RemotePartitionDeleteMetadata remotePartitionDeleteMetadata) {
        Objects.requireNonNull(remotePartitionDeleteMetadata, "remotePartitionDeleteMetadata can't be null");

        ensureNotClosed();

        final boolean isFinalRemoveState =
            metadataHandler.isValidFinalRemovePartitionMetadataState(remotePartitionDeleteMetadata);

        return putPartitionDeleteMetadataInRemoteStorage(remotePartitionDeleteMetadata, isFinalRemoveState)
            .thenAccept(ignore -> metadataHandler.putRemotePartitionDeleteMetadata(remotePartitionDeleteMetadata));
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(final TopicIdPartition topicIdPartition)
        throws RemoteStorageException {
        Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");

        ensureNotClosed();

        return metadataHandler.listRemoteLogSegments(topicIdPartition);
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(final TopicIdPartition topicIdPartition,
                                                                    final int leaderEpoch)
        throws RemoteStorageException {
        Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");

        ensureNotClosed();

        return metadataHandler.listRemoteLogSegments(topicIdPartition, leaderEpoch);
    }

    @Override
    public void onPartitionLeadershipChanges(final Set<TopicIdPartition> leaderPartitions,
                                             final Set<TopicIdPartition> followerPartitions) {
        Objects.requireNonNull(leaderPartitions, "leaderPartitions can't be null");
        Objects.requireNonNull(followerPartitions, "followerPartitions can't be null");

        ensureNotClosed();

        LOG.info("Received leadership notifications with leader partitions {} and follower partitions {}",
            leaderPartitions, followerPartitions);

        try {
            metadataHandler.onPartitionLeadershipChanges(leaderPartitions, followerPartitions);
        } catch (final RemoteStorageException exc) {
            throw new KafkaException("Error during fetching segment metadata from remote store", exc);
        }
    }

    @Override
    public void onStopPartitions(final Set<TopicIdPartition> partitions) {
        Objects.requireNonNull(partitions, "partitions can't be null");

        ensureNotClosed();

        // update only in-memory metadata store view of this broker instance
        metadataHandler.removeLogSegmentsMetadata(partitions);
    }

    @Override
    public long remoteLogSize(final TopicIdPartition topicIdPartition,
                              final int leaderEpoch) throws RemoteStorageException {
        Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");

        ensureNotClosed();

        long remoteLogSize = 0L;
        final Iterator<RemoteLogSegmentMetadata> metadataIterator =
            metadataHandler.listRemoteLogSegments(topicIdPartition, leaderEpoch);

        while (metadataIterator.hasNext()) {
            final RemoteLogSegmentMetadata segmentMetadata = metadataIterator.next();
            remoteLogSize += segmentMetadata.segmentSizeInBytes();
        }

        return remoteLogSize;
    }

    @Override
    public void close() {
        if (!isClosing) {
            isClosing = true;
            Utils.closeQuietly(remoteStorage, "RemoteStorageBackend");
        }
    }

    private CompletableFuture<Void> putPartitionDeleteMetadataInRemoteStorage(
        final RemotePartitionDeleteMetadata remotePartitionDeleteMetadata,
        final boolean isFinalRemoveState) {

        final TopicIdPartition topicIdPartition = remotePartitionDeleteMetadata.topicIdPartition();
        final String partitionMetadataKey = keyFactory.partitionMetadataKey(topicIdPartition);
        final byte[] partitionDeleteMetadata = serializer.serializeLogMetadata(remotePartitionDeleteMetadata);

        if (!isFinalRemoveState) {
            return remoteStorage.addValue(partitionMetadataKey, partitionDeleteMetadata);
        }

        return remoteStorage.updateValue(partitionMetadataKey, partitionDeleteMetadata)
            .thenCompose(ignore -> remoteStorage.removeValuesWithKeyLike(keyFactory.partitionKey(topicIdPartition)));
    }

    private CompletableFuture<Void> putLogSegmentMetadataWithEpochs(final TopicPartitionMetadata partitionMetadata,
                                                                    final RemoteLogSegmentMetadata segmentMetadata,
                                                                    final MetadataStorageOperation.Type operationType) {

        final List<MetadataStorageOperation> operations = partitionMetadata.getEpochStates()
            .entrySet()
            .stream()
            .map(entry -> createPutEpochStateOperation(
                partitionMetadata, entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());

        final String segmentKey = keyFactory.segmentMetadataKey(
            segmentMetadata.topicIdPartition(), segmentMetadata.remoteLogSegmentId());
        final byte[] serializedMetadata = serializer.serializeLogMetadata(segmentMetadata);
        operations.add(new MetadataStorageOperation(segmentKey, serializedMetadata, operationType));

        return remoteStorage.executeAtomically(operations);
    }

    private MetadataStorageOperation createPutEpochStateOperation(
        final TopicPartitionMetadata partitionMetadata, final Integer epoch,
        final RemoteLogLeaderEpochState leaderEpochState) {

        final String key = keyFactory.leaderEpochMetadataKey(partitionMetadata.getTopicIdPartition(), epoch);
        final byte[] value = serializer.serializeLeaderEpochMetadata(leaderEpochState);

        if (partitionMetadata.getNewEpochStates().contains(epoch)) {
            return MetadataStorageOperation.add(key, value);
        }

        return MetadataStorageOperation.update(key, value);
    }

    private void ensureNotClosed() {
        if (isClosing) {
            throw new IllegalStateException("Trying to access closing instance");
        }
    }

    class RemoteSegmentMetadataFetcher implements RemoteMetadataFetcher {

        @Override
        public List<RemoteLogSegmentMetadata> getSegmentsMetadata(final TopicIdPartition topicIdPartition,
                                                                  final List<RemoteLogSegmentId> segmentIds)
            throws MetadataStorageBackendException {
            Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");
            Objects.requireNonNull(segmentIds, "segmentIds can't be null");

            ensureNotClosed();

            final List<String> segmentKeys = segmentIds.stream()
                .map(id -> keyFactory.segmentMetadataKey(topicIdPartition, id))
                .collect(Collectors.toList());

            final List<RemoteLogSegmentMetadata> segmentsMetadata =
                deserialize(remoteStorage.getValuesWithKeyIn(segmentKeys));
            segmentsMetadata.sort(Comparator.comparingLong(RemoteLogSegmentMetadata::startOffset));
            return segmentsMetadata;
        }

        @Override
        public List<RemoteLogSegmentMetadata> getSegmentsMetadata(final TopicIdPartition topicIdPartition)
            throws MetadataStorageBackendException {
            Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");

            ensureNotClosed();

            final String partitionKey = keyFactory.partitionKey(topicIdPartition);
            return deserialize(remoteStorage.getValuesWithKeyLike(partitionKey));
        }

        @Override
        public RemoteLogSegmentMetadata getSegmentMetadata(final TopicIdPartition topicIdPartition,
                                                           final RemoteLogSegmentId remoteLogSegmentId)
            throws MetadataStorageBackendException {
            Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");
            Objects.requireNonNull(remoteLogSegmentId, "remoteLogSegmentId can't be null");

            ensureNotClosed();

            final String segmentMetadataKey =
                keyFactory.segmentMetadataKey(topicIdPartition, remoteLogSegmentId);
            return serializer.deserializeLogMetadata(remoteStorage.getValue(segmentMetadataKey));
        }

        @Override
        public RemoteLogLeaderEpochState getLeaderEpochState(
            final TopicIdPartition topicIdPartition, final int leaderEpoch) throws MetadataStorageBackendException {
            Objects.requireNonNull(topicIdPartition, "topicIdPartition can't be null");

            ensureNotClosed();

            final String leaderEpochKey = keyFactory.leaderEpochMetadataKey(topicIdPartition, leaderEpoch);
            return serializer.deserializeLeaderEpochMetadata(remoteStorage.getValue(leaderEpochKey));
        }

        private List<RemoteLogSegmentMetadata> deserialize(final Stream<byte[]> values) {
            Objects.requireNonNull(values, "values can't be null");

            ensureNotClosed();

            return values.map(serializer::deserializeLeaderEpochMetadata)
                .map(RemoteLogSegmentMetadata.class::cast)
                .collect(Collectors.toList());
        }
    }
}
