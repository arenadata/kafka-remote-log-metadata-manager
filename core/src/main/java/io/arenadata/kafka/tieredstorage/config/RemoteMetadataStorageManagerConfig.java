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

package io.arenadata.kafka.tieredstorage.config;

import java.util.Map;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.utils.Utils;

import io.arenadata.kafka.tieredstorage.metadata.MetadataKeyFactory;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackend;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageConnector;
import io.arenadata.kafka.tieredstorage.metadata.storage.serde.DefaultMetadataMapper;
import io.arenadata.kafka.tieredstorage.metadata.storage.serde.MetadataMapper;

public class RemoteMetadataStorageManagerConfig extends AbstractConfig {
    private static final String STORAGE_PREFIX = "metadata.storage.";

    public static final String STORAGE_CONNECTOR_CLASS_CONFIG = STORAGE_PREFIX + "connector.class";
    private static final String STORAGE_CONNECTOR_CLASS_DOC = "The metadata storage connector implementation class";

    public static final String SERIALIZER_CLASS_CONFIG = STORAGE_PREFIX + "serializer.class";
    private static final String SERIALIZER_CLASS_DOC = "Metadata serializer implementation class";

    private static final ConfigDef CONFIG;

    static {
        CONFIG = new ConfigDef();

        CONFIG.define(
            STORAGE_CONNECTOR_CLASS_CONFIG,
            ConfigDef.Type.CLASS,
            ConfigDef.NO_DEFAULT_VALUE,
            ConfigDef.Importance.HIGH,
            STORAGE_CONNECTOR_CLASS_DOC
        ).define(
            SERIALIZER_CLASS_CONFIG,
            ConfigDef.Type.CLASS,
            DefaultMetadataMapper.class,
            ConfigDef.Importance.HIGH,
            SERIALIZER_CLASS_DOC
        );
    }

    private final MetadataStorageConnector metadataStorageConnector;

    public RemoteMetadataStorageManagerConfig(final Map<String, ?> props) {
        super(CONFIG, props);
        metadataStorageConnector = metadataStorageConnector();
    }

    private MetadataStorageConnector metadataStorageConnector() {
        final Class<?> storageClass = getClass(STORAGE_CONNECTOR_CLASS_CONFIG);
        return Utils.newInstance(storageClass, MetadataStorageConnector.class);
    }

    public MetadataStorageBackend metadataStorageBackend() {
        final MetadataStorageBackend storageBackend = metadataStorageConnector.provideStorageBackend();
        storageBackend.configure(this.originalsWithPrefix(STORAGE_PREFIX));
        return storageBackend;
    }

    public MetadataKeyFactory metadataKeyFactory() {
        final MetadataKeyFactory keyFactory = metadataStorageConnector.provideKeyFactory();
        keyFactory.configure(this.originalsWithPrefix(STORAGE_PREFIX));
        return keyFactory;
    }

    public MetadataMapper remoteMetadataManagerSerDe() {
        final Class<?> serializerClass = getClass(SERIALIZER_CLASS_CONFIG);
        final MetadataMapper serializer = Utils.newInstance(
            serializerClass, MetadataMapper.class);
        serializer.configure(this.originalsWithPrefix(STORAGE_PREFIX));
        return serializer;
    }
}
