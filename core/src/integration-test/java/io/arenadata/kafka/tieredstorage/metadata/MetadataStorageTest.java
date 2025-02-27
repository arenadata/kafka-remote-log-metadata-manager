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

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadataUpdate;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentState;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteMetadata;
import org.apache.kafka.server.log.remote.storage.RemotePartitionDeleteState;

import io.arenadata.kafka.tieredstorage.metadata.filesystem.FileSystemMetadataStorageConnector;
import io.arenadata.kafka.tieredstorage.metadata.storage.RemoteLogMetadataManager;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.arenadata.kafka.tieredstorage.metadata.filesystem.FileSystemMetadataStorageBackend.FS_METADATA_STORAGE_ROOT_CONFIG;

public class MetadataStorageTest {
    private static final int ASYNC_OPERATION_TIMEOUT_SEC = 5;

    private static final TopicIdPartition T0P0 = new TopicIdPartition(Uuid.randomUuid(),
        new TopicPartition("foo", 0));

    private static final TopicIdPartition T1P0 = new TopicIdPartition(Uuid.randomUuid(),
        new TopicPartition("foo", 0));

    private static final int SEG_SIZE = 1024 * 1024;
    private static final int BROKER_ID_0 = 0;
    private static final int BROKER_ID_1 = 1;

    private final Time time = new MockTime(1);

    private RemoteLogMetadataManager remoteLogMetadataManager;

    @TempDir
    private Path tmpDir;

    @BeforeEach
    public void setUp() {
        remoteLogMetadataManager = new RemoteLogMetadataManager();
        final Map<String, ?> config = Map.of(
            "metadata.storage.connector.class", FileSystemMetadataStorageConnector.class.getName(),
            "metadata.storage." + FS_METADATA_STORAGE_ROOT_CONFIG, tmpDir.resolve("metadata").toString()
        );
        remoteLogMetadataManager.configure(config);
    }

    @Test
    public void testFetchSegments() throws Exception {
        try {
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.singleton(T0P0), Collections.emptySet());

            // 1.Create a segment with state COPY_SEGMENT_STARTED, and this segment should not be available.
            final Map<Integer, Long> segmentLeaderEpochs = Collections.singletonMap(0, 101L);
            final RemoteLogSegmentId segmentId = new RemoteLogSegmentId(T0P0, Uuid.randomUuid());
            final RemoteLogSegmentMetadata segmentMetadata =
                new RemoteLogSegmentMetadata(segmentId, 101L, 200L, -1L, BROKER_ID_0,
                    time.milliseconds(), SEG_SIZE, segmentLeaderEpochs);
            // Wait until the segment is added successfully.
            waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(segmentMetadata));

