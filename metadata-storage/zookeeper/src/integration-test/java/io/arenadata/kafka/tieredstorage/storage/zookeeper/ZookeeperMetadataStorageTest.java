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

import java.util.Map;

import io.arenadata.kafka.tieredstorage.metadata.BaseMetadataStorageTest;
import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackend;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_HOSTS_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_ROOT_CONFIG;

@Testcontainers
public class ZookeeperMetadataStorageTest extends BaseMetadataStorageTest {

    private static final int ZOOKEEPER_PORT = 2181;
    private static final String BASE_ZOOKEEPER_PATH = "/kip405";

    private static CuratorFramework zookeeperClient;
    private ZookeeperMetadataStorageBackend metadataStorage;

    @Container
    static final GenericContainer<?> ZOOKEEPER_CONTAINER = new GenericContainer<>("zookeeper:3.8.3")
        .withExposedPorts(ZOOKEEPER_PORT);

    @BeforeAll
    static void setUp() {
        zookeeperClient = CuratorFrameworkFactory
            .newClient(getZookeeperHost(), new RetryNTimes(3, 2500));
        zookeeperClient.start();
    }

    @BeforeEach
    void setUpStorage() {
        final Map<String, String> config = Map.of(
            ZOOKEEPER_HOSTS_CONFIG, getZookeeperHost(),
            ZOOKEEPER_ROOT_CONFIG, BASE_ZOOKEEPER_PATH
        );

        metadataStorage = new ZookeeperMetadataStorageBackend();
        metadataStorage.configure(config);
    }

    @AfterEach
    void clear() throws Exception {
        zookeeperClient.delete()
            .deletingChildrenIfNeeded()
            .forPath(BASE_ZOOKEEPER_PATH);
    }

    @Override
    protected MetadataStorageBackend metadataStorage() {
        return metadataStorage;
    }

    private static String getZookeeperHost() {
        return ZOOKEEPER_CONTAINER.getHost() + ":" + ZOOKEEPER_CONTAINER.getMappedPort(ZOOKEEPER_PORT);
    }
}
