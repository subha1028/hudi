/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.common.table.read;

import org.apache.hudi.common.config.HoodieCommonConfig;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.config.HoodieStorageConfig;
import org.apache.hudi.common.config.RecordMergeMode;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.engine.HoodieLocalEngineContext;
import org.apache.hudi.common.engine.HoodieReaderContext;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.view.FileSystemViewManager;
import org.apache.hudi.common.table.view.FileSystemViewStorageConfig;
import org.apache.hudi.common.table.view.SyncableFileSystemView;
import org.apache.hudi.common.testutils.HoodieTestDataGenerator;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.ExternalSpillableMap;
import org.apache.hudi.keygen.constant.KeyGeneratorOptions;
import org.apache.hudi.metadata.HoodieTableMetadata;
import org.apache.hudi.storage.StorageConfiguration;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.apache.hudi.common.model.WriteOperationType.INSERT;
import static org.apache.hudi.common.model.WriteOperationType.UPSERT;
import static org.apache.hudi.common.table.HoodieTableConfig.PARTITION_FIELDS;
import static org.apache.hudi.common.table.HoodieTableConfig.RECORD_MERGER_STRATEGY;
import static org.apache.hudi.common.table.HoodieTableConfig.RECORD_MERGE_MODE;
import static org.apache.hudi.common.table.read.HoodieBaseFileGroupRecordBuffer.compareTo;
import static org.apache.hudi.common.testutils.HoodieTestUtils.getLogFileListFromFileSlice;
import static org.apache.hudi.common.testutils.RawTripTestPayload.recordsToStrings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Tests {@link HoodieFileGroupReader} with different engines
 */
public abstract class TestHoodieFileGroupReaderBase<T> {
  @TempDir
  protected java.nio.file.Path tempDir;

  public abstract StorageConfiguration<?> getStorageConf();

  public abstract String getBasePath();

  public abstract HoodieReaderContext<T> getHoodieReaderContext(String tablePath, Schema avroSchema, StorageConfiguration<?> storageConf);

  public abstract void commitToTable(List<String> recordList, String operation,
                                     Map<String, String> writeConfigs);

  public abstract void validateRecordsInFileGroup(String tablePath,
                                                  List<T> actualRecordList,
                                                  Schema schema,
                                                  String fileGroupId);

  public abstract Comparable getComparableUTF8String(String value);

  @Test
  public void testCompareToComparable() {
    // Test same type
    assertEquals(1, compareTo(Boolean.TRUE, Boolean.FALSE));
    assertEquals(0, compareTo(Boolean.TRUE, Boolean.TRUE));
    assertEquals(-1, compareTo(Boolean.FALSE, Boolean.TRUE));
    assertEquals(1, compareTo(20, 15));
    assertEquals(0, compareTo(15, 15));
    assertEquals(-1, compareTo(10, 15));
    assertEquals(1, compareTo(1.1f, 1.0f));
    assertEquals(0, compareTo(1.0f, 1.0f));
    assertEquals(-1, compareTo(0.9f, 1.0f));
    assertEquals(1, compareTo(1.1, 1.0));
    assertEquals(0, compareTo(1.0, 1.0));
    assertEquals(-1, compareTo(0.9, 1.0));
    assertEquals(1, compareTo("value2", "value1"));
    assertEquals(0, compareTo("value1", "value1"));
    assertEquals(-1, compareTo("value1", "value2"));
    // Test different types which are comparable
    assertEquals(1, compareTo(Long.MAX_VALUE / 2L, 10));
    assertEquals(1, compareTo(20, 10L));
    assertEquals(0, compareTo(10L, 10));
    assertEquals(0, compareTo(10, 10L));
    assertEquals(-1, compareTo(10, Long.MAX_VALUE));
    assertEquals(-1, compareTo(10L, 20));
    assertEquals(1, compareTo(getComparableUTF8String("value2"), "value1"));
    assertEquals(1, compareTo("value2", getComparableUTF8String("value1")));
    assertEquals(0, compareTo(getComparableUTF8String("value1"), "value1"));
    assertEquals(0, compareTo("value1", getComparableUTF8String("value1")));
    assertEquals(-1, compareTo(getComparableUTF8String("value1"), "value2"));
    assertEquals(-1, compareTo("value1", getComparableUTF8String("value2")));
  }

