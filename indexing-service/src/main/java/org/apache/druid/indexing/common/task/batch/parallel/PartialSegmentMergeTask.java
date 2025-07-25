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

package org.apache.druid.indexing.common.task.batch.parallel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexer.report.TaskReport;
import org.apache.druid.indexing.common.TaskLock;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.LockListAction;
import org.apache.druid.indexing.common.actions.SurrogateAction;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.task.AbstractBatchIndexTask;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.segment.BaseProgressIndicator;
import org.apache.druid.segment.DataSegmentsWithSchemas;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexMerger;
import org.apache.druid.segment.IndexMergerV9;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.SchemaPayloadPlus;
import org.apache.druid.segment.SegmentSchemaMapping;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.loading.DataSegmentPusher;
import org.apache.druid.segment.metadata.CentralizedDatasourceSchemaConfig;
import org.apache.druid.segment.metadata.FingerprintGenerator;
import org.apache.druid.segment.realtime.appenderator.TaskSegmentSchemaUtil;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.partition.ShardSpec;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Base class for creating task that merges partial segments created by {@link PartialSegmentGenerateTask}.
 */
abstract class PartialSegmentMergeTask<S extends ShardSpec> extends PerfectRollupWorkerTask
{
  private static final Logger LOG = new Logger(PartialSegmentMergeTask.class);

  private final PartialSegmentMergeIOConfig ioConfig;
  private final int numAttempts;
  private final String subtaskSpecId;

  PartialSegmentMergeTask(
      // id shouldn't be null except when this task is created by ParallelIndexSupervisorTask
      @Nullable String id,
      final String groupId,
      final TaskResource taskResource,
      final String supervisorTaskId,
      @Nullable String subtaskSpecId,
      DataSchema dataSchema,
      PartialSegmentMergeIOConfig ioConfig,
      ParallelIndexTuningConfig tuningConfig,
      final int numAttempts, // zero-based counting
      final Map<String, Object> context
  )
  {
    super(
        id,
        groupId,
        taskResource,
        dataSchema,
        tuningConfig,
        context,
        supervisorTaskId
    );

    Preconditions.checkArgument(
        !dataSchema.getGranularitySpec().inputIntervals().isEmpty(),
        "Missing intervals in granularitySpec"
    );
    this.subtaskSpecId = subtaskSpecId;
    this.ioConfig = ioConfig;
    this.numAttempts = numAttempts;
  }

  @JsonProperty
  public int getNumAttempts()
  {
    return numAttempts;
  }

  @JsonProperty
  @Override
  public String getSubtaskSpecId()
  {
    return subtaskSpecId;
  }

  @Override
  public boolean isReady(TaskActionClient taskActionClient)
  {
    return true;
  }

  @Override
  public TaskStatus runTask(TaskToolbox toolbox) throws Exception
  {
    // Group partitionLocations by interval and partitionId
    final Map<Interval, Int2ObjectMap<List<PartitionLocation>>> intervalToBuckets = new HashMap<>();
    for (PartitionLocation location : ioConfig.getPartitionLocations()) {
      intervalToBuckets.computeIfAbsent(location.getInterval(), k -> new Int2ObjectOpenHashMap<>())
                       .computeIfAbsent(location.getBucketId(), k -> new ArrayList<>())
                       .add(location);
    }

    final List<TaskLock> locks = toolbox.getTaskActionClient().submit(
        new SurrogateAction<>(getSupervisorTaskId(), new LockListAction())
    );
    final Map<Interval, String> intervalToVersion = Maps.newHashMapWithExpectedSize(locks.size());
    locks.forEach(lock -> {
      if (lock.isRevoked()) {
        throw new ISE("Lock[%s] is revoked", lock);
      }
      final String mustBeNull = intervalToVersion.put(lock.getInterval(), lock.getVersion());
      if (mustBeNull != null) {
        throw new ISE(
            "Unexpected state: Two versions([%s], [%s]) for the same interval[%s]",
            lock.getVersion(),
            mustBeNull,
            lock.getInterval()
        );
      }
    });

    final Stopwatch fetchStopwatch = Stopwatch.createStarted();
    final Map<Interval, Int2ObjectMap<List<File>>> intervalToUnzippedFiles = fetchSegmentFiles(
        toolbox,
        intervalToBuckets
    );
    final long fetchTime = fetchStopwatch.elapsed(TimeUnit.SECONDS);
    fetchStopwatch.stop();
    LOG.info("Fetch took [%s] seconds", fetchTime);

    final ParallelIndexSupervisorTaskClient taskClient = toolbox.getSupervisorTaskClientProvider().build(
        getSupervisorTaskId(),
        getTuningConfig().getChatHandlerTimeout(),
        getTuningConfig().getChatHandlerNumRetries()
    );

    final File persistDir = toolbox.getPersistDir();
    org.apache.commons.io.FileUtils.deleteQuietly(persistDir);
    FileUtils.mkdirp(persistDir);

    final DataSegmentsWithSchemas dataSegmentsWithSchemas = mergeAndPushSegments(
        toolbox,
        getDataSchema(),
        getTuningConfig(),
        persistDir,
        intervalToVersion,
        intervalToUnzippedFiles
    );

    taskClient.report(
        new PushedSegmentsReport(
            getId(),
            Collections.emptySet(),
            dataSegmentsWithSchemas.getSegments(),
            new TaskReport.ReportMap(),
            dataSegmentsWithSchemas.getSegmentSchemaMapping()
        )
    );

    return TaskStatus.success(getId());
  }

