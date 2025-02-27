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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.apache.kafka.common.Configurable;

public interface MetadataStorageBackend extends Configurable, AutoCloseable {

    CompletableFuture<Void> addValue(final String key, final byte[] value) throws MetadataStorageBackendException;

    CompletableFuture<Void> updateValue(final String key, final byte[] value) throws MetadataStorageBackendException;

    byte[] getValue(final String key) throws MetadataStorageBackendException;

    CompletableFuture<Void> removeValuesWithKeyLike(final String key) throws MetadataStorageBackendException;

    Stream<byte[]> getValuesWithKeyLike(final String key) throws MetadataStorageBackendException;

    CompletableFuture<Void> executeAtomically(
        final List<MetadataStorageOperation> operations) throws MetadataStorageBackendException;

    default Stream<byte[]> getValuesWithKeyIn(
        final Collection<String> keys) throws MetadataStorageBackendException {
        return keys.stream().map(this::getValue);
    }
}
