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

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;

import io.arenadata.kafka.tieredstorage.metadata.MetadataStorageBackendException;

import org.apache.curator.RetryPolicy;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.retry.RetryNTimes;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;

class ZookeeperMetadataStorageConfig extends AbstractConfig {

    enum RetryType {
        EXPONENTIAL,
        N_TIMES
    }

    private static final ConfigDef CONFIG;

    static final String ZOOKEEPER_HOSTS_CONFIG = "zookeeper.host";
    private static final String ZOOKEEPER_HOSTS_DOC =
        "Zookeeper connection string, a comma separated list of host:port pairs, "
            + "each corresponding to a ZooKeeper server";

    static final String ZOOKEEPER_ROOT_CONFIG = "zookeeper.root";
    private static final String ZOOKEEPER_ROOT_DEFAULT = "/";
    private static final String ZOOKEEPER_ROOT_DOC =
        "The base znode path relative to which all uploaded keys will be resolved";

    static final String ZOOKEEPER_CLIENT_CONFIG_PATH_CONFIG = "zookeeper.config.path";
    private static final String ZOOKEEPER_CLIENT_CONFIG_PATH_DOC =
        "Absolute path of zookeeper client configuration file";

    static final String ZOOKEEPER_SESSION_TIMEOUT_CONFIG = "zookeeper.session.timeout.ms";
    private static final int ZOOKEEPER_SESSION_TIMEOUT_DEFAULT = 60 * 1000;
    private static final String ZOOKEEPER_SESSION_TIMEOUT_DOC =
        "Zookeeper session timeout in milliseconds";

    static final String ZOOKEEPER_CONNECTION_TIMEOUT_CONFIG = "zookeeper.connection.timeout.ms";
    private static final int ZOOKEEPER_CONNECTION_TIMEOUT_DEFAULT = 15 * 1000;
    private static final String ZOOKEEPER_CONNECTION_TIMEOUT_DOC =
        "Zookeeper connection timeout in milliseconds";

    static final String ZOOKEEPER_RETRY_POLICY_CONFIG = "zookeeper.connection.retry.policy";
    private static final String ZOOKEEPER_RETRY_POLICY_DEFAULT = RetryType.EXPONENTIAL.toString();
    private static final String ZOOKEEPER_RETRY_POLICY_DOC =
        "Policy to use when retrying connections, available options are "
            + "EXPONENTIAL - retries a set number of times with increasing sleep time between retries; "
            + "N_TIMES - retries a max number of times. Default is EXPONENTIAL";

    static final String ZOOKEEPER_RETRY_MAX_NUM_CONFIG = "zookeeper.connection.retry.max";
    static final int ZOOKEEPER_RETRY_MAX_NUM_DEFAULT = 5;
    private static final String ZOOKEEPER_RETRY_MAX_NUM_DOC =
        "Max number of times to retry the connection";

    static final String ZOOKEEPER_RETRY_SLEEP_TIME_CONFIG = "zookeeper.connection.retry.sleep-time.ms";
    static final int ZOOKEEPER_RETRY_SLEEP_TIME_DEFAULT = 500;
    private static final String ZOOKEEPER_RETRY_SLEEP_TIME_DOC =
        "Amount of time to wait between connection retries. If EXPONENTIAL retry policy is used, "
            + "then this value will be used as initial sleep time";

    private final ZKClientConfig zkClientConfig;
    private final RetryPolicy retryPolicy;

