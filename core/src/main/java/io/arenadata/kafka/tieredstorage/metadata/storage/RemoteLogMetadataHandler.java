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

import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadataUpdate;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteMetadata;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteState;
import org.apache.kafka.server.log.remote.storage.RemoteResourceNotFoundException;
import org.apache.kafka.server.log.remote.storage.RemoteStorageException;

import io.arenadata.kafka.tieredstorage.metadata.MetadataNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteLogMetadataHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteLogMetadataHandler.class);

    private final FollowerPartitionLogMetadataHandler followerPartitionsHandler;
    private final LeaderPartitionLogMetadataHandler leaderPartitionsHandler;

    public RemoteLogMetadataHandler(final RemoteMetadataFetcher metadataFetcher) {
        this.leaderPartitionsHandler = new LeaderPartitionLogMetadataHandler();
        this.followerPartitionsHandler = new FollowerPartitionLogMetadataHandler(metadataFetcher);
    }

    public TopicPartitionMetadata addRemoteLogSegmentMetadata(final RemoteLogSegmentMetadata segmentMetadata) {
        return leaderPartitionsHandler.addRemoteLogSegmentMetadata(segmentMetadata);
    }

    public TopicPartitionMetadata updateRemoteLogSegmentMetadata(
        final RemoteLogSegmentMetadataUpdate segmentMetadataUpdate) throws RemoteResourceNotFoundException {
        return leaderPartitionsHandler.updateRemoteLogSegmentMetadata(segmentMetadataUpdate);
    }

    public Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(
        final TopicIdPartition topicIdPartition, final int leaderEpoch, final long offset)
        throws RemoteStorageException {

        return getFetchStrategy(topicIdPartition)
            .remoteLogSegmentMetadata(topicIdPartition, leaderEpoch, offset);
    }

    public Optional<Long> highestOffsetForEpoch(
        final TopicIdPartition topicIdPartition, final int leaderEpoch) throws RemoteStorageException {

        return getFetchStrategy(topicIdPartition)
            .highestOffsetForEpoch(topicIdPartition, leaderEpoch);
    }

    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(
        final TopicIdPartition topicIdPartition) throws RemoteStorageException {

        return getFetchStrategy(topicIdPartition)
            .listRemoteLogSegments(topicIdPartition, false);
    }

    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(
        final TopicIdPartition topicIdPartition, final int leaderEpoch) throws RemoteStorageException {

        return getFetchStrategy(topicIdPartition)
            .listRemoteLogSegments(topicIdPartition, leaderEpoch);
    }

    public void removeLogSegmentsMetadata(final Set<TopicIdPartition> partitions) {
        leaderPartitionsHandler.unlinkPartitions(partitions);
        followerPartitionsHandler.unlinkPartitions(partitions);
    }

    public void putRemotePartitionDeleteMetadata(final RemotePartitionDeleteMetadata partitionDeleteMetadata) {
        leaderPartitionsHandler.putRemotePartitionDeleteMetadata(partitionDeleteMetadata);
    }

    public void onPartitionLeadershipChanges(final Set<TopicIdPartition> newLeaderPartitions,
                                             final Set<TopicIdPartition> newFollowerPartitions)
        throws RemoteStorageException {
        // fetch metadata for each partition from leaderPartitions
        // from StorageBackend to in memory cache if it's not present
        for (final TopicIdPartition newLeaderPartition : newLeaderPartitions) {
            if (!leaderPartitionsHandler.isLeaderPartition(newLeaderPartition)) {
                leaderPartitionsHandler.addPartitionMetadata(newLeaderPartition,
                    loadSegmentsMetadata(newLeaderPartition));
            }
        }

        leaderPartitionsHandler.onPartitionLeadershipChanges(newLeaderPartitions, newFollowerPartitions);
        followerPartitionsHandler.onPartitionLeadershipChanges(newLeaderPartitions, newFollowerPartitions);
    }

    public boolean isValidFinalRemovePartitionMetadataState(
        final RemotePartitionDeleteMetadata partitionDeleteMetadata) {
        final TopicIdPartition topicIdPartition = partitionDeleteMetadata.topicIdPartition();
        final RemotePartitionDeleteState targetState = partitionDeleteMetadata.state();

        final RemotePartitionDeleteMetadata existingMetadata =
            leaderPartitionsHandler.getPartitionDeleteMetadata(topicIdPartition);
        final RemotePartitionDeleteState existingState = Optional.ofNullable(existingMetadata)
            .map(RemotePartitionDeleteMetadata::state)
            .orElse(null);

        if (!RemotePartitionDeleteState.isValidTransition(existingState, targetState)) {
            throw new IllegalStateException("Wrong RemotePartitionDeleteState transition. "
                + "Current  state: " + existingState + ", target state: " + targetState);
        }

        return targetState == RemotePartitionDeleteState.DELETE_PARTITION_FINISHED;
    }

    public RemoteLogSegmentMetadata buildUpdatedSegmentMetadata(
        final RemoteLogSegmentMetadataUpdate segmentMetadataUpdate) throws RemoteStorageException {
        final Optional<RemoteLogSegmentMetadata> segmentMetadata = leaderPartitionsHandler.remoteLogSegmentMetadata(
            segmentMetadataUpdate.topicIdPartition(), segmentMetadataUpdate.remoteLogSegmentId());

        if (segmentMetadata.isEmpty()) {
            throw new RemoteResourceNotFoundException("Remote log segment with provided id not found: "
                + segmentMetadataUpdate.remoteLogSegmentId());
        }

        return segmentMetadata.get().createWithUpdates(segmentMetadataUpdate);
    }

    private RemoteLogMetadataFetchStrategy getFetchStrategy(final TopicIdPartition topicIdPartition) {
        return leaderPartitionsHandler.isLeaderPartition(topicIdPartition)
            ? leaderPartitionsHandler
            : followerPartitionsHandler;
    }

    private Iterator<RemoteLogSegmentMetadata> loadSegmentsMetadata(final TopicIdPartition topicIdPartition) {
        try {
            return followerPartitionsHandler.listRemoteLogSegments(topicIdPartition, true);
        } catch (final MetadataNotFoundException resourceNotFoundException) {
            // we don't fail here, because this method can be called before
            // local retention time passed, when we assign leader partitions to this broker
            LOG.warn("Remote log segments not found for " + topicIdPartition);
            return Collections.emptyIterator();
        }
    }

    interface RemoteLogMetadataFetchStrategy {
        Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(
            TopicIdPartition topicIdPartition, int leaderEpoch, long offset) throws RemoteStorageException;

        Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(
            TopicIdPartition topicIdPartition, boolean forceFetch) throws RemoteStorageException;

        Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(
            TopicIdPartition topicIdPartition, int leaderEpoch) throws RemoteStorageException;

        Optional<Long> highestOffsetForEpoch(
            TopicIdPartition topicIdPartition, int leaderEpoch) throws RemoteStorageException;

        void unlinkPartitions(Set<TopicIdPartition> partitions);

        void onPartitionLeadershipChanges(Set<TopicIdPartition> leaderPartitions,
                                          Set<TopicIdPartition> followerPartitions);
    }
}
