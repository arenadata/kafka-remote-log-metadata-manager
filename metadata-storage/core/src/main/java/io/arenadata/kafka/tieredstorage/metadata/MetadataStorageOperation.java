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

public class MetadataStorageOperation {
    public enum Type {
        ADD,
        UPDATE
    }

    final String key;

    final byte[] data;

    final Type type;

    public MetadataStorageOperation(final String key, final byte[] data, final Type type) {
        this.key = key;
        this.data = data;
        this.type = type;
    }

    public String getKey() {
        return key;
    }

    public byte[] getData() {
        return data;
    }

    public Type getType() {
        return type;
    }

    public static MetadataStorageOperation add(final String key, final byte[] data) {
        return new MetadataStorageOperation(key, data, Type.ADD);
    }

    public static MetadataStorageOperation update(final String key, final byte[] data) {
        return new MetadataStorageOperation(key, data, Type.UPDATE);
    }
}
