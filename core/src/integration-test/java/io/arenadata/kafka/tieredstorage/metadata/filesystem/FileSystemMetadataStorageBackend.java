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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.common.KafkaException;

import io.arenadata.kafka.tieredstorage.metadata.MetadataNotFoundException;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackend;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackendException;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageOperation;
import io.arenadata.kafka.tieredstorage.metadata.storage.RemoteLogMetadataManager;

/** Pseudo-async {@link MetadataStorageBackend} implementation used for
 * {@link RemoteLogMetadataManager
 * RemoteLogMetadataManager} testing */
public class FileSystemMetadataStorageBackend implements MetadataStorageBackend {

    public static final String FS_METADATA_STORAGE_ROOT_CONFIG = "filesystem.root";

    private Path fsRoot;

    @Override
    public void configure(final Map<String, ?> configs) {
        fsRoot = Path.of(configs.get(FS_METADATA_STORAGE_ROOT_CONFIG).toString());
    }

    @Override
    public CompletableFuture<Void> addValue(final String key, final byte[] value)
        throws MetadataStorageBackendException {
        try {
            Files.createDirectories(fsRoot.resolve(key).getParent());
            return updateValue(key, value);
        } catch (final IOException e) {
            throw new MetadataStorageBackendException(e);
        }
    }

    @Override
    public byte[] getValue(final String key) {
        try {
            return Files.readAllBytes(fsRoot.resolve(key));
        } catch (final NoSuchFileException exception) {
            throw new MetadataNotFoundException("Metadata not found", exception);
        } catch (final IOException e) {
            throw new MetadataStorageBackendException(e);
        }
    }

    @Override
    public CompletableFuture<Void> updateValue(final String key, final byte[] value) {
        final Path filePath = fsRoot.resolve(key);
        try {
            Files.write(filePath, value);
            return CompletableFuture.completedFuture(null);
        } catch (final NoSuchFileException exception) {
            throw new MetadataNotFoundException("Metadata not found", exception);
        } catch (final IOException e) {
            throw new MetadataStorageBackendException(e);
        }
    }

    @Override
    public Stream<byte[]> getValuesWithKeyLike(final String key) {
        try (final Stream<Path> fileStream = Files.walk(fsRoot.resolve(key))) {
            final List<byte[]> filesData = fileStream
                .filter(Files::isRegularFile)
                .map(this::readAllBytesUnchecked)
                .collect(Collectors.toList());

            // we need to close fs stream, so we create new one
            return filesData.stream();
        } catch (final NoSuchFileException exception) {
            throw new MetadataNotFoundException("Metadata not found", exception);
        } catch (final IOException e) {
            throw new MetadataStorageBackendException(e);
        }
    }

    @Override
    public CompletableFuture<Void> executeAtomically(final List<MetadataStorageOperation> operations)
        throws MetadataStorageBackendException {
        return CompletableFuture.allOf(
            operations.stream()
                .map(this::execute)
                .toArray(CompletableFuture[]::new)
        );
    }

    @Override
    public CompletableFuture<Void> removeValuesWithKeyLike(final String key) {
        try (Stream<Path> walk = Files.walk(fsRoot.resolve(key))) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);

            return CompletableFuture.completedFuture(null);
        } catch (final NoSuchFileException exception) {
            throw new MetadataNotFoundException("Metadata not found", exception);
        } catch (final IOException e) {
            throw new MetadataStorageBackendException(e);
        }
    }

    private CompletableFuture<Void> execute(final MetadataStorageOperation operation) {
        return operation.getType() == MetadataStorageOperation.Type.UPDATE
            ? updateValue(operation.getKey(), operation.getData())
            : addValue(operation.getKey(), operation.getData());
    }

    private byte[] readAllBytesUnchecked(final Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (final IOException e) {
            throw new KafkaException(e);
        }
    }

    @Override
    public void close() {
    }
}
