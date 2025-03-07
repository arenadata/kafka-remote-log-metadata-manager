/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.server.log.remote.metadata.storage;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadataUpdate;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentState;
import org.apache.kafka.server.log.remote.storage.RemoteResourceNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka's {@link RemoteLogMetadataCache} copy
 * in order to support {@link ArenadataRemoteLogLeaderEpochState}
 */
public class ArenadataRemoteLogMetadataCache {

    private static final Logger log = LoggerFactory.getLogger(ArenadataRemoteLogMetadataCache.class);

    // It contains all the segment-id to metadata mappings which did not reach the terminal state viz DELETE_SEGMENT_FINISHED.
    protected final ConcurrentMap<RemoteLogSegmentId, RemoteLogSegmentMetadata> idToSegmentMetadata
            = new ConcurrentHashMap<>();

    // It contains leader epoch to the respective entry containing the state.
    // TODO We are not clearing the entry for epoch when RemoteLogLeaderEpochState becomes empty. This will be addressed
    // later. We will look into it when we integrate these APIs along with RemoteLogManager changes.
    // https://issues.apache.org/jira/browse/KAFKA-12641
    protected final ConcurrentMap<Integer, ArenadataRemoteLogLeaderEpochState> leaderEpochEntries = new ConcurrentHashMap<>();

    private final CountDownLatch initializedLatch = new CountDownLatch(1);

    public void markInitialized() {
        initializedLatch.countDown();
    }

    public boolean isInitialized() {
        return initializedLatch.getCount() == 0;
    }

    /**
     * Returns {@link RemoteLogSegmentMetadata} if it exists for the given leader-epoch containing the offset and with
     * {@link RemoteLogSegmentState#COPY_SEGMENT_FINISHED} state, else returns {@link Optional#empty()}.
     *
     * @param leaderEpoch leader epoch for the given offset
     * @param offset      offset
     * @return the requested remote log segment metadata if it exists.
     */
    public Optional<RemoteLogSegmentMetadata> remoteLogSegmentMetadata(int leaderEpoch, long offset) {
        ArenadataRemoteLogLeaderEpochState remoteLogLeaderEpochState = leaderEpochEntries.get(leaderEpoch);

        if (remoteLogLeaderEpochState == null) {
            return Optional.empty();
        }

        // Look for floor entry as the given offset may exist in this entry.
        RemoteLogSegmentId remoteLogSegmentId = remoteLogLeaderEpochState.floorEntry(offset);
        if (remoteLogSegmentId == null) {
            // If the offset is lower than the minimum offset available in metadata then return empty.
            return Optional.empty();
        }

        RemoteLogSegmentMetadata metadata = idToSegmentMetadata.get(remoteLogSegmentId);
        // Check whether the given offset with leaderEpoch exists in this segment.
        // Check for epoch's offset boundaries with in this segment.
        //      1. Get the next epoch's start offset -1 if exists
        //      2. If no next epoch exists, then segment end offset can be considered as epoch's relative end offset.
        Map.Entry<Integer, Long> nextEntry = metadata.segmentLeaderEpochs().higherEntry(leaderEpoch);
        long epochEndOffset = (nextEntry != null) ? nextEntry.getValue() - 1 : metadata.endOffset();

        // Return empty when target offset > epoch's end offset.
        return offset > epochEndOffset ? Optional.empty() : Optional.of(metadata);
    }

    public void updateRemoteLogSegmentMetadata(RemoteLogSegmentMetadataUpdate metadataUpdate)
            throws RemoteResourceNotFoundException {
        log.debug("Updating remote log segment metadata: [{}]", metadataUpdate);
        Objects.requireNonNull(metadataUpdate, "metadataUpdate can not be null");

        RemoteLogSegmentState targetState = metadataUpdate.state();
        RemoteLogSegmentId remoteLogSegmentId = metadataUpdate.remoteLogSegmentId();
        RemoteLogSegmentMetadata existingMetadata = idToSegmentMetadata.get(remoteLogSegmentId);
        if (existingMetadata == null) {
            throw new RemoteResourceNotFoundException("No remote log segment metadata found for :" +
                                                      remoteLogSegmentId);
        }

        // Check the state transition.
        checkStateTransition(existingMetadata.state(), targetState);

        switch (targetState) {
            case COPY_SEGMENT_STARTED:
                // Callers should use addCopyInProgressSegment to add RemoteLogSegmentMetadata with state as
                // RemoteLogSegmentState.COPY_SEGMENT_STARTED.
                throw new IllegalArgumentException("metadataUpdate: " + metadataUpdate + " with state " + RemoteLogSegmentState.COPY_SEGMENT_STARTED +
                                                   " can not be updated");
            case COPY_SEGMENT_FINISHED:
                handleSegmentWithCopySegmentFinishedState(existingMetadata.createWithUpdates(metadataUpdate));
                break;
            case DELETE_SEGMENT_STARTED:
                handleSegmentWithDeleteSegmentStartedState(existingMetadata.createWithUpdates(metadataUpdate));
                break;
            case DELETE_SEGMENT_FINISHED:
                handleSegmentWithDeleteSegmentFinishedState(existingMetadata.createWithUpdates(metadataUpdate));
                break;
            default:
                throw new IllegalArgumentException("Metadata with the state " + targetState + " is not supported");
        }
    }

