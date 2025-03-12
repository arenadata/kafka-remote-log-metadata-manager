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

package io.arenadata.kafka.tieredstorage.metadata;

import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;

public interface MetadataKeyFactory extends Configurable {
    String partitionKey(TopicIdPartition topicIdPartition);

    String segmentMetadataKey(TopicIdPartition topicIdPartition, RemoteLogSegmentId segmentId);

    String partitionMetadataKey(TopicIdPartition topicIdPartition);

    String leaderEpochMetadataKey(TopicIdPartition topicIdPartition, int leaderEpoch);
}
