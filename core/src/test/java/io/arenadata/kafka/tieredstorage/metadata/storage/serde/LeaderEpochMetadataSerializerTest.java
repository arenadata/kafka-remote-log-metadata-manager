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

package io.arenadata.kafka.tieredstorage.metadata.storage.serde;

import java.util.Collections;
import java.util.HashSet;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;

import io.arenadata.kafka.tieredstorage.metadata.storage.RemoteLogLeaderEpochState;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeaderEpochMetadataSerializerTest {

    private final LeaderEpochMetadataSerializer serializer = new LeaderEpochMetadataSerializer();

    public static Stream<RemoteLogLeaderEpochState> leaderEpochStates() {
        final NavigableMap<Long, RemoteLogSegmentId> offsetToId = new TreeMap<>();
        final Set<RemoteLogSegmentId> unreferencedSegmentIds = new HashSet<>();

        final TopicIdPartition partitionId = new TopicIdPartition(Uuid.randomUuid(), 1, "test");

        offsetToId.put(1L, new RemoteLogSegmentId(partitionId, Uuid.randomUuid()));
        offsetToId.put(32L, new RemoteLogSegmentId(partitionId, Uuid.randomUuid()));
        unreferencedSegmentIds.add(new RemoteLogSegmentId(partitionId, Uuid.randomUuid()));

        return Stream.of(
            new RemoteLogLeaderEpochState(offsetToId, unreferencedSegmentIds, 1112L),
            new RemoteLogLeaderEpochState(Collections.emptyNavigableMap(), unreferencedSegmentIds, 901L),
            new RemoteLogLeaderEpochState(offsetToId, Collections.emptySet(), 1L),
            new RemoteLogLeaderEpochState(offsetToId, unreferencedSegmentIds, null),
            new RemoteLogLeaderEpochState()
        );
    }

    @ParameterizedTest
    @MethodSource("leaderEpochStates")
    void testSerializeDeserialize(final RemoteLogLeaderEpochState leaderEpochState) {
        final byte[] bytes = serializer.serialize(leaderEpochState);
        final RemoteLogLeaderEpochState deserializedLeaderEpochState = serializer.deserialize(bytes);

        assertEquals(leaderEpochState, deserializedLeaderEpochState);
    }
}
