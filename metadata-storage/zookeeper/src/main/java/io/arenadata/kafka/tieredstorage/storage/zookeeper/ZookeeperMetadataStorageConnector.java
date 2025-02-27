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

package io.arenadata.kafka.tieredstorage.storage.zookeeper;

import org.apache.kafka.common.TopicIdPartition;

import io.arenadata.kafka.tieredstorage.metadata.MetadataKeyFactory;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackend;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageConnector;
import io.arenadata.kafka.tieredstorage.metadata.PathLikeMetadataKeyFactory;

public class ZookeeperMetadataStorageConnector implements MetadataStorageConnector {

    @Override
    public MetadataStorageBackend provideStorageBackend() {
        return new ZookeeperMetadataStorageBackend();
    }

    @Override
    public MetadataKeyFactory provideKeyFactory() {
        return new ZookeeperKeyFactory();
    }

    private static class ZookeeperKeyFactory extends PathLikeMetadataKeyFactory {

        // we can store metadata directly in partition metadata znode
        @Override
        public String partitionMetadataKey(final TopicIdPartition topicIdPartition) {
            return super.partitionMetadataDir(topicIdPartition);
        }
    }
}
