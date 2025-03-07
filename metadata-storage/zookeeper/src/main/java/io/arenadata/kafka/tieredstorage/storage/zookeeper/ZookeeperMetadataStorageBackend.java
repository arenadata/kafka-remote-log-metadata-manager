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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.arenadata.kafka.tieredstorage.metadata.MetadataNotFoundException;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackend;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackendException;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageOperation;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorOp;
import org.apache.curator.utils.ZKPaths;
import org.apache.curator.x.async.AsyncCuratorFramework;
import org.apache.curator.x.async.AsyncWrappers;
import org.apache.curator.x.async.api.CreateOption;
import org.apache.curator.x.async.api.DeleteOption;
import org.apache.zookeeper.KeeperException;

public class ZookeeperMetadataStorageBackend implements MetadataStorageBackend {

    private static final Set<CreateOption> ZK_CREATE_OPTIONS = Set.of(
        CreateOption.createParentsIfNeeded,
        CreateOption.setDataIfExists
    );

    private AsyncCuratorFramework zookeeperAsyncClient;

    @Override
    public void configure(final Map<String, ?> configs) {
        final ZookeeperMetadataStorageConfig config = new ZookeeperMetadataStorageConfig(configs);
        final CuratorFramework zkClient = CuratorFrameworkFactory.builder()
            .retryPolicy(config.retryPolicy())
            .zkClientConfig(config.zkClientConfig())
            .connectString(config.hosts())
            .sessionTimeoutMs(config.sessionTimeoutMs())
            .connectionTimeoutMs(config.connectionTimeoutMs())
            .namespace(config.root())
            .build();
        zkClient.start();

        zookeeperAsyncClient = AsyncCuratorFramework.wrap(zkClient);
    }

    @Override
    public byte[] getValue(final String key) {
        try {
            return zookeeperAsyncClient.unwrap()
                .getData()
                .forPath(key);
        } catch (final KeeperException.NoNodeException noNodeException) {
            throw new MetadataNotFoundException("Node not found for key: " + key);
        } catch (final Exception exc) {
            throw new MetadataStorageBackendException("Error fetching for key: " + key, exc);
        }
    }

    @Override
    public CompletableFuture<Void> addValue(final String key, final byte[] value)
        throws MetadataStorageBackendException {
        return zookeeperAsyncClient.create()
            .withOptions(ZK_CREATE_OPTIONS)
            .forPath(key, value)
            .exceptionally(exc -> fail(exc, "Error creating value for key: " + key))
            .thenAccept(ignore -> { })
            .toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> updateValue(final String key, final byte[] value) {
        return zookeeperAsyncClient.setData()
            .forPath(key, value)
            .exceptionally(exc -> fail(exc, "Error updating value for key: " + key))
            .thenAccept(ignore -> { })
            .toCompletableFuture();
    }

    @Override
    public CompletableFuture<Void> removeValuesWithKeyLike(final String key) {
        return zookeeperAsyncClient.delete()
            .withOptions(
                Set.of(DeleteOption.quietly, DeleteOption.deletingChildrenIfNeeded, DeleteOption.guaranteed))
            .forPath(key)
            .exceptionally(exc -> fail(exc, "Error removing value for key: " + key))
            .toCompletableFuture();
    }

    @Override
    public Stream<byte[]> getValuesWithKeyLike(final String key) {
        try {
            return zookeeperAsyncClient.sync()
                .forPath(key)
                .thenCompose(ignore -> zookeeperAsyncClient.getChildren().forPath(key))
                .thenApply(children -> getNodesData(children, key))
                .exceptionally(exc -> fail(exc, "Error fetching values for key: " + key))
                .toCompletableFuture()
                .get();
        } catch (final Exception exception) {
            if (exception.getCause() instanceof MetadataStorageBackendException) {
                throw (MetadataStorageBackendException) exception.getCause();
            }
            throw new MetadataStorageBackendException("Error fetching values for key: " + key, exception);
        }
    }

    @Override
    public CompletableFuture<Void> executeAtomically(final List<MetadataStorageOperation> operations)
        throws MetadataStorageBackendException {
        if (operations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // we need to manually create parents if needed,
        // because curator transaction api doesn't contain corresponding option
        final Set<String> valuesToAddParents = operations.stream()
            .filter(op -> op.getType() == MetadataStorageOperation.Type.ADD)
            .map(op -> ZKPaths.getPathAndNode(op.getKey()).getPath())
            .collect(Collectors.toSet());

        return ensurePathsExist(valuesToAddParents).thenCompose(
            ignore -> runOperationsInTransaction(operations,
                "Error performing batch operation for keys" + getKeys(operations)));
    }

    @Override
    public void close() {
        zookeeperAsyncClient.unwrap().close();
    }

    private CompletableFuture<Void> ensurePathsExist(final Set<String> paths) {
        final CompletableFuture<?>[] futures = paths.stream()
            .map(path -> AsyncWrappers.asyncEnsureParents(zookeeperAsyncClient, path))
            .map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    private CompletableFuture<Void> runOperationsInTransaction(
        final List<MetadataStorageOperation> operations, final String errorMessage) {
        return zookeeperAsyncClient.transaction()
            .forOperations(createCuratorOperations(operations))
            .exceptionally(exc -> fail(exc, errorMessage))
            .thenAccept(ignore -> { })
            .toCompletableFuture();
    }

    private List<CuratorOp> createCuratorOperations(final List<MetadataStorageOperation> operations) {
        return operations
            .stream()
            .map(this::createCuratorOp)
            .collect(Collectors.toList());
    }

    private CuratorOp createCuratorOp(final MetadataStorageOperation operation) {
        return operation.getType() == MetadataStorageOperation.Type.UPDATE
            ? createUpdateTransactionOp(operation)
            : createAddTransactionOp(operation);
    }

    private CuratorOp createAddTransactionOp(final MetadataStorageOperation operation) {
        return zookeeperAsyncClient.transactionOp()
            .create()
            .forPath(operation.getKey(), operation.getData());
    }

    private CuratorOp createUpdateTransactionOp(final MetadataStorageOperation operation) {
        return zookeeperAsyncClient.transactionOp()
            .setData()
            .forPath(operation.getKey(), operation.getData());
    }

    private Stream<byte[]> getNodesData(final List<String> nodePaths, final String prefix) {
        return nodePaths.stream()
            .map(path -> getValue(ZKPaths.makePath(prefix, path)));
    }

    private <T> T fail(final Throwable exception, final String message) {
        final Throwable realException = exception instanceof CompletionException
            ? exception.getCause()
            : exception;

        if (realException instanceof KeeperException.NoNodeException) {
            throw new MetadataNotFoundException(message, realException);
        }

        throw new MetadataStorageBackendException(message, realException);
    }

    private String getKeys(final List<MetadataStorageOperation> operations) {
        return operations.stream()
            .map(MetadataStorageOperation::getKey)
            .collect(Collectors.joining(", "));
    }
}