    static {
        CONFIG = new ConfigDef()
            .define(
                ZOOKEEPER_HOSTS_CONFIG,
                ConfigDef.Type.STRING,
                ConfigDef.NO_DEFAULT_VALUE,
                new ConfigDef.NonEmptyString(),
                ConfigDef.Importance.HIGH,
                ZOOKEEPER_HOSTS_DOC
            )
            .define(
                ZOOKEEPER_ROOT_CONFIG,
                ConfigDef.Type.STRING,
                ZOOKEEPER_ROOT_DEFAULT,
                new ConfigDef.NonEmptyString(),
                ConfigDef.Importance.HIGH,
                ZOOKEEPER_ROOT_DOC
            ).define(
                ZOOKEEPER_CLIENT_CONFIG_PATH_CONFIG,
                ConfigDef.Type.STRING,
                null,
                new ConfigDef.NonEmptyString(),
                ConfigDef.Importance.MEDIUM,
                ZOOKEEPER_CLIENT_CONFIG_PATH_DOC
            )
            .define(
                ZOOKEEPER_SESSION_TIMEOUT_CONFIG,
                ConfigDef.Type.INT,
                ZOOKEEPER_SESSION_TIMEOUT_DEFAULT,
                ConfigDef.Range.atLeast(1),
                ConfigDef.Importance.MEDIUM,
                ZOOKEEPER_SESSION_TIMEOUT_DOC
            )
            .define(
                ZOOKEEPER_CONNECTION_TIMEOUT_CONFIG,
                ConfigDef.Type.INT,
                ZOOKEEPER_CONNECTION_TIMEOUT_DEFAULT,
                ConfigDef.Range.atLeast(1),
                ConfigDef.Importance.MEDIUM,
                ZOOKEEPER_CONNECTION_TIMEOUT_DOC
            )
            .define(
                ZOOKEEPER_RETRY_POLICY_CONFIG,
                ConfigDef.Type.STRING,
                ZOOKEEPER_RETRY_POLICY_DEFAULT,
                new ConfigDef.NonEmptyString(),
                ConfigDef.Importance.HIGH,
                ZOOKEEPER_RETRY_POLICY_DOC
            )
            .define(
                ZOOKEEPER_RETRY_MAX_NUM_CONFIG,
                ConfigDef.Type.INT,
                ZOOKEEPER_RETRY_MAX_NUM_DEFAULT,
                ConfigDef.Range.atLeast(0),
                ConfigDef.Importance.MEDIUM,
                ZOOKEEPER_RETRY_MAX_NUM_DOC
            )
            .define(
                ZOOKEEPER_RETRY_SLEEP_TIME_CONFIG,
                ConfigDef.Type.INT,
                ZOOKEEPER_RETRY_SLEEP_TIME_DEFAULT,
                ConfigDef.Range.atLeast(1),
                ConfigDef.Importance.MEDIUM,
                ZOOKEEPER_RETRY_SLEEP_TIME_DOC
            );
    }

    public ZookeeperMetadataStorageConfig(final Map<String, ?> props) {
        super(CONFIG, props);
        this.zkClientConfig = buildZkClientConfig();
        this.retryPolicy = buildRetryPolicy();
    }

    public ZKClientConfig zkClientConfig() {
        return zkClientConfig;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public String hosts() {
        return getString(ZOOKEEPER_HOSTS_CONFIG);
    }

    public String root() {
        final String root = getString(ZOOKEEPER_ROOT_CONFIG);

        int startPos = 0;
        int endPos = root.length();

        // remove leading /
        if (root.startsWith("/")) {
            ++startPos;
        }

        // remove last /
        if (root.length() != 1 && root.lastIndexOf("/") == root.length() - 1) {
            --endPos;
        }

        return root.substring(startPos, endPos);
    }

    public int sessionTimeoutMs() {
        return getInt(ZOOKEEPER_SESSION_TIMEOUT_CONFIG);
    }

    public int connectionTimeoutMs() {
        return getInt(ZOOKEEPER_CONNECTION_TIMEOUT_CONFIG);
    }

    private ZKClientConfig buildZkClientConfig() {
        final String configPath = getString(ZOOKEEPER_CLIENT_CONFIG_PATH_CONFIG);
        try {
            if (configPath == null) {
                return null;
            }
            return new ZKClientConfig(configPath);
        } catch (final QuorumPeerConfig.ConfigException exc) {
            throw new MetadataStorageBackendException(
                "Error reading zookeeper client config from path: " + configPath, exc);
        }
    }

    private RetryPolicy buildRetryPolicy() {
        try {
            final RetryType retryType = RetryType.valueOf(getString(ZOOKEEPER_RETRY_POLICY_CONFIG));

            final Integer maxRetries = getInt(ZOOKEEPER_RETRY_MAX_NUM_CONFIG);
            final Integer sleepTimeMs = getInt(ZOOKEEPER_RETRY_SLEEP_TIME_CONFIG);

            return retryType == RetryType.EXPONENTIAL
                ? new ExponentialBackoffRetry(sleepTimeMs, maxRetries)
                : new RetryNTimes(maxRetries, sleepTimeMs);
        } catch (final Exception exception) {
            throw new MetadataStorageBackendException("Error building retry policy", exception);
        }
    }
}
