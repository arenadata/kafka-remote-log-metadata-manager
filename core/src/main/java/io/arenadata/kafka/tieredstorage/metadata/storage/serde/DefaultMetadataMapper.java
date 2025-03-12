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

import java.util.Map;

import org.apache.kafka.server.log.remote.storage.RemoteLogMetadata;

import io.arenadata.kafka.tieredstorage.metadata.storage.RemoteLogLeaderEpochState;

public class DefaultMetadataMapper implements MetadataMapper {

    private Serializer<RemoteLogMetadata> metadataSerializer;

    private Serializer<RemoteLogLeaderEpochState> leaderEpochStateSerializer;

    @Override
    public void configure(final Map<String, ?> configs) {
        metadataSerializer = new KafkaDefaultLogMetadataSerializer();
        leaderEpochStateSerializer = new LeaderEpochMetadataSerializer();
    }

    @Override
    public byte[] serializeLogMetadata(final RemoteLogMetadata remoteLogMetadata) {
        return metadataSerializer.serialize(remoteLogMetadata);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends RemoteLogMetadata> T deserializeLogMetadata(final byte[] bytes) {
        return (T) metadataSerializer.deserialize(bytes);
    }

    @Override
    public byte[] serializeLeaderEpochMetadata(final RemoteLogLeaderEpochState topicPartitionMetadata) {
        return leaderEpochStateSerializer.serialize(topicPartitionMetadata);
    }

    @Override
    public RemoteLogLeaderEpochState deserializeLeaderEpochMetadata(final byte[] bytes) {
        return leaderEpochStateSerializer.deserialize(bytes);
    }
}
