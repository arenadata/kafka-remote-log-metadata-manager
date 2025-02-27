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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;

import io.arenadata.kafka.tieredstorage.metadata.storage.RemoteLogLeaderEpochState;

public class LeaderEpochMetadataSerializer implements Serializer<RemoteLogLeaderEpochState> {

    @Override
    public byte[] serialize(final RemoteLogLeaderEpochState epochState) {
        Objects.requireNonNull(epochState);

        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
             final ObjectOutputStream oos = new ObjectOutputStream(bos)) {

            serializeOffsetToId(oos, epochState.getOffsetToId());
            serializeUnreferencedSegmentIds(oos, epochState.getUnreferencedSegmentIds());
            oos.writeObject(epochState.getHighestLogOffset());

            return bos.toByteArray();
        } catch (final Exception e) {
            throw new KafkaException(e);
        }
    }

    @Override
    public RemoteLogLeaderEpochState deserialize(final byte[] data) {
        try (final ByteArrayInputStream bis = new ByteArrayInputStream(data);
             final ObjectInputStream ois = new ObjectInputStream(bis)) {
            return new RemoteLogLeaderEpochState(
                deserializeOffsetToId(ois),
                deserializeUnreferencedSegmentIds(ois),
                (Long) ois.readObject()
            );
        } catch (final Exception e) {
            throw new KafkaException(e);
        }
    }

    private void serializeOffsetToId(final ObjectOutputStream oos,
                                     final Map<Long, RemoteLogSegmentId> offsetToId)
        throws IOException {
        oos.writeInt(offsetToId.size());
        for (final var entry: offsetToId.entrySet()) {
            oos.writeLong(entry.getKey());
            serializeRemoteLogSegmentId(oos, entry.getValue());
        }
    }

    private NavigableMap<Long, RemoteLogSegmentId> deserializeOffsetToId(
        final ObjectInputStream ois) throws IOException {
        final int size = ois.readInt();

        final NavigableMap<Long, RemoteLogSegmentId> offsetToId = new TreeMap<>();
        for (int i = 0; i < size; ++i) {
            offsetToId.put(
                ois.readLong(),
                deserializeRemoteLogSegmentId(ois)
            );
        }

        return offsetToId;
    }

    private void serializeUnreferencedSegmentIds(final ObjectOutputStream oos,
                                                 final Set<RemoteLogSegmentId> unreferencedSegmentIds)
        throws IOException {
        oos.writeInt(unreferencedSegmentIds.size());
        for (final RemoteLogSegmentId remoteLogSegmentId: unreferencedSegmentIds) {
            serializeRemoteLogSegmentId(oos, remoteLogSegmentId);
        }
    }

    private Set<RemoteLogSegmentId> deserializeUnreferencedSegmentIds(final ObjectInputStream ois) throws IOException {
        final int size = ois.readInt();

        final Set<RemoteLogSegmentId> unreferencedSegmentIds = new HashSet<>();
        for (int i = 0; i < size; ++i) {
            unreferencedSegmentIds.add(deserializeRemoteLogSegmentId(ois));
        }

        return unreferencedSegmentIds;
    }

    private void serializeRemoteLogSegmentId(final ObjectOutputStream oos,
                                             final RemoteLogSegmentId remoteLogSegmentId) throws IOException {
        serializeTopicIdPartition(oos, remoteLogSegmentId.topicIdPartition());
        serializeUuid(oos, remoteLogSegmentId.id());
    }

    private RemoteLogSegmentId deserializeRemoteLogSegmentId(final ObjectInputStream ois) throws IOException {
        return new RemoteLogSegmentId(
            deserializeTopicIdPartition(ois),
            deserializeUuid(ois)
        );
    }

    private void serializeTopicIdPartition(final ObjectOutputStream oos,
                                           final TopicIdPartition topicIdPartition) throws IOException {
        serializeUuid(oos, topicIdPartition.topicId());
        oos.writeInt(topicIdPartition.partition());
        oos.writeUTF(topicIdPartition.topic());
    }

    private TopicIdPartition deserializeTopicIdPartition(final ObjectInputStream ois) throws IOException {
        return new TopicIdPartition(
            deserializeUuid(ois),
            ois.readInt(),
            ois.readUTF()
        );
    }

    private void serializeUuid(final ObjectOutputStream oos,
                               final Uuid uuid) throws IOException {
        oos.writeLong(uuid.getMostSignificantBits());
        oos.writeLong(uuid.getLeastSignificantBits());
    }

    private Uuid deserializeUuid(final ObjectInputStream ois) throws IOException {
        return new Uuid(
            ois.readLong(),
            ois.readLong()
        );
    }
}
