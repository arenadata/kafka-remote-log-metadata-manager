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

package io.arenadata.kafka.tieredstorage.metadata.filesystem;

import java.util.Map;

import io.arenadata.kafka.tieredstorage.metadata.MetadataKeyFactory;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackend;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageConnector;
import io.arenadata.kafka.tieredstorage.metadata.PathLikeMetadataKeyFactory;

import static io.arenadata.kafka.tieredstorage.metadata.filesystem.FileSystemMetadataStorageBackend.FS_METADATA_STORAGE_ROOT_CONFIG;

/** Pseudo-async fs metadata storage for testing purposes */
public class FileSystemMetadataStorageConnector implements MetadataStorageConnector {
    @Override
    public MetadataStorageBackend provideStorageBackend() {
        return new FileSystemMetadataStorageBackend();
    }

    @Override
    public MetadataKeyFactory provideKeyFactory() {
        return new FileSystemPathMetadataKeyFactory();
    }

    private static class FileSystemPathMetadataKeyFactory extends PathLikeMetadataKeyFactory {
        @Override
        public void configure(final Map<String, ?> configs) {
            basePath = configs.get(FS_METADATA_STORAGE_ROOT_CONFIG).toString();
        }
    }
}
