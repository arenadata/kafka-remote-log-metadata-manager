# Remote Log Metadata Manager implementation

## Design

### Remote Log Metadata Manager

`RemoteLogMetadataManager` provides ability to store and fetch remote log segment metadata with strongly consistent semantics.
There is an inbuilt implementation backed by topic storage in the local cluster. This implementation, on the other way, 
lets to store metadata in remote systems, abstracted by pluggable `MetadataStorageBackend` implementations.

Current implementation handles log segment metadata depending on the partition it belongs to: 

- If current broker is the leader for this partition, then metadata manager atomically stores metadata and auxiliary information 
in memory along with saving it in an external system. Since in the entire cluster only one broker can be the leader 
for a particular partition, fetching metadata of a partition or log segments from this partition also can be done from 
the in-memory cache in strictly consistent way on current broker.
- Otherwise, if current broker is the follower for this partition, then only read operations can be performed 
for log segments from it. To fetch the particular log segment metadata by offset, at first the id of the remote 
log segment is obtained from the topic partition metadata, that is fetched from the remote storage,
and only then the log segment metadata itself is fetched from the remote storage.

### Partition metadata

Along with each log segment metadata add/update, `RemoteLogMetadataManager` saves some auxiliary information, needed for 
metadata retrieval by offset or leader epoch. This information is grouped by partitions, therefore, it was called partition metadata.

The structure of partition metadata:

| Field       | Type                                      | Description                                                                                                                                                            |
|-------------|-------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| deleteState | `RemotePartitionDeleteState`              | This fields indicates the deletion state of the remote topic partition. Can be one of DELETE_PARTITION_MARKED, DELETE_PARTITION_STARTED or DELETE_PARTITION_FINISHED.	 |
| epochStates | `Map<Integer, RemoteLogLeaderEpochState>` | Metadata for each leader epoch, containing in log segments of this partition.                                                                                          |

The structure of leader epoch metadata:

| Field                  | Type                            | Description                                                                                                                                                                                                                                       |
|------------------------|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| offsetToId             | `Map<Long, RemoteLogSegmentId>` | Contains offset to segment ids mapping with the segment state as COPY_SEGMENT_FINISHED.	                                                                                                                                                          |
| unreferencedSegmentIds | `Set<RemoteLogSegmentId>`       | Unreferenced segments for this leader epoch. It contains the segments still in COPY_SEGMENT_STARTED and DELETE_SEGMENT_STARTED state or these have been replaced by callers with other segments having the same start offset for the leader epoch |
| highestLogOffset       | `Long`                          | The highest log offset of the segments that reached the COPY_SEGMENT_FINISHED state.                                                                                                                                                              |

When metadata manager adds or updates log segment metadata, it also atomically updates/creates metadata for partition leader epochs, 
contained in this segment metadata.

### Storage backends

Remote metadata storage is abstracted in the `MetadataStorageBackend` interface. 
This allows to select the storage backend with configuration. `MetadataStorageBackend` is a binary key-value storage with following operations:
- Create value by key;
- Update value by key;
- Get value by key;
- Get values by key like provided one;
- Remove values by key like provided one;
- Atomically execute values creations/updates by keys. 

### Metadata key factory

Each remote metadata storage backend should also provide implementation of `MetadataKeyFactory`, that creates string keys for:

- Remote log segments metadata;
- Partition metadata;
- Leader epoch metadata;
- Collective key of all segments metadata from particular partition.

### Remote storage connector

Remote storage connector is implementation of `MetadataStorageConnector`, that simply creates `MetadataStorageBackend` and `MetadataKeyFactory`.

## Configuration

Kafka can be configured using the following options to enable the current `RemoteLogMetadataManager` implementation:

| Name                                            | Type   | Default value                                                                 | Description                                                                                                                                                                                                |
|-------------------------------------------------|--------|-------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `remote.log.metadata.manager.class.name`        | String | org.apache.kafka.server.log.remote.storage.RemoteLogMetadataManager           | Fully qualified class name of `RemoteLogMetadataManager` implementation. To enable current implementation it should be set to 'io.arenadata.kafka.tieredstorage.metadata.storage.RemoteLogMetadataManager' |
| `rlmm.config.metadata.storage.connector.class`  | String | -                                                                             | Fully qualified class name of `MetadataStorageConnector` implementation.                                                                                                                                   |
| `rlmm.config.metadata.storage.serializer.class` | String | io.arenadata.kafka.tieredstorage.metadata.storage.serde.DefaultMetadataMapper | Fully qualified class name of `MetadataSerDe` implementation, remote log/partition metadata serializer.                                                                                                    |

### Local demo

To run the Kafka instance with Zookeeper RemoteLogMetadataManager and HDFS storage backend locally, follow these steps:
1. Run `make docker_image` to build the docker image of Kafka with the necessary dependencies.
2. Follow the instruction from [demo/README.md](demo/README.md)