  private Map<Interval, Int2ObjectMap<List<File>>> fetchSegmentFiles(
      TaskToolbox toolbox,
      Map<Interval, Int2ObjectMap<List<PartitionLocation>>> intervalToBuckets
  ) throws IOException
  {
    final File tempDir = toolbox.getIndexingTmpDir();
    org.apache.commons.io.FileUtils.deleteQuietly(tempDir);
    FileUtils.mkdirp(tempDir);

    final Map<Interval, Int2ObjectMap<List<File>>> intervalToUnzippedFiles = new HashMap<>();
    // Fetch partition files
    for (Entry<Interval, Int2ObjectMap<List<PartitionLocation>>> entryPerInterval : intervalToBuckets.entrySet()) {
      final Interval interval = entryPerInterval.getKey();
      for (Int2ObjectMap.Entry<List<PartitionLocation>> entryPerBucketId : entryPerInterval.getValue().int2ObjectEntrySet()) {
        final int bucketId = entryPerBucketId.getIntKey();
        final File partitionDir = org.apache.commons.io.FileUtils.getFile(
            tempDir,
            interval.getStart().toString(),
            interval.getEnd().toString(),
            Integer.toString(bucketId)
        );
        FileUtils.mkdirp(partitionDir);
        for (PartitionLocation location : entryPerBucketId.getValue()) {
          final File unzippedDir = toolbox.getShuffleClient().fetchSegmentFile(partitionDir, getSupervisorTaskId(), location);
          intervalToUnzippedFiles.computeIfAbsent(interval, k -> new Int2ObjectOpenHashMap<>())
                                 .computeIfAbsent(bucketId, k -> new ArrayList<>())
                                 .add(unzippedDir);
        }
      }
    }
    return intervalToUnzippedFiles;
  }

  /**
   * Create a {@link ShardSpec} suitable for the desired secondary partitioning strategy.
   */
  abstract S createShardSpec(TaskToolbox toolbox, Interval interval, int bucketId);

  private DataSegmentsWithSchemas mergeAndPushSegments(
      TaskToolbox toolbox,
      DataSchema dataSchema,
      ParallelIndexTuningConfig tuningConfig,
      File persistDir,
      Map<Interval, String> intervalToVersion,
      Map<Interval, Int2ObjectMap<List<File>>> intervalToUnzippedFiles
  ) throws Exception
  {
    final DataSegmentPusher segmentPusher = toolbox.getSegmentPusher();
    final Set<DataSegment> pushedSegments = new HashSet<>();
    final SegmentSchemaMapping segmentSchemaMapping = new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION);

