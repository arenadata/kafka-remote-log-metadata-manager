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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class BaseMetadataStorageTest {

    protected static final String TEST_DATA_KEY_GROUP = "/some_key_group";

    protected static final String TEST_DATA_KEY_1 = TEST_DATA_KEY_GROUP + "/abcd_key";
    protected static final String TEST_DATA_KEY_2 = TEST_DATA_KEY_GROUP + "/2_key";
    protected static final String TEST_DATA_KEY_3 = TEST_DATA_KEY_GROUP + "/third";

    protected static final String ANOTHER_GROUP_KEY = "/another_group/key";

    protected static final List<String> TEST_GROUP_DATA_KEYS = List.of(
        TEST_DATA_KEY_1,
        TEST_DATA_KEY_2,
        TEST_DATA_KEY_3
    );

    protected static final int ASYNC_OPERATION_TIMEOUT_SEC = 5;

    protected abstract MetadataStorageBackend metadataStorage();

    @Test
    public void testAdd() throws Exception {
        final byte[] data = "some value".getBytes();
        metadataStorage().addValue(TEST_DATA_KEY_1, data)
            .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);

        assertValue(TEST_DATA_KEY_1, data);
    }

    @Test
    public void testUpdate() throws Exception {
        final byte[] firstVersionData = "some value".getBytes();
        metadataStorage().addValue(TEST_DATA_KEY_1, firstVersionData)
            .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);

        assertValue(TEST_DATA_KEY_1, firstVersionData);

        final byte[] secondVersionData = "another val".getBytes();
        metadataStorage().updateValue(TEST_DATA_KEY_1, secondVersionData)
            .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);

        assertValue(TEST_DATA_KEY_1, secondVersionData);

        final byte[] thirdVersionData = "third version".getBytes();
        metadataStorage().updateValue(TEST_DATA_KEY_1, thirdVersionData)
            .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);

        assertValue(TEST_DATA_KEY_1, thirdVersionData);
    }

    @Test
    public void testExecuteAtomically() throws Exception {
        final List<MetadataStorageOperation> operations = TEST_GROUP_DATA_KEYS.stream()
            .map(key -> MetadataStorageOperation.add(key, valueForAddedKey(key)))
            .collect(Collectors.toList());

        Stream.of(TEST_DATA_KEY_1, TEST_DATA_KEY_2)
            .map(key -> MetadataStorageOperation.update(key, valueForUpdatedKey(key)))
            .forEach(operations::add);

        metadataStorage().executeAtomically(operations)
            .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);

        assertValue(TEST_DATA_KEY_1, valueForUpdatedKey(TEST_DATA_KEY_1));
        assertValue(TEST_DATA_KEY_2, valueForUpdatedKey(TEST_DATA_KEY_2));
        assertValue(TEST_DATA_KEY_3, valueForAddedKey(TEST_DATA_KEY_3));
    }

    @Test
    public void testGetValuesWithKeyLike() throws Exception {
        final Set<byte[]> expectedGroupValues = uploadTestGroupValues();
        metadataStorage().addValue(ANOTHER_GROUP_KEY, "12345".getBytes());

        final List<byte[]> actualGroupValues = metadataStorage().getValuesWithKeyLike(TEST_DATA_KEY_GROUP)
            .collect(Collectors.toList());

        assertThat(actualGroupValues)
            .containsExactlyInAnyOrderElementsOf(expectedGroupValues);
    }

    @Test
    public void testGetValuesWithKeyIn() throws Exception {
        final Set<byte[]> expectedGroupValues = uploadTestGroupValues();
        metadataStorage().addValue(ANOTHER_GROUP_KEY, "12345".getBytes());

        final List<byte[]> actualGroupValues = metadataStorage().getValuesWithKeyIn(TEST_GROUP_DATA_KEYS)
            .collect(Collectors.toList());

        assertThat(actualGroupValues)
            .containsExactlyInAnyOrderElementsOf(expectedGroupValues);
    }

    @Test
    public void testRemoveValuesWithKeyLike() throws Exception {
        uploadTestGroupValues();

        final byte[] anotherGroupKeyValue = "12345".getBytes();
        metadataStorage().addValue(ANOTHER_GROUP_KEY, anotherGroupKeyValue);

        metadataStorage().removeValuesWithKeyLike(TEST_DATA_KEY_GROUP)
            .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);

        TEST_GROUP_DATA_KEYS.forEach(key ->
            assertThatThrownBy(() -> metadataStorage().getValue(key))
                .isInstanceOf(MetadataNotFoundException.class));

        assertValue(ANOTHER_GROUP_KEY, anotherGroupKeyValue);
    }

    private byte[] valueForAddedKey(final String key) {
        return (key + "_added").getBytes();
    }

    private byte[] valueForUpdatedKey(final String key) {
        return (key + "_updated").getBytes();
    }

    private Set<byte[]> uploadTestGroupValues() throws Exception {
        final Set<byte[]> expectedGroupValues = new HashSet<>();

        for (final String key : TEST_GROUP_DATA_KEYS) {
            final byte[] value = ("value for " + key).getBytes();
            metadataStorage().addValue(key, value)
                .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);
            expectedGroupValues.add(value);
        }
        return expectedGroupValues;
    }

    private void assertValue(final String key, final byte[] expectedValue) {
        final byte[] actualData = metadataStorage().getValue(key);
        assertThat(actualData).isEqualTo(expectedValue);
    }
}
