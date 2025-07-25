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

package org.apache.druid.msq.exec;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.catalog.MapMetadataCatalog;
import org.apache.druid.catalog.model.DatasourceProjectionMetadata;
import org.apache.druid.catalog.model.TableId;
import org.apache.druid.catalog.model.table.DatasourceDefn;
import org.apache.druid.catalog.model.table.TableBuilder;
import org.apache.druid.catalog.sql.LiveCatalogResolver;
import org.apache.druid.data.input.impl.AggregateProjectionSpec;
import org.apache.druid.data.input.impl.LongDimensionSchema;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.DruidExceptionMatcher;
import org.apache.druid.hll.HyperLogLogCollector;
import org.apache.druid.indexing.common.TaskLockType;
import org.apache.druid.indexing.common.task.Tasks;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.msq.indexing.LegacyMSQSpec;
import org.apache.druid.msq.indexing.MSQControllerTask;
import org.apache.druid.msq.indexing.MSQTuningConfig;
import org.apache.druid.msq.indexing.destination.DataSourceMSQDestination;
import org.apache.druid.msq.indexing.error.ColumnNameRestrictedFault;
import org.apache.druid.msq.indexing.error.RowTooLargeFault;
import org.apache.druid.msq.indexing.error.TooManySegmentsInTimeChunkFault;
import org.apache.druid.msq.indexing.report.MSQSegmentReport;
import org.apache.druid.msq.kernel.WorkerAssignmentStrategy;
import org.apache.druid.msq.sql.MSQTaskQueryMaker;
import org.apache.druid.msq.test.CounterSnapshotMatcher;
import org.apache.druid.msq.test.MSQTestBase;
import org.apache.druid.msq.util.MultiStageQueryContext;
import org.apache.druid.query.OrderBy;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.CountAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniquesAggregatorFactory;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.groupby.orderby.DefaultLimitSpec;
import org.apache.druid.query.groupby.orderby.OrderByColumnSpec;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.segment.AggregateProjectionMetadata;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.server.lookup.cache.LookupLoadingSpec;
import org.apache.druid.sql.calcite.planner.CatalogResolver;
import org.apache.druid.sql.calcite.planner.ColumnMapping;
import org.apache.druid.sql.calcite.planner.ColumnMappings;
import org.apache.druid.sql.calcite.util.CalciteTests;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.hamcrest.CoreMatchers;
import org.junit.internal.matchers.ThrowableMessageMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


public class MSQInsertTest extends MSQTestBase
{
  private static final String WITH_APPEND_LOCK = "WITH_APPEND_LOCK";
  private static final Map<String, Object> QUERY_CONTEXT_WITH_APPEND_LOCK =
      ImmutableMap.<String, Object>builder()
                  .putAll(DEFAULT_MSQ_CONTEXT)
                  .put(
                      Tasks.TASK_LOCK_TYPE,
                      TaskLockType.APPEND.name().toLowerCase(Locale.ENGLISH)
                  )
                  .build();
  private final HashFunction fn = Hashing.murmur3_128();

  public static Collection<Object[]> data()
  {
    Object[][] data = new Object[][]{
        {DEFAULT, DEFAULT_MSQ_CONTEXT},
        {SUPERUSER, SUPERUSER_MSQ_CONTEXT},
        {DURABLE_STORAGE, DURABLE_STORAGE_MSQ_CONTEXT},
        {FAULT_TOLERANCE, FAULT_TOLERANCE_MSQ_CONTEXT},
        {PARALLEL_MERGE, PARALLEL_MERGE_MSQ_CONTEXT},
        {WITH_APPEND_LOCK, QUERY_CONTEXT_WITH_APPEND_LOCK}
    };
    return Arrays.asList(data);
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = expectedFooRows();
    int expectedCounterRows = expectedRows.size();
    long[] expectedArray = createExpectedFrameArray(expectedCounterRows, 1);

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    testIngestQuery().setSql(
                         "insert into foo1 select  __time, dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(context)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedRows)
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             NumberedShardSpec.class.getSimpleName(),
                             "Using NumberedShardSpec to generate segments since the query is inserting rows."
                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         2, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(Arrays.stream(expectedArray).sum()),
                         2, 0
                     )
                     .verifyResults();

  }