    final FingerprintGenerator fingerprintGenerator = new FingerprintGenerator(toolbox.getJsonMapper());
    for (Entry<Interval, Int2ObjectMap<List<File>>> entryPerInterval : intervalToUnzippedFiles.entrySet()) {
      final Interval interval = entryPerInterval.getKey();
      for (Int2ObjectMap.Entry<List<File>> entryPerBucketId : entryPerInterval.getValue().int2ObjectEntrySet()) {
        long startTime = System.nanoTime();
        final int bucketId = entryPerBucketId.getIntKey();
        final List<File> segmentFilesToMerge = entryPerBucketId.getValue();

        final Pair<File, List<String>> mergedFileAndDimensionNames = mergeSegmentsInSamePartition(
            dataSchema,
            tuningConfig,
            toolbox.getIndexIO(),
            toolbox.getIndexMergerV9(),
            segmentFilesToMerge,
            tuningConfig.getMaxNumSegmentsToMerge(),
            persistDir,
            0
        );

        long mergeFinishTime = System.nanoTime();
        LOG.info("Merged [%d] input segment(s) for interval [%s] in [%,d]ms.",
                 segmentFilesToMerge.size(),
                 interval,
                 (mergeFinishTime - startTime) / 1000000
        );
        final List<String> metricNames = Arrays.stream(dataSchema.getAggregators())
                                               .map(AggregatorFactory::getName)
                                               .collect(Collectors.toList());
        SegmentId segmentId = SegmentId.of(
            getDataSource(),
            interval,
            Preconditions.checkNotNull(AbstractBatchIndexTask.findVersion(
                intervalToVersion,
                interval
            ), "version for interval[%s]", interval),
            0
        );

        final DataSegment segment = segmentPusher.push(
            mergedFileAndDimensionNames.lhs,
            DataSegment.builder(segmentId)
                       .shardSpec(createShardSpec(toolbox, interval, bucketId))
                       .dimensions(mergedFileAndDimensionNames.rhs)
                       .metrics(metricNames)
                       .projections(dataSchema.getProjectionNames())
                       .build(),
            false
        );
        long pushFinishTime = System.nanoTime();
        pushedSegments.add(segment);

        if (toolbox.getCentralizedTableSchemaConfig().isEnabled()) {
          SchemaPayloadPlus schemaPayloadPlus =
              TaskSegmentSchemaUtil.getSegmentSchema(mergedFileAndDimensionNames.lhs, toolbox.getIndexIO());
          segmentSchemaMapping.addSchema(
              segment.getId(),
              schemaPayloadPlus,
              fingerprintGenerator.generateFingerprint(
                  schemaPayloadPlus.getSchemaPayload(),
                  getDataSource(),
                  CentralizedDatasourceSchemaConfig.SCHEMA_VERSION
              )
          );
        }

        LOG.info("Built segment [%s] for interval [%s] (from [%d] input segment(s) in [%,d]ms) of "
            + "size [%d] bytes and pushed ([%,d]ms) to deep storage [%s].",
            segment.getId(),
            interval,
            segmentFilesToMerge.size(),
            (mergeFinishTime - startTime) / 1000000,
            segment.getSize(),
            (pushFinishTime - mergeFinishTime) / 1000000,
            segment.getLoadSpec()
        );
      }
    }
    if (toolbox.getCentralizedTableSchemaConfig().isEnabled()) {
      LOG.info("SegmentSchema for the pushed segments is [%s]", segmentSchemaMapping);
    }
    return new DataSegmentsWithSchemas(pushedSegments, segmentSchemaMapping.isNonEmpty() ? segmentSchemaMapping : null);
  }

  private static Pair<File, List<String>> mergeSegmentsInSamePartition(
      DataSchema dataSchema,
      ParallelIndexTuningConfig tuningConfig,
      IndexIO indexIO,
      IndexMergerV9 merger,
      List<File> indexes,
      int maxNumSegmentsToMerge,
      File baseOutDir,
      int outDirSuffix
  ) throws IOException
  {
    int suffix = outDirSuffix;
    final List<File> mergedFiles = new ArrayList<>();
    List<String> dimensionNames = null;
    for (int i = 0; i < indexes.size(); i += maxNumSegmentsToMerge) {
      final List<File> filesToMerge = indexes.subList(i, Math.min(i + maxNumSegmentsToMerge, indexes.size()));
      final List<QueryableIndex> indexesToMerge = new ArrayList<>(filesToMerge.size());
      final Closer indexCleaner = Closer.create();
      for (File file : filesToMerge) {
        final QueryableIndex queryableIndex = indexIO.loadIndex(file);
        indexesToMerge.add(queryableIndex);
        indexCleaner.register(() -> {
          queryableIndex.close();
          file.delete();
        });
      }
      if (maxNumSegmentsToMerge >= indexes.size()) {
        dimensionNames = IndexMerger.getMergedDimensionsFromQueryableIndexes(indexesToMerge, dataSchema.getDimensionsSpec());
      }
      final File outDir = new File(baseOutDir, StringUtils.format("merged_%d", suffix++));
      mergedFiles.add(
          merger.mergeQueryableIndex(
              indexesToMerge,
              dataSchema.getGranularitySpec().isRollup(),
              dataSchema.getAggregators(),
              dataSchema.getDimensionsSpec(),
              outDir,
              tuningConfig.getIndexSpec(),
              tuningConfig.getIndexSpecForIntermediatePersists(),
              new BaseProgressIndicator(),
              tuningConfig.getSegmentWriteOutMediumFactory(),
              tuningConfig.getMaxColumnsToMerge()
          )
      );

      indexCleaner.close();
    }

    if (mergedFiles.size() == 1) {
      return Pair.of(mergedFiles.get(0), Preconditions.checkNotNull(dimensionNames, "dimensionNames"));
    } else {
      return mergeSegmentsInSamePartition(
          dataSchema,
          tuningConfig,
          indexIO,
          merger,
          mergedFiles,
          maxNumSegmentsToMerge,
          baseOutDir,
          suffix
      );
    }
  }
}
