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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;

/**
 * This class represents the in-memory state of segments associated with a leader epoch.
 * This includes the mapping of offset to segment ids and unreferenced segments which are not mapped
 * to any offset, but they exist in remote storage.
 */
public class RemoteLogLeaderEpochState implements Serializable {

    // It contains offset to segment ids mapping with the segment state as COPY_SEGMENT_FINISHED.
    private final NavigableMap<Long, RemoteLogSegmentId> offsetToId;

    /**
     * It represents unreferenced segments for this leader epoch. It contains the segments still in COPY_SEGMENT_STARTED
     * and DELETE_SEGMENT_STARTED state or these have been replaced by callers with other segments having the same
     * start offset for the leader epoch.
     */
    private final Set<RemoteLogSegmentId> unreferencedSegmentIds;

    // It represents the highest log offset of the segments that reached the COPY_SEGMENT_FINISHED state.
    private final Long highestLogOffset;

    public RemoteLogLeaderEpochState(final NavigableMap<Long, RemoteLogSegmentId> offsetToId,
                                     final Set<RemoteLogSegmentId> unreferencedSegmentIds,
                                     final Long highestLogOffset) {
        this.offsetToId = offsetToId;
        this.unreferencedSegmentIds = unreferencedSegmentIds;
        this.highestLogOffset = highestLogOffset;
    }

    public RemoteLogLeaderEpochState() {
        this.offsetToId = new TreeMap<>();
        this.unreferencedSegmentIds = new HashSet<>();
        this.highestLogOffset = 0L;
    }

    Long highestLogOffset() {
        return highestLogOffset;
    }

    /**
     * Returns the RemoteLogSegmentId of a segment for the given offset, if there exists a mapping associated with
     * the greatest offset less than or equal to the given offset, or null if there is no such mapping.
     *
     * @param offset offset
     */
    RemoteLogSegmentId floorEntry(final long offset) {
        final Map.Entry<Long, RemoteLogSegmentId> entry = offsetToId.floorEntry(offset);

        return entry == null ? null : entry.getValue();
    }

    /**
     * Returns all ids of the segments associated with this leader epoch.
     */
    public List<RemoteLogSegmentId> listAllRemoteLogSegments() {
        // Return all the segments including unreferenced metadata.
        final int size = offsetToId.size() + unreferencedSegmentIds.size();
        if (size == 0) {
            return Collections.emptyList();
        }

        final List<RemoteLogSegmentId> allSegmentIds = new ArrayList<>(offsetToId.values());
        allSegmentIds.addAll(unreferencedSegmentIds);
        return allSegmentIds;
    }

    public NavigableMap<Long, RemoteLogSegmentId> getOffsetToId() {
        return offsetToId;
    }

    public Set<RemoteLogSegmentId> getUnreferencedSegmentIds() {
        return unreferencedSegmentIds;
    }

    public Long getHighestLogOffset() {
        return highestLogOffset;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final RemoteLogLeaderEpochState that = (RemoteLogLeaderEpochState) obj;
        return Objects.equals(offsetToId, that.offsetToId)
            && Objects.equals(unreferencedSegmentIds, that.unreferencedSegmentIds)
            && Objects.equals(highestLogOffset, that.highestLogOffset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offsetToId, unreferencedSegmentIds, highestLogOffset);
    }
}
