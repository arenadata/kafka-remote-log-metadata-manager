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

package org.apache.kafka.server.log.remote.metadata.storage;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;

import io.arenadata.kafka.tieredstorage.metadata.storage.RemoteLogLeaderEpochState;

public class LeaderSegmentsMetadataCache extends ArenadataRemoteLogMetadataCache {

    public void load(final RemoteLogSegmentMetadata logSegmentMetadata) {
        switch (logSegmentMetadata.state()) {
            case COPY_SEGMENT_STARTED:
                addCopyInProgressSegment(logSegmentMetadata);
                break;
            case COPY_SEGMENT_FINISHED:
                addEphemeralCopyInProgressSegment(logSegmentMetadata);
                handleSegmentWithCopySegmentFinishedState(logSegmentMetadata);
                break;
            case DELETE_SEGMENT_STARTED:
                addEphemeralCopyInProgressSegment(logSegmentMetadata);
                handleSegmentWithDeleteSegmentStartedState(logSegmentMetadata);
                break;
            default:
                throw new IllegalArgumentException(
                    "Given remoteLogSegmentMetadata has invalid state: " + logSegmentMetadata);
        }
    }

    public void addEphemeralCopyInProgressSegment(final RemoteLogSegmentMetadata remoteLogSegmentMetadata) {
        idToSegmentMetadata.put(
            remoteLogSegmentMetadata.remoteLogSegmentId(), remoteLogSegmentMetadata);
    }

    public Optional<RemoteLogSegmentMetadata> getSegmentMetadata(final RemoteLogSegmentId remoteLogSegmentId) {
        return Optional.ofNullable(idToSegmentMetadata.get(remoteLogSegmentId));
    }

    public Map<Integer, RemoteLogLeaderEpochState> getLeaderEpochStates() {
        return leaderEpochEntries.entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> buildSerializableLeaderEpochState(entry.getValue())
            ));
    }

    private RemoteLogLeaderEpochState buildSerializableLeaderEpochState(
        final ArenadataRemoteLogLeaderEpochState leaderEpochState) {
        return new RemoteLogLeaderEpochState(
            leaderEpochState.rawOffsetToId(),
            leaderEpochState.rawUnreferencedSegmentIds(),
            leaderEpochState.highestLogOffset()
        );
    }
}