    protected final void handleSegmentWithCopySegmentFinishedState(RemoteLogSegmentMetadata remoteLogSegmentMetadata) {
        doHandleSegmentStateTransitionForLeaderEpochs(remoteLogSegmentMetadata,
                                                      (leaderEpoch, remoteLogLeaderEpochState, startOffset, segmentId) -> {
                                                          long leaderEpochEndOffset = highestOffsetForEpoch(leaderEpoch,
                                                                                                            remoteLogSegmentMetadata);
                                                          remoteLogLeaderEpochState.handleSegmentWithCopySegmentFinishedState(startOffset,
                                                                                                                              segmentId,
                                                                                                                              leaderEpochEndOffset);
                                                      });

        // Put the entry with the updated metadata.
        idToSegmentMetadata.put(remoteLogSegmentMetadata.remoteLogSegmentId(), remoteLogSegmentMetadata);
    }

    protected final void handleSegmentWithDeleteSegmentStartedState(RemoteLogSegmentMetadata remoteLogSegmentMetadata) {
        log.debug("Cleaning up the state for : [{}]", remoteLogSegmentMetadata);

        doHandleSegmentStateTransitionForLeaderEpochs(remoteLogSegmentMetadata,
                                                      (leaderEpoch, remoteLogLeaderEpochState, startOffset, segmentId) ->
                                                              remoteLogLeaderEpochState.handleSegmentWithDeleteSegmentStartedState(startOffset, segmentId));

        // Put the entry with the updated metadata.
        idToSegmentMetadata.put(remoteLogSegmentMetadata.remoteLogSegmentId(), remoteLogSegmentMetadata);
    }

    private void handleSegmentWithDeleteSegmentFinishedState(RemoteLogSegmentMetadata remoteLogSegmentMetadata) {
        log.debug("Removing the entry as it reached the terminal state: [{}]", remoteLogSegmentMetadata);

        doHandleSegmentStateTransitionForLeaderEpochs(remoteLogSegmentMetadata,
                                                      (leaderEpoch, remoteLogLeaderEpochState, startOffset, segmentId) ->
                                                              remoteLogLeaderEpochState.handleSegmentWithDeleteSegmentFinishedState(segmentId));

        // Remove the segment's id to metadata mapping because this segment is considered as deleted, and it cleared all
        // the state of this segment in the cache.
        idToSegmentMetadata.remove(remoteLogSegmentMetadata.remoteLogSegmentId());
    }

    private void doHandleSegmentStateTransitionForLeaderEpochs(RemoteLogSegmentMetadata remoteLogSegmentMetadata,
                                                               ArenadataRemoteLogLeaderEpochState.Action action) {
        RemoteLogSegmentId remoteLogSegmentId = remoteLogSegmentMetadata.remoteLogSegmentId();
        Map<Integer, Long> leaderEpochToOffset = remoteLogSegmentMetadata.segmentLeaderEpochs();

        // Go through all the leader epochs and apply the given action.
        for (Map.Entry<Integer, Long> entry : leaderEpochToOffset.entrySet()) {
            Integer leaderEpoch = entry.getKey();
            Long startOffset = entry.getValue();
            // leaderEpochEntries will be empty when resorting the metadata from snapshot.
            ArenadataRemoteLogLeaderEpochState remoteLogLeaderEpochState = leaderEpochEntries.computeIfAbsent(
                    leaderEpoch, x -> new ArenadataRemoteLogLeaderEpochState());
            action.accept(leaderEpoch, remoteLogLeaderEpochState, startOffset, remoteLogSegmentId);
        }
    }

