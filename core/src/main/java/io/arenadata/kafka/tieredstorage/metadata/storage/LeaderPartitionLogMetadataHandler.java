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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.server.log.remote.metadata.storage.LeaderSegmentsMetadataCache;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadataUpdate;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteMetadata;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteState;
import org.apache.kafka.server.log.remote.storage.RemoteResourceNotFoundException;
import org.apache.kafka.server.log.remote.storage.RemoteStorageException;

class LeaderPartitionLogMetadataHandler implements RemoteLogMetadataHandler.RemoteLogMetadataFetchStrategy {

    private final Map<TopicIdPartition, LeaderSegmentsMetadataCache> idToLeaderSegmentsMetadataCache
        = new ConcurrentHashMap<>();

    private final Map<TopicIdPartition, RemotePartitionDeleteMetadata> idToLeaderPartitionDeleteMetadata =
        new ConcurrentHashMap<>();

    @Override
    public Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(
        final TopicIdPartition topicIdPartition, final int leaderEpoch, final long offset)
        throws RemoteStorageException {
        return getSegmentsMetadataCache(topicIdPartition).remoteLogSegmentMetadata(leaderEpoch, offset);
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(
        final TopicIdPartition topicIdPartition, final boolean forceFetch) throws RemoteResourceNotFoundException {
        return getSegmentsMetadataCache(topicIdPartition).listAllRemoteLogSegments();
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(
        final TopicIdPartition topicIdPartition, final int leaderEpoch) throws RemoteResourceNotFoundException {
        return getSegmentsMetadataCache(topicIdPartition).listRemoteLogSegments(leaderEpoch);
    }

    @Override
    public Optional<Long> highestOffsetForEpoch(final TopicIdPartition topicIdPartition,
                                                final int leaderEpoch) throws RemoteStorageException {
        return getSegmentsMetadataCache(topicIdPartition).highestOffsetForEpoch(leaderEpoch);
    }

    @Override
    public void unlinkPartitions(final Set<TopicIdPartition> partitions) {
        idToLeaderSegmentsMetadataCache.keySet().removeAll(partitions);
    }

    @Override
    public void onPartitionLeadershipChanges(final Set<TopicIdPartition> newLeaderPartitions,
                                             final Set<TopicIdPartition> newFollowerPartitions) {
        idToLeaderSegmentsMetadataCache.keySet().removeAll(newFollowerPartitions);
    }

    Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(
        final TopicIdPartition topicIdPartition, final RemoteLogSegmentId remoteLogSegmentId)
        throws RemoteStorageException {

        return getSegmentsMetadataCache(topicIdPartition).getSegmentMetadata(remoteLogSegmentId);
    }

    void putRemotePartitionDeleteMetadata(final RemotePartitionDeleteMetadata partitionDeleteMetadata) {

        final TopicIdPartition topicIdPartition = partitionDeleteMetadata.topicIdPartition();
        idToLeaderPartitionDeleteMetadata.put(topicIdPartition, partitionDeleteMetadata);

        if (partitionDeleteMetadata.state() == RemotePartitionDeleteState.DELETE_PARTITION_FINISHED) {
            // Remove the association for the partition.
            idToLeaderSegmentsMetadataCache.remove(topicIdPartition);
            idToLeaderPartitionDeleteMetadata.remove(topicIdPartition);
        }
    }

    TopicPartitionMetadata addRemoteLogSegmentMetadata(final RemoteLogSegmentMetadata segmentMetadata) {
        final RemoteLogSegmentId remoteLogSegmentId = segmentMetadata.remoteLogSegmentId();

        final LeaderSegmentsMetadataCache metadataCache = idToLeaderSegmentsMetadataCache
            .computeIfAbsent(remoteLogSegmentId.topicIdPartition(), id -> new LeaderSegmentsMetadataCache());

        final Set<Integer> previousLeaderEpochs = getCurrentLeaderEpochs(segmentMetadata.topicIdPartition());

        metadataCache.addCopyInProgressSegment(segmentMetadata);

        return getPartitionState(segmentMetadata.topicIdPartition(), previousLeaderEpochs);
    }

    TopicPartitionMetadata updateRemoteLogSegmentMetadata(
        final RemoteLogSegmentMetadataUpdate segmentMetadataUpdate) throws RemoteResourceNotFoundException {
        final TopicIdPartition topicIdPartition = segmentMetadataUpdate.remoteLogSegmentId().topicIdPartition();

        final Set<Integer> previousLeaderEpochs = getCurrentLeaderEpochs(segmentMetadataUpdate.topicIdPartition());

        getSegmentsMetadataCache(topicIdPartition)
            .updateRemoteLogSegmentMetadata(segmentMetadataUpdate);

        return getPartitionState(segmentMetadataUpdate.topicIdPartition(), previousLeaderEpochs);
    }

    RemotePartitionDeleteMetadata getPartitionDeleteMetadata(final TopicIdPartition topicIdPartition) {
        return idToLeaderPartitionDeleteMetadata.get(topicIdPartition);
    }

    void addPartitionMetadata(
        final TopicIdPartition topicIdPartition, final Iterator<RemoteLogSegmentMetadata> partitionSegments) {
        final LeaderSegmentsMetadataCache metadataCache = new LeaderSegmentsMetadataCache();
        partitionSegments.forEachRemaining(metadataCache::load);
        idToLeaderSegmentsMetadataCache.put(topicIdPartition, metadataCache);
    }

    boolean isLeaderPartition(final TopicIdPartition topicIdPartition) {
        return idToLeaderSegmentsMetadataCache.containsKey(topicIdPartition);
    }

    private Set<Integer> getCurrentLeaderEpochs(
        final TopicIdPartition topicIdPartition) {
        final Set<Integer> leaderEpochs = idToLeaderSegmentsMetadataCache.get(topicIdPartition)
            .getLeaderEpochStates()
            .keySet();
        return new HashSet<>(leaderEpochs);
    }

    private LeaderSegmentsMetadataCache getSegmentsMetadataCache(final TopicIdPartition topicIdPartition)
        throws RemoteResourceNotFoundException {
        final LeaderSegmentsMetadataCache cache = idToLeaderSegmentsMetadataCache.get(topicIdPartition);
        if (cache == null) {
            throw new RemoteResourceNotFoundException("No existing metadata found for partition: " + topicIdPartition);
        }

        return cache;
    }

    private TopicPartitionMetadata getPartitionState(final TopicIdPartition topicIdPartition,
                                                     final Set<Integer> previousLeaderEpochs) {
        final Map<Integer, RemoteLogLeaderEpochState> leaderEpochStates =
            idToLeaderSegmentsMetadataCache.get(topicIdPartition).getLeaderEpochStates();

        final Set<Integer> newEpochStates = leaderEpochStates.keySet().stream()
            .filter(epoch -> !previousLeaderEpochs.contains(epoch))
            .collect(Collectors.toSet());

        return new TopicPartitionMetadata(topicIdPartition, leaderEpochStates, newEpochStates);
    }
}