            // Search should not return the above segment.
            Assertions.assertFalse(remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 0, 150).isPresent());

            // 2.Move that segment to COPY_SEGMENT_FINISHED state and this segment should be available.
            final RemoteLogSegmentMetadataUpdate segmentMetadataUpdate =
                new RemoteLogSegmentMetadataUpdate(segmentId, time.milliseconds(),
                    Optional.empty(),
                    RemoteLogSegmentState.COPY_SEGMENT_FINISHED,
                    BROKER_ID_1);
            // Wait until the segment is updated successfully.
            waitForResult(remoteLogMetadataManager.updateRemoteLogSegmentMetadata(segmentMetadataUpdate));
            final RemoteLogSegmentMetadata expectedSegmentMetadata =
                segmentMetadata.createWithUpdates(segmentMetadataUpdate);

            // Search should return the above segment.
            final Optional<RemoteLogSegmentMetadata> segmentMetadataForOffset150 =
                remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 0, 150);
            Assertions.assertEquals(Optional.of(expectedSegmentMetadata), segmentMetadataForOffset150);
        } finally {
            Utils.closeQuietly(remoteLogMetadataManager, "RemoteLogMetadataManager");
        }
    }

    @Test
    public void testFetchFollowerSegments() throws Exception {
        try {
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.singleton(T0P0), Collections.emptySet());

            final Map<Integer, Long> segmentLeaderEpochs = Collections.singletonMap(0, 101L);
            final RemoteLogSegmentId segmentId = new RemoteLogSegmentId(T0P0, Uuid.randomUuid());
            final RemoteLogSegmentMetadata segmentMetadata =
                new RemoteLogSegmentMetadata(segmentId, 101L, 200L, -1L, BROKER_ID_0,
                    time.milliseconds(), SEG_SIZE, segmentLeaderEpochs);
            // write metadata of leader partition and log segment
            waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(segmentMetadata));

            final RemoteLogSegmentMetadataUpdate segmentMetadataUpdate =
                new RemoteLogSegmentMetadataUpdate(segmentId, time.milliseconds(),
                    Optional.empty(),
                    RemoteLogSegmentState.COPY_SEGMENT_FINISHED,
                    BROKER_ID_1);
            waitForResult(remoteLogMetadataManager.updateRemoteLogSegmentMetadata(segmentMetadataUpdate));

            // remove leadership of partition TP0
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.emptySet(), Collections.singleton(T0P0));

            // read metadata of follower partition and log segment
            final Optional<RemoteLogSegmentMetadata> segmentMetadataForOffset150 =
                remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 0, 150);

            final RemoteLogSegmentMetadata expectedSegmentMetadata =
                segmentMetadata.createWithUpdates(segmentMetadataUpdate);
            Assertions.assertEquals(Optional.of(expectedSegmentMetadata), segmentMetadataForOffset150);
        } finally {
            Utils.closeQuietly(remoteLogMetadataManager, "RemoteLogMetadataManager");
        }
    }

    @Test
    public void testFetchNonExistentSegmentMetadata() throws Exception {
        try {
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.singleton(T0P0), Collections.emptySet());

            final Optional<RemoteLogSegmentMetadata> fetchResult =
                remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 1, 1);
            Assertions.assertTrue(fetchResult.isEmpty());
        } finally {
            Utils.closeQuietly(remoteLogMetadataManager, "RemoteLogMetadataManager");
        }
    }

    @Test
    public void testFetchFromNonAssignedPartition() {
        try {
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.singleton(T0P0), Collections.emptySet());

            Assertions.assertThrows(MetadataNotFoundException.class,
                () -> remoteLogMetadataManager.remoteLogSegmentMetadata(T1P0, 1, 1));
        } finally {
            Utils.closeQuietly(remoteLogMetadataManager, "RemoteLogMetadataManager");
        }
    }

    @Test
    public void testFetchHighestOffsetForEpoch() throws Exception {
        try {
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.singleton(T0P0), Collections.emptySet());

            // check leader partition
            final Map<Integer, Long> segmentLeaderEpochs = new HashMap<>();
            segmentLeaderEpochs.put(1, 20L);
            segmentLeaderEpochs.put(2, 50L);
            final RemoteLogSegmentId segmentId = new RemoteLogSegmentId(T0P0, Uuid.randomUuid());
            final RemoteLogSegmentMetadata segmentMetadata = new RemoteLogSegmentMetadata(segmentId, 0L, 70L,
                -1L, BROKER_ID_0, time.milliseconds(), SEG_SIZE, segmentLeaderEpochs);

            waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(segmentMetadata));
            waitForResult(remoteLogMetadataManager.updateRemoteLogSegmentMetadata(
                copyFinishedMetadataUpdate(segmentId)));

            final Map<Integer, Long> secondSegmentLeaderEpochs = new HashMap<>();
            secondSegmentLeaderEpochs.put(2, 80L);
            secondSegmentLeaderEpochs.put(3, 100L);
            final RemoteLogSegmentId secondSegmentId = new RemoteLogSegmentId(T0P0, Uuid.randomUuid());
            final RemoteLogSegmentMetadata secondSegmentMetadata = new RemoteLogSegmentMetadata(secondSegmentId,
                0L, 150L, -1L, BROKER_ID_0, time.milliseconds(), SEG_SIZE, secondSegmentLeaderEpochs);

            waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(secondSegmentMetadata));
            waitForResult(remoteLogMetadataManager.updateRemoteLogSegmentMetadata(
                copyFinishedMetadataUpdate(secondSegmentId)));

            // check follower partition
            Assertions.assertEquals(Optional.of(49L), remoteLogMetadataManager.highestOffsetForEpoch(T0P0, 1));
            Assertions.assertEquals(Optional.of(99L), remoteLogMetadataManager.highestOffsetForEpoch(T0P0, 2));
            Assertions.assertEquals(Optional.of(150L), remoteLogMetadataManager.highestOffsetForEpoch(T0P0, 3));

            // remove leadership of partition T0P0
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.emptySet(), Collections.singleton(T0P0));

            // check follower partition
            Assertions.assertEquals(Optional.of(49L), remoteLogMetadataManager.highestOffsetForEpoch(T0P0, 1));
            Assertions.assertEquals(Optional.of(99L), remoteLogMetadataManager.highestOffsetForEpoch(T0P0, 2));
            Assertions.assertEquals(Optional.of(150L), remoteLogMetadataManager.highestOffsetForEpoch(T0P0, 3));
        } finally {
            Utils.closeQuietly(remoteLogMetadataManager, "RemoteLogMetadataManager");
        }
    }

    private RemoteLogSegmentMetadataUpdate copyFinishedMetadataUpdate(final RemoteLogSegmentId segmentId) {
        return new RemoteLogSegmentMetadataUpdate(segmentId, time.milliseconds(),
            Optional.empty(), RemoteLogSegmentState.COPY_SEGMENT_FINISHED, BROKER_ID_1);
    }

    @Test
    public void testRemotePartitionDeletion() throws Exception {
        try {
            remoteLogMetadataManager.onPartitionLeadershipChanges(Collections.singleton(T0P0), Collections.emptySet());

            // Create remote log segment metadata and add them to RLMM.

            // segment 0
            // offsets: [0-100]
            // leader epochs (0,0), (1,20), (2,50), (3, 80)
            final Map<Integer, Long> segmentLeaderEpochs = new HashMap<>();
            segmentLeaderEpochs.put(0, 0L);
            segmentLeaderEpochs.put(1, 20L);
            segmentLeaderEpochs.put(2, 50L);
            segmentLeaderEpochs.put(3, 80L);
            final RemoteLogSegmentId segmentId = new RemoteLogSegmentId(T0P0, Uuid.randomUuid());
            final RemoteLogSegmentMetadata segmentMetadata = new RemoteLogSegmentMetadata(segmentId, 0L, 100L,
                -1L, BROKER_ID_0, time.milliseconds(), SEG_SIZE, segmentLeaderEpochs);
            // Wait until the segment is added successfully.
            waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(segmentMetadata));

            final RemoteLogSegmentMetadataUpdate segmentMetadataUpdate = new RemoteLogSegmentMetadataUpdate(segmentId,
                time.milliseconds(), Optional.empty(), RemoteLogSegmentState.COPY_SEGMENT_FINISHED, BROKER_ID_1);
            // Wait until the segment is updated successfully.
            waitForResult(remoteLogMetadataManager.updateRemoteLogSegmentMetadata(segmentMetadataUpdate));

            final RemoteLogSegmentMetadata expectedSegMetadata =
                segmentMetadata.createWithUpdates(segmentMetadataUpdate);

            // Check that the segment exists in RLMM.
            final Optional<RemoteLogSegmentMetadata> segMetadataForOffset30Epoch1 =
                remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 1, 30L);
            Assertions.assertEquals(Optional.of(expectedSegMetadata), segMetadataForOffset30Epoch1);

            // Mark the partition for deletion and wait for it to be updated successfully.
            waitForResult(remoteLogMetadataManager.putRemotePartitionDeleteMetadata(
                createRemotePartitionDeleteMetadata(RemotePartitionDeleteState.DELETE_PARTITION_MARKED)));

            final Optional<RemoteLogSegmentMetadata> segmentMetadataAfterDelMark =
                remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 1, 30L);
            Assertions.assertEquals(Optional.of(expectedSegMetadata), segmentMetadataAfterDelMark);

            // Set the partition deletion state as started. Partition and segments should still be accessible
            // as they are not yet deleted. Wait until the segment state is updated successfully.
            waitForResult(remoteLogMetadataManager.putRemotePartitionDeleteMetadata(
                createRemotePartitionDeleteMetadata(RemotePartitionDeleteState.DELETE_PARTITION_STARTED)));

            final Optional<RemoteLogSegmentMetadata> segmentMetadataAfterDelStart =
                remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 1, 30L);
            Assertions.assertEquals(Optional.of(expectedSegMetadata), segmentMetadataAfterDelStart);

            // Set the partition deletion state as finished. RLMM should clear all its internal
            // state for that partition. Wait until the segment state is updated successfully.
            waitForResult(remoteLogMetadataManager.putRemotePartitionDeleteMetadata(
                createRemotePartitionDeleteMetadata(RemotePartitionDeleteState.DELETE_PARTITION_FINISHED)));

            Assertions.assertThrows(MetadataNotFoundException.class,
                () -> remoteLogMetadataManager.remoteLogSegmentMetadata(T0P0, 1, 30L));
        } finally {
            Utils.closeQuietly(remoteLogMetadataManager, "RemoteLogMetadataManager");
        }
    }

    @Test
    public void testRemoteLogSizeCalculationWithSegmentsOfTheSameEpoch() throws Exception {
        final RemoteLogSegmentMetadata firstSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 0, 100,
                -1L, 0, time.milliseconds(), SEG_SIZE, Collections.singletonMap(0, 0L));
        final RemoteLogSegmentMetadata secondSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 100, 200,
                -1L, 0, time.milliseconds(), SEG_SIZE * 2, Collections.singletonMap(0, 0L));
        final RemoteLogSegmentMetadata thirdSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 200, 300,
                -1L, 0, time.milliseconds(), SEG_SIZE * 3, Collections.singletonMap(0, 0L));

        waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(firstSegmentMetadata));
        waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(secondSegmentMetadata));
        waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(thirdSegmentMetadata));

        final Long remoteLogSize = remoteLogMetadataManager.remoteLogSize(T0P0, 0);

        Assertions.assertEquals(SEG_SIZE * 6, remoteLogSize);
    }

    @Test
    public void testRemoteLogSizeCalculationWithSegmentsOfDifferentEpochs() throws Exception {
        final RemoteLogSegmentMetadata firstSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 0, 100,
                -1L, 0, time.milliseconds(), SEG_SIZE, Collections.singletonMap(0, 0L));
        final RemoteLogSegmentMetadata secondSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 100, 200,
                -1L, 0, time.milliseconds(), SEG_SIZE * 2, Collections.singletonMap(1, 100L));
        final RemoteLogSegmentMetadata thirdSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 200, 300,
                -1L, 0, time.milliseconds(), SEG_SIZE * 3, Collections.singletonMap(2, 200L));

        waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(firstSegmentMetadata));
        waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(secondSegmentMetadata));
        waitForResult(remoteLogMetadataManager.addRemoteLogSegmentMetadata(thirdSegmentMetadata));

        Assertions.assertEquals(SEG_SIZE, remoteLogMetadataManager.remoteLogSize(T0P0, 0));
        Assertions.assertEquals(SEG_SIZE * 2, remoteLogMetadataManager.remoteLogSize(T0P0, 1));
        Assertions.assertEquals(SEG_SIZE * 3, remoteLogMetadataManager.remoteLogSize(T0P0, 2));
    }

    @Test
    public void testRemoteLogSizeCalculationWithSegmentsHavingNonExistentEpochs() throws Exception {
        final RemoteLogSegmentMetadata firstSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 0, 100,
                -1L, 0, time.milliseconds(), SEG_SIZE, Collections.singletonMap(0, 0L));
        final RemoteLogSegmentMetadata secondSegmentMetadata =
            new RemoteLogSegmentMetadata(new RemoteLogSegmentId(T0P0, Uuid.randomUuid()), 100, 200,
                -1L, 0, time.milliseconds(), SEG_SIZE * 2, Collections.singletonMap(1, 100L));

        remoteLogMetadataManager.addRemoteLogSegmentMetadata(firstSegmentMetadata);
        remoteLogMetadataManager.addRemoteLogSegmentMetadata(secondSegmentMetadata);

        Assertions.assertEquals(0, remoteLogMetadataManager.remoteLogSize(T0P0, 9001));
    }

    private RemotePartitionDeleteMetadata createRemotePartitionDeleteMetadata(final RemotePartitionDeleteState state) {
        return new RemotePartitionDeleteMetadata(T0P0, state, time.milliseconds(), BROKER_ID_0);
    }

    private <V> V waitForResult(final CompletableFuture<V> result) throws Exception {
        return result
            .get(ASYNC_OPERATION_TIMEOUT_SEC, TimeUnit.SECONDS);
    }
}