    private static long highestOffsetForEpoch(Integer leaderEpoch, RemoteLogSegmentMetadata segmentMetadata) {
        // Compute the highest offset for the leader epoch with in the segment
        NavigableMap<Integer, Long> epochToOffset = segmentMetadata.segmentLeaderEpochs();
        Map.Entry<Integer, Long> nextEntry = epochToOffset.higherEntry(leaderEpoch);

        return nextEntry != null ? nextEntry.getValue() - 1 : segmentMetadata.endOffset();
    }

    /**
     * Returns all the segments stored in this cache.
     *
     * @return
     */
    public Iterator<RemoteLogSegmentMetadata> listAllRemoteLogSegments() {
        // Return all the segments including unreferenced metadata.
        return Collections.unmodifiableCollection(idToSegmentMetadata.values()).iterator();
    }

    /**
     * Returns all the segments mapped to the leader epoch that exist in this cache sorted by {@link RemoteLogSegmentMetadata#startOffset()}.
     *
     * @param leaderEpoch leader epoch.
     */
    public Iterator<RemoteLogSegmentMetadata> listRemoteLogSegments(int leaderEpoch)
            throws RemoteResourceNotFoundException {
        ArenadataRemoteLogLeaderEpochState remoteLogLeaderEpochState = leaderEpochEntries.get(leaderEpoch);
        if (remoteLogLeaderEpochState == null) {
            return Collections.emptyIterator();
        }

        return remoteLogLeaderEpochState.listAllRemoteLogSegments(idToSegmentMetadata);
    }

    /**
     * Returns the highest offset of a segment for the given leader epoch if exists, else it returns empty. The segments
     * that have reached the {@link RemoteLogSegmentState#COPY_SEGMENT_FINISHED} or later states are considered here.
     *
     * @param leaderEpoch leader epoch
     */
    public Optional<Long> highestOffsetForEpoch(int leaderEpoch) {
        ArenadataRemoteLogLeaderEpochState entry = leaderEpochEntries.get(leaderEpoch);
        return entry != null ? Optional.ofNullable(entry.highestLogOffset()) : Optional.empty();
    }

    /**
     * This method tracks the given remote segment as not yet available for reads. It does not add the segment
     * leader epoch offset mapping until this segment reaches COPY_SEGMENT_FINISHED state.
     *
     * @param remoteLogSegmentMetadata RemoteLogSegmentMetadata instance
     */
    public void addCopyInProgressSegment(RemoteLogSegmentMetadata remoteLogSegmentMetadata) {
        log.debug("Adding to in-progress state: [{}]", remoteLogSegmentMetadata);
        Objects.requireNonNull(remoteLogSegmentMetadata, "remoteLogSegmentMetadata can not be null");

        // This method is allowed only to add remote log segment with the initial state(which is RemoteLogSegmentState.COPY_SEGMENT_STARTED)
        // but not to update the existing remote log segment metadata.
        if (remoteLogSegmentMetadata.state() != RemoteLogSegmentState.COPY_SEGMENT_STARTED) {
            throw new IllegalArgumentException(
                    "Given remoteLogSegmentMetadata:" + remoteLogSegmentMetadata + " should have state as " + RemoteLogSegmentState.COPY_SEGMENT_STARTED
                    + " but it contains state as: " + remoteLogSegmentMetadata.state());
        }

        RemoteLogSegmentId remoteLogSegmentId = remoteLogSegmentMetadata.remoteLogSegmentId();
        RemoteLogSegmentMetadata existingMetadata = idToSegmentMetadata.get(remoteLogSegmentId);
        checkStateTransition(existingMetadata != null ? existingMetadata.state() : null,
                remoteLogSegmentMetadata.state());

        for (Integer epoch : remoteLogSegmentMetadata.segmentLeaderEpochs().keySet()) {
            leaderEpochEntries.computeIfAbsent(epoch, leaderEpoch -> new ArenadataRemoteLogLeaderEpochState())
                    .handleSegmentWithCopySegmentStartedState(remoteLogSegmentId);
        }

        idToSegmentMetadata.put(remoteLogSegmentId, remoteLogSegmentMetadata);
    }

    private void checkStateTransition(RemoteLogSegmentState existingState, RemoteLogSegmentState targetState) {
        if (!RemoteLogSegmentState.isValidTransition(existingState, targetState)) {
            throw new IllegalStateException(
                    "Current state: " + existingState + " can not be transitioned to target state: " + targetState);
        }
    }

}