  @Override
  protected CatalogResolver createMockCatalogResolver()
  {
    final MapMetadataCatalog metadataCatalog = new MapMetadataCatalog(objectMapper);
    metadataCatalog.addSpec(
        TableId.datasource("foo2"),
        TableBuilder.datasource("foo2", Granularities.DAY.toString())
                    .property(
                        DatasourceDefn.PROJECTIONS_KEYS_PROPERTY,
                        ImmutableList.of(
                            new DatasourceProjectionMetadata(
                                new AggregateProjectionSpec(
                                    "channel_added_hourly",
                                    VirtualColumns.create(
                                        Granularities.toVirtualColumn(
                                            Granularities.HOUR,
                                            Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME
                                        )
                                    ),
                                    ImmutableList.of(
                                        new LongDimensionSchema(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
                                        new StringDimensionSchema("channel")
                                    ),
                                    new AggregatorFactory[]{
                                        new LongSumAggregatorFactory("sum_added", "added")
                                    }
                                )
                            ),
                            new DatasourceProjectionMetadata(
                                new AggregateProjectionSpec(
                                    "channel_delta_daily",
                                    VirtualColumns.create(
                                        Granularities.toVirtualColumn(
                                            Granularities.DAY,
                                            Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME
                                        )
                                    ),
                                    ImmutableList.of(
                                        new LongDimensionSchema(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
                                        new StringDimensionSchema("channel")
                                    ),
                                    new AggregatorFactory[]{
                                        new LongSumAggregatorFactory("sum_delta", "delta")
                                    }
                                )
                            )
                        )
                    )
                    .buildSpec()
    );
    return new LiveCatalogResolver(metadataCatalog);
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithSpec(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = expectedFooRows();
    int expectedCounterRows = expectedRows.size();
    long[] expectedArray = createExpectedFrameArray(expectedCounterRows, 1);

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    LegacyMSQSpec msqSpec = new LegacyMSQSpec(
        GroupByQuery.builder()
                    .setDataSource("foo")
                    .setInterval(Intervals.ONLY_ETERNITY)
                    .setDimFilter(notNull("dim1"))
                    .setGranularity(Granularities.ALL)
                    .setDimensions(
                        new DefaultDimensionSpec("__time", "d0", ColumnType.LONG),
                        new DefaultDimensionSpec("dim1", "d1", ColumnType.STRING)
                    )
                    .setContext(ImmutableMap.<String, Object>builder()
                                            .put("__user", "allowAll")
                                            .put("finalize", true)
                                            .put("maxNumTasks", 2)
                                            .put("maxParseExceptions", 0)
                                            .put("sqlInsertSegmentGranularity", "\"DAY\"")
                                            .put("sqlQueryId", "test-query")
                                            .put("sqlStringifyArrays", false)
                                            .build()
                    )
                    .setLimitSpec(DefaultLimitSpec.builder()
                                                  .orderBy(OrderByColumnSpec.asc("d1"))
                                                  .build()
                    )
                    .setAggregatorSpecs(new CountAggregatorFactory("a0"))
                    .setQuerySegmentSpec(new MultipleIntervalSegmentSpec(Intervals.ONLY_ETERNITY))
                    .build(),
        new ColumnMappings(
            ImmutableList.of(
                new ColumnMapping("d0", "__time"),
                new ColumnMapping("d1", "dim1"),
                new ColumnMapping("a0", "cnt"))
        ),
        new DataSourceMSQDestination(
            "foo1",
            Granularity.fromString("DAY"),
            null,
            null,
            null,
            null,
            null
        ),
        WorkerAssignmentStrategy.MAX,
        MSQTuningConfig.defaultConfig()
    );

    ImmutableMap<String, Object> sqlContext =
        ImmutableMap.<String, Object>builder()
            .putAll(context)
                    .put("sqlInsertSegmentGranularity", "\"DAY\"")
                    .put("forceTimeChunkLock", true)
                    .build();

    MSQControllerTask controllerTask = new MSQControllerTask(
        TEST_CONTROLLER_TASK_ID,
        msqSpec,
        null,
        sqlContext,
        null,
        ImmutableList.of(SqlTypeName.TIMESTAMP, SqlTypeName.VARCHAR, SqlTypeName.BIGINT),
        ImmutableList.of(ColumnType.LONG, ColumnType.STRING, ColumnType.LONG),
        null
        );

    testIngestQuery().setTaskSpec(controllerTask)
                     .setExpectedDataSource("foo1")
                     .setQueryContext(context)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedRows)
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             NumberedShardSpec.class.getSimpleName(),
                             "Using NumberedShardSpec to generate segments since the query is inserting rows."
                         )
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(Arrays.stream(expectedArray).sum()),
                         2, 0
                     )
                     .setExpectedLookupLoadingSpec(LookupLoadingSpec.ALL)
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertWithExistingTimeColumn(String contextName, Map<String, Object> context) throws IOException
  {
    List<Object[]> expectedRows = ImmutableList.of(
        new Object[] {1678897351000L, "A"},
        new Object[] {1679588551000L, "B"},
        new Object[] {1682266951000L, "C"}
    );

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("flags", ColumnType.STRING)
                                            .build();

    final File toRead = getResourceAsTemporaryFile("/dataset-with-time-column.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    testIngestQuery().setSql(" INSERT INTO foo1 SELECT\n"
                             + "  __time,\n"
                             + "  flags\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"__time\", \"type\": \"long\"}, {\"name\": \"flags\", \"type\": \"string\"}]'\n"
                             + "  )\n"
                             + ")\n"
                             + "WHERE __time > TIMESTAMP '1999-01-01 00:00:00'\n"
                             + "PARTITIONED BY day")
                     .setQueryContext(context)
                     .setExpectedResultRows(expectedRows)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertWithUnnestInline(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = ImmutableList.of(
        new Object[]{1692226800000L, 1L},
        new Object[]{1692226800000L, 2L},
        new Object[]{1692226800000L, 3L}
    );

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("d", ColumnType.LONG)
                                            .build();


    testIngestQuery().setSql(
                         "insert into foo1 select TIME_PARSE('2023-08-16T23:00') as __time, d from UNNEST(ARRAY[1,2,3]) as unnested(d) PARTITIONED BY ALL")
                     .setQueryContext(context)
                     .setExpectedResultRows(expectedRows)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertWithUnnest(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = ImmutableList.of(
        new Object[]{946684800000L, "a"},
        new Object[]{946684800000L, "b"},
        new Object[]{946771200000L, "b"},
        new Object[]{946771200000L, "c"},
        new Object[]{946857600000L, "d"},
        new Object[]{978307200000L, ""},
        new Object[]{978393600000L, null},
        new Object[]{978480000000L, null}
    );

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("d", ColumnType.STRING)
                                            .build();


    testIngestQuery().setSql(
                         "insert into foo1 select __time, d from foo,UNNEST(MV_TO_ARRAY(dim3)) as unnested(d) PARTITIONED BY ALL")
                     .setQueryContext(context)
                     .setExpectedResultRows(expectedRows)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertWithUnnestWithVirtualColumns(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = ImmutableList.of(
        new Object[]{946684800000L, 1.0f},
        new Object[]{946684800000L, 1.0f},
        new Object[]{946771200000L, 2.0f},
        new Object[]{946771200000L, 2.0f},
        new Object[]{946857600000L, 3.0f},
        new Object[]{946857600000L, 3.0f},
        new Object[]{978307200000L, 4.0f},
        new Object[]{978307200000L, 4.0f},
        new Object[]{978393600000L, 5.0f},
        new Object[]{978393600000L, 5.0f},
        new Object[]{978480000000L, 6.0f},
        new Object[]{978480000000L, 6.0f}
    );

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("d", ColumnType.FLOAT)
                                            .build();


    testIngestQuery().setSql(
                         "insert into foo1 select __time, d from foo,UNNEST(ARRAY[m1,m2]) as unnested(d) PARTITIONED BY ALL")
                     .setQueryContext(context)
                     .setExpectedResultRows(expectedRows)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnExternalDataSource(String contextName, Map<String, Object> context) throws IOException
  {
    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(" insert into foo1 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                             + "  count(*) as cnt\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                             + "  )\n"
                             + ") group by 1  PARTITIONED by day ")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo1",
                         Intervals.of("2016-06-27/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(new Object[]{1466985600000L, 20L}))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(20).bytes(toRead.length()).files(1).totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         2, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(1),
                         2, 0
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnExternalDataSourceWithCatalogProjections(String contextName, Map<String, Object> context) throws IOException
  {
    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("channel", ColumnType.STRING)
                                            .add("page", ColumnType.STRING)
                                            .add("user", ColumnType.STRING)
                                            .add("added", ColumnType.LONG)
                                            .add("deleted", ColumnType.LONG)
                                            .add("delta", ColumnType.LONG)
                                            .build();
    AggregateProjectionMetadata expectedProjection = new AggregateProjectionMetadata(
        new AggregateProjectionMetadata.Schema(
            "channel_added_hourly",
            Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME,
            VirtualColumns.create(
                Granularities.toVirtualColumn(
                    Granularities.HOUR,
                    Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME
                )
            ),
            ImmutableList.of(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME, "channel"),
            new AggregatorFactory[] {
                new LongSumAggregatorFactory("sum_added", "added")
            },
            ImmutableList.of(
                OrderBy.ascending(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
                OrderBy.ascending("channel")
            )
        ),
        16
    );
    AggregateProjectionMetadata expectedProjection2 = new AggregateProjectionMetadata(
        new AggregateProjectionMetadata.Schema(
            "channel_delta_daily",
            Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME,
            VirtualColumns.create(
                Granularities.toVirtualColumn(
                    Granularities.DAY,
                    Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME
                )
            ),
            ImmutableList.of(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME, "channel"),
            new AggregatorFactory[] {
                new LongSumAggregatorFactory("sum_delta", "delta")
            },
            ImmutableList.of(
                OrderBy.ascending(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
                OrderBy.ascending("channel")
            )
        ),
        11
    );

    testIngestQuery().setSql(" insert into foo2 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to minute) AS __time,\n"
                             + "  channel,\n"
                             + "  page,\n"
                             + "  user,\n"
                             + "  added,\n"
                             + "  deleted,\n"
                             + "  delta\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"channel\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}, {\"name\": \"added\", \"type\": \"long\"}, {\"name\": \"deleted\", \"type\": \"long\"}, {\"name\": \"delta\", \"type\": \"long\"}]'\n"
                             + "  )\n"
                             + ") PARTITIONED by day ")
                     .setExpectedDataSource("foo2")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo2",
                         Intervals.of("2016-06-27/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedProjections(List.of(expectedProjection, expectedProjection2))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{1466985600000L, "#en.wikipedia", "Bailando 2015", "181.230.118.178", 2L, 0L, 2L},
                             new Object[]{1466985600000L, "#en.wikipedia", "Richie Rich's Christmas Wish", "JasonAQuest", 0L, 2L, -2L},
                             new Object[]{1466985600000L, "#pl.wikipedia", "Kategoria:Dyskusje nad usunięciem artykułu zakończone bez konsensusu − lipiec 2016", "Beau.bot", 270L, 0L, 270L},
                             new Object[]{1466985600000L, "#sv.wikipedia", "Salo Toraut", "Lsjbot", 31L, 0L, 31L},
                             new Object[]{1466985660000L, "#ceb.wikipedia", "Neqerssuaq", "Lsjbot", 4150L, 0L, 4150L},
                             new Object[]{1466985660000L, "#en.wikipedia", "Panama Canal", "Mariordo", 496L, 0L, 496L},
                             new Object[]{1466985660000L, "#es.wikipedia", "Sumo (banda)", "181.110.165.189", 0L, 173L, -173L},
                             new Object[]{1466985660000L, "#sh.wikipedia", "El Terco, Bachíniva", "Kolega2357", 0L, 1L, -1L},
                             new Object[]{1466985720000L, "#ru.wikipedia", "Википедия:Опросы/Унификация шаблонов «Не переведено»", "Wanderer777", 196L, 0L, 196L},
                             new Object[]{1466985720000L, "#sh.wikipedia", "Hermanos Díaz, Ascensión", "Kolega2357", 0L, 1L, -1L},
                             new Object[]{1466989320000L, "#es.wikipedia", "Clasificación para la Eurocopa Sub-21 de 2017", "Guly600", 4L, 0L, 4L},
                             new Object[]{1466989320000L, "#id.wikipedia", "Ibnu Sina", "Ftihikam", 106L, 0L, 106L},
                             new Object[]{1466989320000L, "#sh.wikipedia", "El Sicomoro, Ascensión", "Kolega2357", 0L, 1L, -1L},
                             new Object[]{1466989320000L, "#zh.wikipedia", "中共十八大以来的反腐败工作", "2001:DA8:207:E132:94DC:BA03:DFDF:8F9F", 18L, 0L, 18L},
                             new Object[]{1466992920000L, "#de.wikipedia", "Benutzer Diskussion:Squasher/Archiv/2016", "TaxonBot", 2560L, 0L, 2560L},
                             new Object[]{1466992920000L, "#pt.wikipedia", "Dobromir Zhechev", "Ceresta", 1926L, 0L, 1926L},
                             new Object[]{1466992920000L, "#sh.wikipedia", "Trinidad Jiménez G., Benemérito de las Américas", "Kolega2357", 0L, 1L, -1L},
                             new Object[]{1466992920000L, "#zh.wikipedia", "Wikipedia:頁面存廢討論/記錄/2016/06/27", "Tigerzeng", 1986L, 0L, 1986L},
                             new Object[]{1466992980000L, "#de.wikipedia", "Benutzer Diskussion:HerrSonderbar", "GiftBot", 364L, 0L, 364L},
                             new Object[]{1466992980000L, "#en.wikipedia", "File:Paint.net 4.0.6 screenshot.png", "Calvin Hogg", 0L, 463L, -463L}
                         )
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(20).bytes(toRead.length()).files(1).totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(20),
                         1, 0
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnExternalDataSourceWithCatalogProjectionsInvalidGranularity(
      String contextName,
      Map<String, Object> context
  ) throws IOException
  {
    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());


    testIngestQuery().setSql(" insert into foo2 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to minute) AS __time,\n"
                             + "  channel,\n"
                             + "  page,\n"
                             + "  user,\n"
                             + "  added,\n"
                             + "  deleted,\n"
                             + "  delta\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"channel\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}, {\"name\": \"added\", \"type\": \"long\"}, {\"name\": \"deleted\", \"type\": \"long\"}, {\"name\": \"delta\", \"type\": \"long\"}]'\n"
                             + "  )\n"
                             + ") PARTITIONED by hour")
                     .setExpectedValidationErrorMatcher(
                         DruidExceptionMatcher.invalidInput().expectMessageIs(
                             "projection[channel_delta_daily] has granularity[{type=period, period=P1D, timeZone=UTC, origin=null}] which must be finer than or equal to segment granularity[{type=period, period=PT1H, timeZone=UTC, origin=null}]"
                         )
                     )
                     .verifyPlanningErrors();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnExternalDataSourceWithCatalogProjectionsMissingColumn(
      String contextName,
      Map<String, Object> context
  ) throws IOException
  {
    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());


    testIngestQuery().setSql(" insert into foo2 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to minute) AS __time,\n"
                             + "  channel,\n"
                             + "  page,\n"
                             + "  user,\n"
                             + "  added\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"channel\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}, {\"name\": \"added\", \"type\": \"long\"}, {\"name\": \"deleted\", \"type\": \"long\"}]'\n"
                             + "  )\n"
                             + ") PARTITIONED by day")
                     .setExpectedExecutionErrorMatcher(
                         CoreMatchers.allOf(
                             CoreMatchers.instanceOf(ISE.class),
                             ThrowableMessageMatcher.hasMessage(CoreMatchers.containsString(
                                 "projection[channel_delta_daily] contains aggregator[sum_delta] that is missing required field[delta] in base table")
                             )
                         )
                     )
                     .verifyExecutionError();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithGroupByLimitWithoutClusterBy(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = expectedFooRows();
    int expectedCounterRows = expectedRows.size();

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    testIngestQuery().setSql(
                         "insert into foo1 select  __time, dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 limit 10 PARTITIONED by All")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(context)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedRows)
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         2, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         3, 0, "input0"
                     )

                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithTwoCountAggregatorsWithRollupContext(String contextName, Map<String, Object> context)
  {
    final List<Object[]> expectedRows = expectedFooRows();

    // Add 1L to each expected row, since we have two count aggregators.
    for (int i = 0; i < expectedRows.size(); i++) {
      final Object[] expectedRow = expectedRows.get(i);
      final Object[] newExpectedRow = new Object[expectedRow.length + 1];
      System.arraycopy(expectedRow, 0, newExpectedRow, 0, expectedRow.length);
      newExpectedRow[expectedRow.length] = 1L;
      expectedRows.set(i, newExpectedRow);
    }

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG)
                                            .add("cnt2", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "insert into foo1\n"
                         + "select  __time, dim1 , count(*) as cnt, count(*) as cnt2\n"
                         + "from foo\n"
                         + "where dim1 is not null\n"
                         + "group by 1, 2\n"
                         + "PARTITIONED by All")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(QueryContexts.override(context, ROLLUP_CONTEXT_PARAMS))
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedRows)
                     .setExpectedRollUp(true)
                     .addExpectedAggregatorFactory(new LongSumAggregatorFactory("cnt", "cnt"))
                     .addExpectedAggregatorFactory(new LongSumAggregatorFactory("cnt2", "cnt2"))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithGroupByLimitWithClusterBy(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = expectedFooRows();
    int expectedCounterRows = expectedRows.size();

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    testIngestQuery().setSql(
                         "insert into foo1 select  __time, dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2  limit 10 PARTITIONED by All clustered by 2,3")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(context)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedRows)
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         2, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         3, 0, "input0"
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithTimeFunction(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    testIngestQuery().setSql(
                         "insert into foo1 select  floor(__time to day) as __time , dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedFooRows())
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithTimeAggregator(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "INSERT INTO foo1 "
                         + "SELECT MILLIS_TO_TIMESTAMP((SUM(CAST(\"m1\" AS BIGINT)))) AS __time "
                         + "FROM foo "
                         + "PARTITIONED BY DAY"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo1", Intervals.of("1970-01-01/P1D"), "test", 0)
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{21L}
                         )
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithTimeAggregatorAndMultipleWorkers(String contextName, Map<String, Object> context)
  {
    Map<String, Object> localContext = new HashMap<>(context);
    localContext.put(MultiStageQueryContext.CTX_TASK_ASSIGNMENT_STRATEGY, WorkerAssignmentStrategy.MAX.name());
    localContext.put(MultiStageQueryContext.CTX_MAX_NUM_TASKS, 4);

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "INSERT INTO foo1 "
                         + "SELECT MILLIS_TO_TIMESTAMP((SUM(CAST(\"m1\" AS BIGINT)))) AS __time "
                         + "FROM foo "
                         + "PARTITIONED BY DAY"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(localContext)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo1", Intervals.of("1970-01-01/P1D"), "test", 0)
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{21L}
                         )
                     )
                     .verifyResults();
  }


  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithTimePostAggregator(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("sum_m1", ColumnType.DOUBLE)
                                            .build();

    testIngestQuery().setSql(
                         "INSERT INTO foo1 "
                         + "SELECT DATE_TRUNC('DAY', TIMESTAMP '2000-01-01' - INTERVAL '1'DAY) AS __time, SUM(m1) AS sum_m1 "
                         + "FROM foo "
                         + "PARTITIONED BY DAY"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo1", Intervals.of("1999-12-31T/P1D"), "test", 0)
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{946598400000L, 21.0}
                         )
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithTimeFunctionWithSequential(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = expectedFooRows();
    int expectedCounterRows = expectedRows.size();
    long[] expectedArray = createExpectedFrameArray(expectedCounterRows, 1);

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();
    Map<String, Object> newContext = ImmutableMap.<String, Object>builder()
                                              .putAll(DEFAULT_MSQ_CONTEXT)
                                              .put(
                                                  MultiStageQueryContext.CTX_CLUSTER_STATISTICS_MERGE_MODE,
                                                  ClusterStatisticsMergeMode.SEQUENTIAL.toString()
                                              )
                                              .build();

    testIngestQuery().setSql(
                         "insert into foo1 select  floor(__time to day) as __time , dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setQueryContext(newContext)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedRows)
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         2, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(Arrays.stream(expectedArray).sum()),
                         2, 0
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithMultiValueDim(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING).build();

    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT dim3 FROM foo WHERE dim3 IS NOT NULL PARTITIONED BY ALL TIME")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedMultiValueFooRows())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1MultiValueDimWithLimitWithoutClusterBy(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING).build();

    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT dim3 FROM foo WHERE dim3 IS NOT NULL limit 10 PARTITIONED BY ALL TIME")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedMultiValueFooRows())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1MultiValueDimWithLimitWithClusterBy(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING).build();

    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT dim3 FROM foo WHERE dim3 IS NOT NULL limit 10 PARTITIONED BY ALL TIME clustered by dim3")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedMultiValueFooRows())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithMultiValueDimGroupBy(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING).build();

    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT dim3 FROM foo WHERE dim3 IS NOT NULL GROUP BY 1 PARTITIONED BY ALL TIME")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedMultiValueFooRowsGroupBy())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithMultiValueMeasureGroupBy(String contextName, Map<String, Object> context)
  {
    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT count(dim3) FROM foo WHERE dim3 IS NOT NULL GROUP BY 1 PARTITIONED BY ALL TIME")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(context)
                     .setExpectedValidationErrorMatcher(
                         invalidSqlContains("Aggregate expression is illegal in GROUP BY clause")
                     )
                     .verifyPlanningErrors();
  }


  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithAutoTypeArrayGroupBy(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING_ARRAY).build();

    final Map<String, Object> adjustedContext = new HashMap<>(context);
    adjustedContext.put(MultiStageQueryContext.CTX_USE_AUTO_SCHEMAS, true);

    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT MV_TO_ARRAY(dim3) as dim3 FROM foo GROUP BY 1 PARTITIONED BY ALL TIME")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(adjustedContext)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{0L, null},
                             new Object[]{0L, new Object[]{"a", "b"}},
                             new Object[]{0L, new Object[]{""}},
                             new Object[]{0L, new Object[]{"b", "c"}},
                             new Object[]{0L, new Object[]{"d"}}
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithArrayIngestModeArrayGroupByInsertAsArray(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING_ARRAY).build();

    final Map<String, Object> adjustedContext = new HashMap<>(context);
    adjustedContext.put(MultiStageQueryContext.CTX_ARRAY_INGEST_MODE, "array");

    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT MV_TO_ARRAY(dim3) as dim3 FROM foo GROUP BY 1 PARTITIONED BY ALL TIME"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(adjustedContext)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{0L, null},
                             new Object[]{0L, new Object[]{"a", "b"}},
                             new Object[]{0L, new Object[]{""}},
                             new Object[]{0L, new Object[]{"b", "c"}},
                             new Object[]{0L, new Object[]{"d"}}
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithArrayIngestModeArrayGroupByInsertAsArraySetStatement(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING_ARRAY).build();

    testIngestQuery().setSql(
                         "SET arrayIngestMode = 'array'; INSERT INTO foo1 SELECT MV_TO_ARRAY(dim3) as dim3 FROM foo GROUP BY 1 PARTITIONED BY ALL TIME"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{0L, null},
                             new Object[]{0L, new Object[]{"a", "b"}},
                             new Object[]{0L, new Object[]{""}},
                             new Object[]{0L, new Object[]{"b", "c"}},
                             new Object[]{0L, new Object[]{"d"}}
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithArrayIngestModeArrayGroupByInsertAsMvd(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim3", ColumnType.STRING).build();

    final Map<String, Object> adjustedContext = new HashMap<>(context);
    adjustedContext.put(MultiStageQueryContext.CTX_ARRAY_INGEST_MODE, "array");

    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT ARRAY_TO_MV(MV_TO_ARRAY(dim3)) as dim3 FROM foo GROUP BY MV_TO_ARRAY(dim3) PARTITIONED BY ALL TIME"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(adjustedContext)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{0L, null},
                             new Object[]{0L, ""},
                             new Object[]{0L, Arrays.asList("a", "b")},
                             new Object[]{0L, Arrays.asList("b", "c")},
                             new Object[]{0L, "d"}
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithMultiValueDimGroupByWithoutGroupByEnable(String contextName, Map<String, Object> context)
  {
    Map<String, Object> localContext = ImmutableMap.<String, Object>builder()
                                                   .putAll(context)
                                                   .put("groupByEnableMultiValueUnnesting", false)
                                                   .build();


    testIngestQuery().setSql(
                         "INSERT INTO foo1 SELECT dim3, count(*) AS cnt1 FROM foo GROUP BY dim3 PARTITIONED BY ALL TIME")
                     .setQueryContext(localContext)
                     .setExpectedExecutionErrorMatcher(CoreMatchers.allOf(
                         CoreMatchers.instanceOf(ISE.class),
                         ThrowableMessageMatcher.hasMessage(CoreMatchers.containsString(
                             "Column [dim3] is a multi-value string. Please wrap the column using MV_TO_ARRAY() to proceed further.")
                         )
                     ))
                     .verifyExecutionError();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithMultiValueDimGroupByWithoutGroupByEnableSetStatement(String contextName, Map<String, Object> context)
  {
    testIngestQuery().setSql(
                         "SET groupByEnableMultiValueUnnesting = false; INSERT INTO foo1 SELECT dim3, count(*) AS cnt1 FROM foo GROUP BY dim3 PARTITIONED BY ALL TIME")
                     .setQueryContext(context)
                     .setExpectedExecutionErrorMatcher(CoreMatchers.allOf(
                         CoreMatchers.instanceOf(ISE.class),
                         ThrowableMessageMatcher.hasMessage(CoreMatchers.containsString(
                             "Column [dim3] is a multi-value string. Please wrap the column using MV_TO_ARRAY() to proceed further.")
                         )
                     ))
                     .verifyExecutionError();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testRollUpOnFoo1UpOnFoo1(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = expectedFooRows();
    int expectedCounterRows = expectedRows.size();
    long[] expectedArray = createExpectedFrameArray(expectedCounterRows, 1);

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    testIngestQuery().setSql(
                         "insert into foo1 select  __time, dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(new ImmutableMap.Builder<String, Object>().putAll(context)
                                                                                .putAll(ROLLUP_CONTEXT_PARAMS)
                                                                                .build())
                     .setExpectedRollUp(true)
                     .addExpectedAggregatorFactory(new LongSumAggregatorFactory("cnt", "cnt"))
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedRows)
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         2, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(Arrays.stream(expectedArray).sum()),
                         2, 0
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testRollUpOnFoo1WithTimeFunction(String contextName, Map<String, Object> context)
  {
    List<Object[]> expectedRows = expectedFooRows();
    int expectedCounterRows = expectedRows.size();
    long[] expectedArray = createExpectedFrameArray(expectedCounterRows, 1);

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    testIngestQuery().setSql(
                         "insert into foo1 select  floor(__time to day) as __time , dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(new ImmutableMap.Builder<String, Object>().putAll(context).putAll(
                         ROLLUP_CONTEXT_PARAMS).build())
                     .setExpectedRollUp(true)
                     .setExpectedQueryGranularity(Granularities.DAY)
                     .addExpectedAggregatorFactory(new LongSumAggregatorFactory("cnt", "cnt"))
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedRows)
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedCounterRows).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(expectedArray).frames(expectedArray),
                         2, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(Arrays.stream(expectedArray).sum()),
                         2, 0
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertWithClusteredByDescendingThrowsException(String contextName, Map<String, Object> context)
  {
    // Add a DESC clustered by column, which should not be allowed
    testIngestQuery().setSql("INSERT INTO foo1 "
                             + "SELECT __time, dim1 , count(*) as cnt "
                             + "FROM foo "
                             + "GROUP BY 1, 2"
                             + "PARTITIONED BY DAY "
                             + "CLUSTERED BY dim1 DESC"
                     )
                     .setExpectedValidationErrorMatcher(
                         invalidSqlIs("Invalid CLUSTERED BY clause [`dim1` DESC]: cannot sort in descending order.")
                     )
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testRollUpOnFoo1WithTimeFunctionComplexCol(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", new ColumnType(ValueType.COMPLEX, "hyperUnique", null))
                                            .build();


    testIngestQuery().setSql(
                         "insert into foo1 select  floor(__time to day) as __time , dim1 , count(distinct m1) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(new ImmutableMap.Builder<String, Object>().putAll(context).putAll(
                         ROLLUP_CONTEXT_PARAMS).build())
                     .setExpectedRollUp(true)
                     .setExpectedQueryGranularity(Granularities.DAY)
                     .addExpectedAggregatorFactory(new HyperUniquesAggregatorFactory("cnt", "cnt", false, true))
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedFooRowsWithAggregatedComplexColumn())
                     .verifyResults();

  }


  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testRollUpOnFoo1ComplexCol(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", new ColumnType(ValueType.COMPLEX, "hyperUnique", null))
                                            .build();

    testIngestQuery().setSql(
                         "insert into foo1 select  __time , dim1 , count(distinct m1) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(new ImmutableMap.Builder<String, Object>().putAll(context).putAll(
                         ROLLUP_CONTEXT_PARAMS).build())
                     .setExpectedRollUp(true)
                     .addExpectedAggregatorFactory(new HyperUniquesAggregatorFactory("cnt", "cnt", false, true))
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(expectedFooSegments())
                     .setExpectedResultRows(expectedFooRowsWithAggregatedComplexColumn())
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testRollUpOnExternalDataSource(String contextName, Map<String, Object> context) throws IOException
  {
    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(" insert into foo1 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                             + "  count(*) as cnt\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                             + "  )\n"
                             + ") group by 1  PARTITIONED by day ")
                     .setQueryContext(new ImmutableMap.Builder<String, Object>().putAll(context).putAll(
                         ROLLUP_CONTEXT_PARAMS).build())
                     .setExpectedRollUp(true)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .addExpectedAggregatorFactory(new LongSumAggregatorFactory("cnt", "cnt"))
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo1",
                         Intervals.of("2016-06-27/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(new Object[]{1466985600000L, 20L}))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(20).bytes(toRead.length()).files(1).totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(1).frames(1),
                         2, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(1),
                         2, 0
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testRollUpOnExternalDataSourceWithCompositeKey(String contextName, Map<String, Object> context) throws IOException
  {
    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("namespace", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(" insert into foo1 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                             + " namespace , count(*) as cnt\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"namespace\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                             + "  )\n"
                             + ") group by 1,2  PARTITIONED by day ")
                     .setQueryContext(new ImmutableMap.Builder<String, Object>().putAll(context).putAll(
                         ROLLUP_CONTEXT_PARAMS).build())
                     .setExpectedRollUp(true)
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .addExpectedAggregatorFactory(new LongSumAggregatorFactory("cnt", "cnt"))
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo1",
                         Intervals.of("2016-06-27/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(
                         new Object[]{1466985600000L, "Benutzer Diskussion", 2L},
                         new Object[]{1466985600000L, "File", 1L},
                         new Object[]{1466985600000L, "Kategoria", 1L},
                         new Object[]{1466985600000L, "Main", 14L},
                         new Object[]{1466985600000L, "Wikipedia", 1L},
                         new Object[]{1466985600000L, "Википедия", 1L}
                     ))
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(20).bytes(toRead.length()).files(1).totalFiles(1),
                         0, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(6).frames(1),
                         0, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(6).frames(1),
                         1, 0, "input0"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(6).frames(1),
                         1, 0, "shuffle"
                     )
                     .setExpectedCountersForStageWorkerChannel(
                         CounterSnapshotMatcher
                             .with().rows(6).frames(1),
                         2, 0, "input0"
                     )
                     .setExpectedSegmentGenerationProgressCountersForStageWorker(
                         CounterSnapshotMatcher
                             .with().segmentRowsProcessed(6),
                         2, 0
                     )
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertWrongTypeTimestamp(String contextName, Map<String, Object> context)
  {
    final RowSignature rowSignature =
        RowSignature.builder()
                    .add("__time", ColumnType.LONG)
                    .add("dim1", ColumnType.STRING)
                    .build();

    testIngestQuery()
        .setSql(
            "INSERT INTO foo1\n"
            + "SELECT dim1 AS __time, cnt\n"
            + "FROM foo\n"
            + "PARTITIONED BY DAY\n"
            + "CLUSTERED BY dim1")
        .setExpectedDataSource("foo1")
        .setExpectedRowSignature(rowSignature)
        .setQueryContext(context)
        .setExpectedValidationErrorMatcher(
            new DruidExceptionMatcher(
                DruidException.Persona.USER,
                DruidException.Category.INVALID_INPUT,
                "invalidInput"
            ).expectMessageIs("Field[__time] was the wrong type[VARCHAR], expected TIMESTAMP")
        )
        .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testIncorrectInsertQuery(String contextName, Map<String, Object> context)
  {
    testIngestQuery()
        .setSql(
            "insert into foo1 select  __time, dim1 , count(*) as cnt from foo  where dim1 is not null group by 1, 2 clustered by dim1"
        )
        .setExpectedValidationErrorMatcher(invalidSqlContains(
            "CLUSTERED BY found before PARTITIONED BY, CLUSTERED BY must come after the PARTITIONED BY clause"
        ))
        .verifyPlanningErrors();
  }


  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertRestrictedColumns(String contextName, Map<String, Object> context)
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("namespace", ColumnType.STRING)
                                            .add("__bucket", ColumnType.LONG)
                                            .build();


    testIngestQuery()
        .setSql(" insert into foo1 SELECT\n"
                + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                + " namespace, __bucket\n"
                + "FROM TABLE(\n"
                + "  EXTERN(\n"
                + "    '{ \"files\": [\"ignored\"],\"type\":\"local\"}',\n"
                + "    '{\"type\": \"json\"}',\n"
                + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"namespace\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}, {\"name\": \"__bucket\", \"type\": \"string\"}]'\n"
                + "  )\n"
                + ") PARTITIONED by day")
        .setExpectedDataSource("foo1")
        .setExpectedRowSignature(rowSignature)
        .setQueryContext(context)
        .setExpectedMSQFault(new ColumnNameRestrictedFault("__bucket"))
        .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertDuplicateColumnNames(String contextName, Map<String, Object> context)
  {
    testIngestQuery()
        .setSql(" insert into foo1 SELECT\n"
                + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                + " namespace,\n"
                + " \"user\" AS namespace\n"
                + "FROM TABLE(\n"
                + "  EXTERN(\n"
                + "    '{ \"files\": [\"ignored\"],\"type\":\"local\"}',\n"
                + "    '{\"type\": \"json\"}',\n"
                + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"namespace\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}, {\"name\": \"__bucket\", \"type\": \"string\"}]'\n"
                + "  )\n"
                + ") PARTITIONED by day")
        .setQueryContext(context)
        .setExpectedValidationErrorMatcher(
            invalidSqlIs("Duplicate field in SELECT: [namespace]")
        )
        .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertQueryWithInvalidSubtaskCount(String contextName, Map<String, Object> context)
  {
    Map<String, Object> localContext = new HashMap<>(context);
    localContext.put(MultiStageQueryContext.CTX_MAX_NUM_TASKS, 1);

    testIngestQuery().setSql(
                         "insert into foo1 select  __time, dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setQueryContext(localContext)
                     .setExpectedExecutionErrorMatcher(
                         new DruidExceptionMatcher(
                             DruidException.Persona.USER,
                             DruidException.Category.INVALID_INPUT,
                             "invalidInput"
                         ).expectMessageIs(
                             "MSQ context maxNumTasks [1] cannot be less than 2, since at least 1 controller "
                             + "and 1 worker is necessary"
                         )
                     )
                     .verifyExecutionError();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertWithTooLargeRowShouldThrowException(String contextName, Map<String, Object> context) throws IOException
  {
    final File toRead = getResourceAsTemporaryFile("/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    Mockito.doReturn(500).when(workerMemoryParameters).getFrameSize();

    testIngestQuery().setSql(" insert into foo1 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                             + "  count(*) as cnt\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + toReadFileNameAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                             + "  )\n"
                             + ") group by 1  PARTITIONED by day ")
                     .setExpectedDataSource("foo")
                     .setQueryContext(context)
                     .setExpectedMSQFault(new RowTooLargeFault(500))
                     .setExpectedExecutionErrorMatcher(CoreMatchers.allOf(
                         CoreMatchers.instanceOf(ISE.class),
                         ThrowableMessageMatcher.hasMessage(CoreMatchers.containsString(
                             "Row too large to add to frame"))
                     ))
                     .verifyExecutionError();
  }

  @Test
  public void testInsertWithTooManySegmentsInTimeChunk()
  {
    final Map<String, Object> context = ImmutableMap.<String, Object>builder()
                                                    .putAll(DEFAULT_MSQ_CONTEXT)
                                                    .put("maxNumSegments", 1)
                                                    .put("rowsPerSegment", 1)
                                                    .build();

    testIngestQuery().setSql("INSERT INTO foo"
                             + " SELECT TIME_PARSE(ts) AS __time, c1 "
                             + " FROM (VALUES('2023-01-01', 'day1_1'), ('2023-01-01', 'day1_2'), ('2023-02-01', 'day2')) AS t(ts, c1)"
                             + " PARTITIONED BY DAY")
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(RowSignature.builder().add("__time", ColumnType.LONG).build())
                     .setQueryContext(context)
                     .setExpectedMSQFault(
                         new TooManySegmentsInTimeChunkFault(
                             DateTimes.of("2023-01-01"),
                             2,
                             1,
                             Granularities.DAY
                         )
                     )
                     .verifyResults();

  }

  @Test
  public void testInsertWithMaxNumSegments()
  {
    final Map<String, Object> context = ImmutableMap.<String, Object>builder()
                                                    .putAll(DEFAULT_MSQ_CONTEXT)
                                                    .put("maxNumSegments", 2)
                                                    .put("rowsPerSegment", 1)
                                                    .build();

    final RowSignature expectedRowSignature = RowSignature.builder()
                                                          .add("__time", ColumnType.LONG)
                                                          .add("c1", ColumnType.STRING)
                                                          .build();
    // Ingest query should at most generate 2 segments per time chunk
    // i.e. 2 segments for the first time chunk and 1 segment for the last time chunk.
    testIngestQuery().setSql("INSERT INTO foo"
                             + " SELECT TIME_PARSE(ts) AS __time, c1 "
                             + " FROM (VALUES('2023-01-01', 'day1_1'), ('2023-01-01', 'day1_2'), ('2023-02-01', 'day2')) AS t(ts, c1)"
                             + " PARTITIONED BY DAY")
                     .setQueryContext(context)
                     .setExpectedDataSource("foo")
                     .setExpectedRowSignature(expectedRowSignature)
                     .setExpectedSegments(
                         ImmutableSet.of(
                             SegmentId.of("foo", Intervals.of("2023-01-01/P1D"), "test", 0),
                             SegmentId.of("foo", Intervals.of("2023-01-01/P1D"), "test", 1),
                             SegmentId.of("foo", Intervals.of("2023-02-01/P1D"), "test", 0)
                         )
                     )
                     .setExpectedResultRows(
                         ImmutableList.of(
                             new Object[]{1672531200000L, "day1_1"},
                             new Object[]{1672531200000L, "day1_2"},
                             new Object[]{1675209600000L, "day2"}
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertLimitWithPeriodGranularityThrowsException(String contextName, Map<String, Object> context)
  {
    testIngestQuery().setSql(" INSERT INTO foo "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "LIMIT 50 "
                             + "PARTITIONED BY MONTH")
                     .setExpectedValidationErrorMatcher(
                         invalidSqlContains(
                             "INSERT and REPLACE queries cannot have a LIMIT unless PARTITIONED BY is \"ALL\""
                         )
                     )
                     .setQueryContext(context)
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOffsetThrowsException(String contextName, Map<String, Object> context)
  {
    testIngestQuery().setSql(" INSERT INTO foo "
                             + "SELECT __time, m1 "
                             + "FROM foo "
                             + "LIMIT 50 "
                             + "OFFSET 10 "
                             + "PARTITIONED BY ALL TIME")
                     .setExpectedValidationErrorMatcher(
                         invalidSqlContains("INSERT and REPLACE queries cannot have an OFFSET")
                     )
                     .setQueryContext(context)
                     .verifyPlanningErrors();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1WithLimit(String contextName, Map<String, Object> context)
  {
    Map<String, Object> queryContext = ImmutableMap.<String, Object>builder()
                                                   .putAll(context)
                                                   .put(MultiStageQueryContext.CTX_ROWS_PER_SEGMENT, 2)
                                                   .build();

    List<Object[]> expectedRows = ImmutableList.of(
        new Object[]{946771200000L, "10.1", 1L},
        new Object[]{978307200000L, "1", 1L},
        new Object[]{946857600000L, "2", 1L},
        new Object[]{978480000000L, "abc", 1L}
    );

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "insert into foo1 select __time, dim1, cnt from foo where dim1 != '' limit 4 partitioned by all clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(queryContext)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0), SegmentId.of("foo1", Intervals.ETERNITY, "test", 1)))
                     .setExpectedResultRows(expectedRows)
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             NumberedShardSpec.class.getSimpleName(),
                             "Using NumberedShardSpec to generate segments since the query is inserting rows."
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnFoo1NoDimensionsWithLimit(String contextName, Map<String, Object> context)
  {
    Map<String, Object> queryContext = ImmutableMap.<String, Object>builder()
                                                   .putAll(context)
                                                   .put(MultiStageQueryContext.CTX_ROWS_PER_SEGMENT, 2)
                                                   .build();

    List<Object[]> expectedRows = ImmutableList.of(new Object[]{DateTimes.utc(0L).getMillis(), 5L});

    RowSignature rowSignature = RowSignature.builder()
                                            .addTimeColumn()
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery()
        .setSql("insert into foo1 select count(*) cnt from foo where dim1 != '' limit 4 partitioned by all")
        .setExpectedDataSource("foo1")
        .setQueryContext(queryContext)
        .setExpectedRowSignature(rowSignature)
        .setExpectedSegments(ImmutableSet.of(SegmentId.of("foo1", Intervals.ETERNITY, "test", 0)))
        .setExpectedResultRows(expectedRows)
        .setExpectedMSQSegmentReport(
            new MSQSegmentReport(
                NumberedShardSpec.class.getSimpleName(),
                "Using NumberedShardSpec to generate segments since the query is inserting rows."
            )
        )
        .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testInsertOnRestricted(String contextName, Map<String, Object> context)
  {
    // Set expected results based on query's end user
    boolean isSuperUser = context.get(MSQTaskQueryMaker.USER_KEY).equals(CalciteTests.TEST_SUPERUSER_NAME);
    List<Object[]> expectedRows = isSuperUser ? ImmutableList.of(
        new Object[]{978307200000L, 4.0f},
        new Object[]{978393600000L, 5.0f},
        new Object[]{978480000000L, 6.0f}
    ) : ImmutableList.of(new Object[]{978480000000L, 6.0f});
    // Set common expected results (not relevant to query's end user)
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("m1", ColumnType.FLOAT)
                                            .build();

    testIngestQuery().setSql(
                         "insert into restrictedDatasource_m1_is_6 select __time, m1 from restrictedDatasource_m1_is_6 where __time >= TIMESTAMP '2001-01-01' partitioned by all")
                     .setExpectedDataSource("restrictedDatasource_m1_is_6")
                     .setQueryContext(new HashMap<>(context))
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of("restrictedDatasource_m1_is_6", Intervals.ETERNITY, "test", 0)))
                     .setExpectedResultRows(expectedRows)
                     .setExpectedMSQSegmentReport(
                         new MSQSegmentReport(
                             NumberedShardSpec.class.getSimpleName(),
                             "Using NumberedShardSpec to generate segments since the query is inserting rows."
                         )
                     )
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testCorrectNumberOfWorkersUsedAutoModeWithoutBytesLimit(String contextName, Map<String, Object> context) throws IOException
  {
    Map<String, Object> localContext = new HashMap<>(context);
    localContext.put(MultiStageQueryContext.CTX_TASK_ASSIGNMENT_STRATEGY, WorkerAssignmentStrategy.AUTO.name());
    localContext.put(MultiStageQueryContext.CTX_MAX_NUM_TASKS, 4);

    final File toRead1 = getResourceAsTemporaryFile("/multipleFiles/wikipedia-sampled-1.json");
    final String toReadFileNameAsJson1 = queryFramework().queryJsonMapper().writeValueAsString(toRead1.getAbsolutePath());

    final File toRead2 = getResourceAsTemporaryFile("/multipleFiles/wikipedia-sampled-2.json");
    final String toReadFileNameAsJson2 = queryFramework().queryJsonMapper().writeValueAsString(toRead2.getAbsolutePath());

    final File toRead3 = getResourceAsTemporaryFile("/multipleFiles/wikipedia-sampled-3.json");
    final String toReadFileNameAsJson3 = queryFramework().queryJsonMapper().writeValueAsString(toRead3.getAbsolutePath());

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "insert into foo1 select "
                         + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                         + "  count(*) as cnt\n"
                         + "FROM TABLE(\n"
                         + "  EXTERN(\n"
                         + "    '{ \"files\": [" + toReadFileNameAsJson1 + "," + toReadFileNameAsJson2 + "," + toReadFileNameAsJson3 + "],\"type\":\"local\"}',\n"
                         + "    '{\"type\": \"json\"}',\n"
                         + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                         + "  )\n"
                         + ") group by 1  PARTITIONED by day ")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(localContext)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo1",
                         Intervals.of("2016-06-27/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(new Object[]{1466985600000L, 20L}))
                     .setExpectedWorkerCount(
                         ImmutableMap.of(
                             0, 1
                         ))
                     .verifyResults();

  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testCorrectNumberOfWorkersUsedAutoModeWithBytesLimit(String contextName, Map<String, Object> context) throws IOException
  {
    Map<String, Object> localContext = new HashMap<>(context);
    localContext.put(MultiStageQueryContext.CTX_TASK_ASSIGNMENT_STRATEGY, WorkerAssignmentStrategy.AUTO.name());
    localContext.put(MultiStageQueryContext.CTX_MAX_NUM_TASKS, 4);
    localContext.put(MultiStageQueryContext.CTX_MAX_INPUT_BYTES_PER_WORKER, 10);

    final File toRead1 = getResourceAsTemporaryFile("/multipleFiles/wikipedia-sampled-1.json");
    final String toReadFileNameAsJson1 = queryFramework().queryJsonMapper().writeValueAsString(toRead1.getAbsolutePath());

    final File toRead2 = getResourceAsTemporaryFile("/multipleFiles/wikipedia-sampled-2.json");
    final String toReadFileNameAsJson2 = queryFramework().queryJsonMapper().writeValueAsString(toRead2.getAbsolutePath());

    final File toRead3 = getResourceAsTemporaryFile("/multipleFiles/wikipedia-sampled-3.json");
    final String toReadFileNameAsJson3 = queryFramework().queryJsonMapper().writeValueAsString(toRead3.getAbsolutePath());

    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("cnt", ColumnType.LONG)
                                            .build();

    testIngestQuery().setSql(
                         "insert into foo1 select "
                         + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
                         + "  count(*) as cnt\n"
                         + "FROM TABLE(\n"
                         + "  EXTERN(\n"
                         + "    '{ \"files\": [" + toReadFileNameAsJson1 + "," + toReadFileNameAsJson2 + "," + toReadFileNameAsJson3 + "],\"type\":\"local\"}',\n"
                         + "    '{\"type\": \"json\"}',\n"
                         + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, {\"name\": \"page\", \"type\": \"string\"}, {\"name\": \"user\", \"type\": \"string\"}]'\n"
                         + "  )\n"
                         + ") group by 1  PARTITIONED by day ")
                     .setExpectedDataSource("foo1")
                     .setQueryContext(localContext)
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedSegments(ImmutableSet.of(SegmentId.of(
                         "foo1",
                         Intervals.of("2016-06-27/P1D"),
                         "test",
                         0
                     )))
                     .setExpectedResultRows(ImmutableList.of(new Object[]{1466985600000L, 20L}))
                     .setExpectedWorkerCount(
                         ImmutableMap.of(
                             0, 3
                         ))
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyInsertQuery(String contextName, Map<String, Object> context)
  {

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "INSERT INTO foo1 "
                         + " SELECT  __time, dim1 , count(*) AS cnt"
                         + " FROM foo WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY day"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedResultRows(ImmutableList.of())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyInsertQueryWithAllGranularity(String contextName, Map<String, Object> context)
  {

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "INSERT INTO foo1 "
                         + " SELECT  __time, dim1 , COUNT(*) AS cnt"
                         + " FROM foo WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " PARTITIONED BY ALL"
                         + " CLUSTERED BY dim1")
                     .setQueryContext(context)
                     .setExpectedResultRows(ImmutableList.of())
                     .verifyResults();
  }

  @MethodSource("data")
  @ParameterizedTest(name = "{index}:with context {0}")
  public void testEmptyInsertLimitQuery(String contextName, Map<String, Object> context)
  {

    // Insert with a condition which results in 0 rows being inserted -- do nothing.
    testIngestQuery().setSql(
                         "INSERT INTO foo1 "
                         + " SELECT  __time, dim1, COUNT(*) AS cnt"
                         + " FROM foo WHERE dim1 IS NOT NULL AND __time < TIMESTAMP '1971-01-01 00:00:00'"
                         + " GROUP BY 1, 2"
                         + " LIMIT 100"
                         + " PARTITIONED BY ALL"
                         + " CLUSTERED BY dim1"
                     )
                     .setQueryContext(context)
                     .setExpectedResultRows(ImmutableList.of())
                     .verifyResults();
  }

  private List<Object[]> expectedFooRows()
  {
    return Arrays.asList(
        new Object[]{946684800000L, "", 1L},
        new Object[]{946771200000L, "10.1", 1L},
        new Object[]{946857600000L, "2", 1L},
        new Object[]{978307200000L, "1", 1L},
        new Object[]{978393600000L, "def", 1L},
        new Object[]{978480000000L, "abc", 1L}
    );
  }

  private List<Object[]> expectedFooRowsWithAggregatedComplexColumn()
  {
    HyperLogLogCollector hyperLogLogCollector = HyperLogLogCollector.makeLatestCollector();
    hyperLogLogCollector.add(fn.hashInt(1).asBytes());
    return ImmutableList.of(
        new Object[]{946684800000L, "", hyperLogLogCollector.estimateCardinalityRound()},
        new Object[]{946771200000L, "10.1", hyperLogLogCollector.estimateCardinalityRound()},
        new Object[]{946857600000L, "2", hyperLogLogCollector.estimateCardinalityRound()},
        new Object[]{978307200000L, "1", hyperLogLogCollector.estimateCardinalityRound()},
        new Object[]{978393600000L, "def", hyperLogLogCollector.estimateCardinalityRound()},
        new Object[]{978480000000L, "abc", hyperLogLogCollector.estimateCardinalityRound()}
    );
  }

  private List<Object[]> expectedMultiValueFooRows()
  {
    return ImmutableList.of(
        new Object[]{0L, ""},
        new Object[]{0L, ImmutableList.of("a", "b")},
        new Object[]{0L, ImmutableList.of("b", "c")},
        new Object[]{0L, "d"}
    );
  }

  private List<Object[]> expectedMultiValueFooRowsGroupBy()
  {
    return ImmutableList.of(
        new Object[]{0L, ""},
        new Object[]{0L, "a"},
        new Object[]{0L, "b"},
        new Object[]{0L, "c"},
        new Object[]{0L, "d"}
    );
  }

  private Set<SegmentId> expectedFooSegments()
  {
    return new TreeSet<>(
        ImmutableSet.of(
            SegmentId.of("foo1", Intervals.of("2000-01-01T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2000-01-02T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2000-01-03T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2001-01-01T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2001-01-02T/P1D"), "test", 0),
            SegmentId.of("foo1", Intervals.of("2001-01-03T/P1D"), "test", 0)
        )
    );
  }
}
