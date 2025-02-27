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

import java.net.URL;
import java.util.Map;
import java.util.stream.Stream;

import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackendException;

import org.apache.curator.RetryPolicy;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.client.ZKClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_CLIENT_CONFIG_PATH_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_CONNECTION_TIMEOUT_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_HOSTS_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_RETRY_MAX_NUM_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_RETRY_POLICY_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_RETRY_SLEEP_TIME_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_ROOT_CONFIG;
import static io.arenadata.kafka.tieredstorage.storage.zookeeper.ZookeeperMetadataStorageConfig.ZOOKEEPER_SESSION_TIMEOUT_CONFIG;
import static org.apache.zookeeper.client.ZKClientConfig.ZK_SASL_CLIENT_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

public class ZookeeperMetadataStorageConfigTest {

    static Stream<Arguments> zookeeperRootTestValues() {
        return Stream.of(
            Arguments.of("/root", "root"),
            Arguments.of("/root/", "root"),
            Arguments.of("root/", "root"),
            Arguments.of("root", "root"),
            Arguments.of("/root/subdir/", "root/subdir"),
            Arguments.of("root/subdir/", "root/subdir"),
            Arguments.of("/root/subdir", "root/subdir"),
            Arguments.of("root/subdir", "root/subdir"),
            Arguments.of("/", "")
        );
    }

    @Test
    void testMinimalConfig() {
        final ZookeeperMetadataStorageConfig config = new ZookeeperMetadataStorageConfig(Map.of(
            ZOOKEEPER_HOSTS_CONFIG, "host1:port1,host2:port2"
        ));

        assertThat(config.hosts()).isEqualTo("host1:port1,host2:port2");
    }

    @Test
    void testConfigWithExponentialRetryPolicy() {
        final ZookeeperMetadataStorageConfig config = new ZookeeperMetadataStorageConfig(Map.of(
            ZOOKEEPER_HOSTS_CONFIG, "host1:port1,host2:port2",
            ZOOKEEPER_RETRY_POLICY_CONFIG, ZookeeperMetadataStorageConfig.RetryType.EXPONENTIAL.toString(),
            ZOOKEEPER_RETRY_MAX_NUM_CONFIG, 10,
            ZOOKEEPER_RETRY_SLEEP_TIME_CONFIG, 1000
        ));

        final RetryPolicy retryPolicy = config.retryPolicy();
        assertThat(retryPolicy).isNotNull();
        assertThat(retryPolicy).isInstanceOf(ExponentialBackoffRetry.class);
    }

    @Test
    void testConfigWithNTimesRetryPolicy() {
        final ZookeeperMetadataStorageConfig config = new ZookeeperMetadataStorageConfig(Map.of(
            ZOOKEEPER_HOSTS_CONFIG, "host1:port1,host2:port2",
            ZOOKEEPER_RETRY_POLICY_CONFIG, ZookeeperMetadataStorageConfig.RetryType.N_TIMES.toString(),
            ZOOKEEPER_RETRY_MAX_NUM_CONFIG, 10,
            ZOOKEEPER_RETRY_SLEEP_TIME_CONFIG, 1000
        ));

        final RetryPolicy retryPolicy = config.retryPolicy();
        assertThat(retryPolicy).isNotNull();
        assertThat(retryPolicy).isInstanceOf(RetryNTimes.class);
    }

    @ParameterizedTest
    @MethodSource("zookeeperRootTestValues")
    void testTrimRootSlashes(final String root, final String expectedRoot) {
        final ZookeeperMetadataStorageConfig config = new ZookeeperMetadataStorageConfig(Map.of(
            ZOOKEEPER_HOSTS_CONFIG, "host1:port1,host2:port2",
            ZOOKEEPER_ROOT_CONFIG, root
        ));

        assertThat(config.root()).isEqualTo(expectedRoot);
    }

    @Test
    void testFullConfig() {
        final ZookeeperMetadataStorageConfig config = new ZookeeperMetadataStorageConfig(Map.of(
            ZOOKEEPER_HOSTS_CONFIG, "host1:port1,host2:port2",
            ZOOKEEPER_RETRY_POLICY_CONFIG, ZookeeperMetadataStorageConfig.RetryType.N_TIMES.toString(),
            ZOOKEEPER_RETRY_MAX_NUM_CONFIG, 10,
            ZOOKEEPER_RETRY_SLEEP_TIME_CONFIG, 1000,
            ZOOKEEPER_CLIENT_CONFIG_PATH_CONFIG, zookeeperPropertiesPath(),
            ZOOKEEPER_ROOT_CONFIG, "/test/",
            ZOOKEEPER_SESSION_TIMEOUT_CONFIG, 10,
            ZOOKEEPER_CONNECTION_TIMEOUT_CONFIG, 12
        ));

        final RetryPolicy retryPolicy = config.retryPolicy();
        assertThat(retryPolicy).isNotNull();
        assertThat(retryPolicy).isInstanceOf(RetryNTimes.class);

        final ZKClientConfig zkClientConfig = config.zkClientConfig();
        assertThat(zkClientConfig).isNotNull();
        assertThat(zkClientConfig.getProperty(ZK_SASL_CLIENT_USERNAME)).isEqualTo("test_user");
        assertThat(zkClientConfig.getProperty("my.key")).isEqualTo("my.value");

        assertThat(config.hosts()).isEqualTo("host1:port1,host2:port2");
        assertThat(config.root()).isEqualTo("test");
        assertThat(config.sessionTimeoutMs()).isEqualTo(10);
        assertThat(config.connectionTimeoutMs()).isEqualTo(12);
    }

    @Test
    void testThrowIfWrongZkClientConfigPathProvided() {
        assertThatThrownBy(() -> new ZookeeperMetadataStorageConfig(Map.of(
            ZOOKEEPER_HOSTS_CONFIG, "host1:port1,host2:port2",
            ZOOKEEPER_CLIENT_CONFIG_PATH_CONFIG, "/some/wrong/path.zookeeper"
        ))).isInstanceOf(MetadataStorageBackendException.class)
            .hasMessageContaining("Error reading zookeeper client config from path");
    }

    private String zookeeperPropertiesPath() {
        final URL resource = this.getClass().getClassLoader().getResource("zookeeper.properties");
        if (resource == null) {
            fail("Resource not found: zookeeper.properties");
        }
        return resource.getPath();
    }
}
