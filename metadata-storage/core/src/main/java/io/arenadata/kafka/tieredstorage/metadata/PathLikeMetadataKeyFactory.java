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

import java.util.Map;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;

public class PathLikeMetadataKeyFactory implements MetadataKeyFactory {

    protected String basePath = "/";

    @Override
    public String partitionKey(final TopicIdPartition topicIdPartition) {
        return basePath + topicIdPartition.topic()
            + "-" + topicIdPartition.topicId()
            + "/" + topicIdPartition.partition();
    }

    @Override
    public String segmentMetadataKey(final TopicIdPartition topicIdPartition,
                                     final RemoteLogSegmentId segmentId) {
        return partitionKey(topicIdPartition) + "/" + segmentId.id();
    }

    @Override
    public String partitionMetadataKey(final TopicIdPartition topicIdPartition) {
        return partitionMetadataDir(topicIdPartition) + "/metadata";
    }

    @Override
    public String leaderEpochMetadataKey(final TopicIdPartition topicIdPartition,
                                         final int leaderEpoch) {
        return partitionMetadataDir(topicIdPartition) + "/epoch-" + leaderEpoch;
    }

    protected String partitionMetadataDir(final TopicIdPartition topicIdPartition) {
        return partitionKey(topicIdPartition) + "-metadata";
    }

    @Override
    public void configure(final Map<String, ?> configs) {

    }
}
