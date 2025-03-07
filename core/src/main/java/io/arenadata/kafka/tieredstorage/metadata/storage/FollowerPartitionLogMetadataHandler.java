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
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteStorageException;

import io.arenadata.kafka.tieredstorage.metadata.MetadataNotFoundException;

class FollowerPartitionLogMetadataHandler implements RemoteLogMetadataHandler.RemoteLogMetadataFetchStrategy {
    private final Set<TopicIdPartition> followerPartitionIds = ConcurrentHashMap.newKeySet();

    private final RemoteMetadataFetcher metadataFetcher;

    FollowerPartitionLogMetadataHandler(final RemoteMetadataFetcher metadataFetcher) {
        this.metadataFetcher = metadataFetcher;
    }

    @Override
    public Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(
        final TopicIdPartition topicIdPartition, final int leaderEpoch, final long offset)
        throws RemoteStorageException {
        validatePartitionAssigned(topicIdPartition);

        final RemoteLogSegmentId remoteLogSegmentId =
            metadataFetcher.getLeaderEpochState(topicIdPartition, leaderEpoch)
                .floorEntry(offset);

        if (remoteLogSegmentId == null) {
            // If the offset is lower than the minimum offset available in metadata then return empty.
            return Optional.empty();
        }

        final RemoteLogSegmentMetadata metadata =
            metadataFetcher.getSegmentMetadata(topicIdPartition, remoteLogSegmentId);
        // Check whether the given offset with leaderEpoch exists in this segment.
        // Check for epoch's offset boundaries with in this segment.
        //      1. Get the next epoch's start offset -1 if exists
        //      2. If no next epoch exists, then segment end offset can be considered as epoch's relative end offset.
        final Map.Entry<Integer, Long> nextEntry = metadata.segmentLeaderEpochs().higherEntry(leaderEpoch);
        final long epochEndOffset = (nextEntry != null) ? nextEntry.getValue() - 1 : metadata.endOffset();

        // Return empty when target offset > epoch's end offset.
        return offset > epochEndOffset ? Optional.empty() : Optional.of(metadata);
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(final TopicIdPartition topicIdPartition,
                                                                    final boolean forceFetch) {
        if (!forceFetch) {
            validatePartitionAssigned(topicIdPartition);
        }

        return metadataFetcher.getSegmentsMetadata(topicIdPartition).iterator();
    }

    @Override
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(final TopicIdPartition topicIdPartition,
                                                                    final int leaderEpoch) {
        validatePartitionAssigned(topicIdPartition);

        final RemoteLogLeaderEpochState leaderEpochState =
            metadataFetcher.getLeaderEpochState(topicIdPartition, leaderEpoch);

        final List<RemoteLogSegmentId> remoteLogSegmentIds = leaderEpochState.listAllRemoteLogSegments();
        if (remoteLogSegmentIds.isEmpty()) {
            return Collections.emptyIterator();
        }

        final List<RemoteLogSegmentMetadata> epochSegments =
            metadataFetcher.getSegmentsMetadata(topicIdPartition, remoteLogSegmentIds);
        epochSegments.sort(Comparator.comparingLong(RemoteLogSegmentMetadata::startOffset));
        return epochSegments.iterator();
    }

    @Override
    public Optional<Long> highestOffsetForEpoch(final TopicIdPartition topicIdPartition,
                                                final int leaderEpoch) {
        validatePartitionAssigned(topicIdPartition);

        final RemoteLogLeaderEpochState leaderEpochState
            = metadataFetcher.getLeaderEpochState(topicIdPartition, leaderEpoch);

        return Optional.ofNullable(leaderEpochState)
            .map(RemoteLogLeaderEpochState::highestLogOffset);
    }

    @Override
    public void unlinkPartitions(final Set<TopicIdPartition> partitions) {
        followerPartitionIds.removeAll(partitions);
    }

    @Override
    public void onPartitionLeadershipChanges(final Set<TopicIdPartition> leaderPartitions,
                                             final Set<TopicIdPartition> followerPartitions) {
        followerPartitionIds.addAll(followerPartitions);
        followerPartitionIds.removeAll(leaderPartitions);
    }

    private void validatePartitionAssigned(final TopicIdPartition topicIdPartition) {
        if (!followerPartitionIds.contains(topicIdPartition)) {
            throw new MetadataNotFoundException(
                "Partition " + topicIdPartition + " wasn't assigned to current RemoteLogMetadataManager");
        }
    }
}