  private static Stream<Arguments> testArguments() {
    return Stream.of(
        arguments(RecordMergeMode.OVERWRITE_WITH_LATEST, "avro"),
        arguments(RecordMergeMode.OVERWRITE_WITH_LATEST, "parquet"),
        arguments(RecordMergeMode.EVENT_TIME_ORDERING, "avro"),
        arguments(RecordMergeMode.EVENT_TIME_ORDERING, "parquet"),
        arguments(RecordMergeMode.CUSTOM, "avro"),
        arguments(RecordMergeMode.CUSTOM, "parquet")
    );
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testReadFileGroupInMergeOnReadTable(RecordMergeMode recordMergeMode, String logDataBlockFormat) throws Exception {
    Map<String, String> writeConfigs = new HashMap<>(getCommonConfigs());
    writeConfigs.put(HoodieStorageConfig.LOGFILE_DATA_BLOCK_FORMAT.key(), logDataBlockFormat);

    try (HoodieTestDataGenerator dataGen = new HoodieTestDataGenerator(0xDEEF)) {
      // One commit; reading one file group containing a base file only
      commitToTable(recordsToStrings(dataGen.generateInserts("001", 100)), INSERT.value(), writeConfigs);
      validateOutputFromFileGroupReader(
          getStorageConf(), getBasePath(), dataGen.getPartitionPaths(), true, 0, recordMergeMode);

      // Two commits; reading one file group containing a base file and a log file
      commitToTable(recordsToStrings(dataGen.generateUpdates("002", 100)), UPSERT.value(), writeConfigs);
      validateOutputFromFileGroupReader(
          getStorageConf(), getBasePath(), dataGen.getPartitionPaths(), true, 1, recordMergeMode);

      // Three commits; reading one file group containing a base file and two log files
      commitToTable(recordsToStrings(dataGen.generateUpdates("003", 100)), UPSERT.value(), writeConfigs);
      validateOutputFromFileGroupReader(
          getStorageConf(), getBasePath(), dataGen.getPartitionPaths(), true, 2, recordMergeMode);
    }
  }

  @ParameterizedTest
  @MethodSource("testArguments")
  public void testReadLogFilesOnlyInMergeOnReadTable(RecordMergeMode recordMergeMode, String logDataBlockFormat) throws Exception {
    Map<String, String> writeConfigs = new HashMap<>(getCommonConfigs());
    writeConfigs.put(HoodieStorageConfig.LOGFILE_DATA_BLOCK_FORMAT.key(), logDataBlockFormat);
    // Use InMemoryIndex to generate log only mor table
    writeConfigs.put("hoodie.index.type", "INMEMORY");

    try (HoodieTestDataGenerator dataGen = new HoodieTestDataGenerator(0xDEEF)) {
      // One commit; reading one file group containing a base file only
      commitToTable(recordsToStrings(dataGen.generateInserts("001", 100)), INSERT.value(), writeConfigs);
      validateOutputFromFileGroupReader(
          getStorageConf(), getBasePath(), dataGen.getPartitionPaths(), false, 1, recordMergeMode);

      // Two commits; reading one file group containing a base file and a log file
      commitToTable(recordsToStrings(dataGen.generateUpdates("002", 100)), UPSERT.value(), writeConfigs);
      validateOutputFromFileGroupReader(
          getStorageConf(), getBasePath(), dataGen.getPartitionPaths(), false, 2, recordMergeMode);
    }
  }

  private Map<String, String> getCommonConfigs() {
    Map<String, String> configMapping = new HashMap<>();
    configMapping.put(KeyGeneratorOptions.RECORDKEY_FIELD_NAME.key(), "_row_key");
    configMapping.put(KeyGeneratorOptions.PARTITIONPATH_FIELD_NAME.key(), "partition_path");
    configMapping.put("hoodie.datasource.write.precombine.field", "timestamp");
    configMapping.put("hoodie.payload.ordering.field", "timestamp");
    configMapping.put(HoodieTableConfig.HOODIE_TABLE_NAME_KEY, "hoodie_test");
    configMapping.put("hoodie.insert.shuffle.parallelism", "4");
    configMapping.put("hoodie.upsert.shuffle.parallelism", "4");
    configMapping.put("hoodie.bulkinsert.shuffle.parallelism", "2");
    configMapping.put("hoodie.delete.shuffle.parallelism", "1");
    configMapping.put("hoodie.merge.small.file.group.candidates.limit", "0");
    configMapping.put("hoodie.compact.inline", "false");
    return configMapping;
  }

  private void validateOutputFromFileGroupReader(StorageConfiguration<?> storageConf,
                                                 String tablePath,
                                                 String[] partitionPaths,
                                                 boolean containsBaseFile,
                                                 int expectedLogFileNum,
                                                 RecordMergeMode recordMergeMode) throws Exception {
    HoodieTableMetaClient metaClient = HoodieTestUtils.createMetaClient(storageConf, tablePath);
    Schema avroSchema = new TableSchemaResolver(metaClient).getTableAvroSchema();
    HoodieEngineContext engineContext = new HoodieLocalEngineContext(storageConf);
    HoodieMetadataConfig metadataConfig = HoodieMetadataConfig.newBuilder().build();
    FileSystemViewManager viewManager = FileSystemViewManager.createViewManager(
        engineContext, FileSystemViewStorageConfig.newBuilder().build(),
        HoodieCommonConfig.newBuilder().build(),
        mc -> HoodieTableMetadata.create(
            engineContext, mc.getStorage(), metadataConfig, mc.getBasePathV2().toString()));
    SyncableFileSystemView fsView = viewManager.getFileSystemView(metaClient);
    FileSlice fileSlice = fsView.getAllFileSlices(partitionPaths[0]).findFirst().get();
    List<String> logFilePathList = getLogFileListFromFileSlice(fileSlice);
    assertEquals(expectedLogFileNum, logFilePathList.size());

    List<T> actualRecordList = new ArrayList<>();
    TypedProperties props = new TypedProperties();
    props.setProperty("hoodie.datasource.write.precombine.field", "timestamp");
    props.setProperty("hoodie.payload.ordering.field", "timestamp");
    props.setProperty(RECORD_MERGER_STRATEGY.key(), RECORD_MERGER_STRATEGY.defaultValue());
    props.setProperty(RECORD_MERGE_MODE.key(), recordMergeMode.name());
    if (metaClient.getTableConfig().contains(PARTITION_FIELDS)) {
      props.setProperty(PARTITION_FIELDS.key(), metaClient.getTableConfig().getString(PARTITION_FIELDS));
    }
    assertEquals(containsBaseFile, fileSlice.getBaseFile().isPresent());
    HoodieFileGroupReader<T> fileGroupReader = new HoodieFileGroupReader<>(
        getHoodieReaderContext(tablePath, avroSchema, storageConf),
        metaClient.getStorage(),
        tablePath,
        metaClient.getActiveTimeline().lastInstant().get().getTimestamp(),
        fileSlice,
        avroSchema,
        avroSchema,
        Option.empty(),
        metaClient,
        props,
        metaClient.getTableConfig(),
        0,
        fileSlice.getTotalFileSize(),
        false,
        1024 * 1024 * 1000,
        metaClient.getTempFolderPath(),
        ExternalSpillableMap.DiskMapType.ROCKS_DB,
        false);
    fileGroupReader.initRecordIterators();
    while (fileGroupReader.hasNext()) {
      actualRecordList.add(fileGroupReader.next());
    }
    fileGroupReader.close();

    validateRecordsInFileGroup(tablePath, actualRecordList, avroSchema, fileSlice.getFileId());
  }
}
