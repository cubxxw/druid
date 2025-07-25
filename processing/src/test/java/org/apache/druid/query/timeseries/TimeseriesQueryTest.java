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

package org.apache.druid.query.timeseries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.Druids;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.segment.CursorBuildSpec;
import org.apache.druid.segment.Cursors;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

@RunWith(Parameterized.class)
public class TimeseriesQueryTest extends InitializedNullHandlingTest
{
  private static final ObjectMapper JSON_MAPPER = TestHelper.makeJsonMapper();

  @Parameterized.Parameters(name = "descending={0}")
  public static Iterable<Object[]> constructorFeeder()
  {
    return QueryRunnerTestHelper.cartesian(Arrays.asList(false, true));
  }

  private final boolean descending;

  public TimeseriesQueryTest(boolean descending)
  {
    this.descending = descending;
  }

  @Test
  public void testQuerySerialization() throws IOException
  {
    Query query = Druids.newTimeseriesQueryBuilder()
                        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
                        .granularity(QueryRunnerTestHelper.DAY_GRAN)
                        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
                        .aggregators(QueryRunnerTestHelper.ROWS_COUNT, QueryRunnerTestHelper.INDEX_DOUBLE_SUM)
                        .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
                        .descending(descending)
                        .build();

    String json = JSON_MAPPER.writeValueAsString(query);
    Query serdeQuery = JSON_MAPPER.readValue(json, Query.class);

    Assert.assertEquals(query, serdeQuery);
  }

  @Test
  public void testGetRequiredColumns()
  {
    final TimeseriesQuery query =
        Druids.newTimeseriesQueryBuilder()
              .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
              .granularity(QueryRunnerTestHelper.DAY_GRAN)
              .virtualColumns(
                  new ExpressionVirtualColumn(
                      "index",
                      "\"fieldFromVirtualColumn\"",
                      ColumnType.LONG,
                      ExprMacroTable.nil()
                  )
              )
              .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
              .aggregators(
                  QueryRunnerTestHelper.ROWS_COUNT,
                  QueryRunnerTestHelper.INDEX_DOUBLE_SUM,
                  QueryRunnerTestHelper.INDEX_LONG_MAX,
                  new LongSumAggregatorFactory("beep", "aField")
              )
              .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
              .descending(descending)
              .build();

    Assert.assertEquals(ImmutableSet.of("__time", "fieldFromVirtualColumn", "aField"), query.getRequiredColumns());
  }

  @Test
  public void testAsCursorBuildSpecAllGranularity()
  {
    final VirtualColumns virtualColumns = VirtualColumns.create(
        new ExpressionVirtualColumn(
            "index",
            "\"fieldFromVirtualColumn\"",
            ColumnType.LONG,
            ExprMacroTable.nil()
        )
    );
    final LongSumAggregatorFactory beep = new LongSumAggregatorFactory("beep", "aField");
    final TimeseriesQuery query =
        Druids.newTimeseriesQueryBuilder()
              .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
              .granularity(Granularities.ALL)
              .virtualColumns(virtualColumns)
              .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
              .aggregators(
                  QueryRunnerTestHelper.ROWS_COUNT,
                  QueryRunnerTestHelper.INDEX_DOUBLE_SUM,
                  QueryRunnerTestHelper.INDEX_LONG_MAX,
                  beep
              )
              .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
              .descending(descending)
              .build();

    final CursorBuildSpec buildSpec = TimeseriesQueryEngine.makeCursorBuildSpec(query, null);
    Assert.assertEquals(QueryRunnerTestHelper.FULL_ON_INTERVAL, buildSpec.getInterval());
    Assert.assertNull(buildSpec.getGroupingColumns());
    Assert.assertEquals(
        ImmutableList.of(
            QueryRunnerTestHelper.ROWS_COUNT,
            QueryRunnerTestHelper.INDEX_DOUBLE_SUM,
            QueryRunnerTestHelper.INDEX_LONG_MAX,
            beep
        ),
        buildSpec.getAggregators()
    );
    Assert.assertEquals(virtualColumns, buildSpec.getVirtualColumns());
    Assert.assertTrue(buildSpec.getPreferredOrdering().isEmpty());
  }

  @Test
  public void testAsCursorBuildSpecDayGranularity()
  {
    final VirtualColumns virtualColumns = VirtualColumns.create(
        new ExpressionVirtualColumn(
            "index",
            "\"fieldFromVirtualColumn\"",
            ColumnType.LONG,
            ExprMacroTable.nil()
        )
    );
    final LongSumAggregatorFactory beep = new LongSumAggregatorFactory("beep", "aField");
    final TimeseriesQuery query =
        Druids.newTimeseriesQueryBuilder()
              .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
              .granularity(Granularities.DAY)
              .virtualColumns(virtualColumns)
              .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
              .aggregators(
                  QueryRunnerTestHelper.ROWS_COUNT,
                  QueryRunnerTestHelper.INDEX_DOUBLE_SUM,
                  QueryRunnerTestHelper.INDEX_LONG_MAX,
                  beep
              )
              .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
              .descending(descending)
              .build();

    final CursorBuildSpec buildSpec = TimeseriesQueryEngine.makeCursorBuildSpec(query, null);
    Assert.assertEquals(QueryRunnerTestHelper.FULL_ON_INTERVAL, buildSpec.getInterval());
    Assert.assertEquals(
        Collections.singletonList(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
        buildSpec.getGroupingColumns()
    );
    Assert.assertEquals(
        ImmutableList.of(
            QueryRunnerTestHelper.ROWS_COUNT,
            QueryRunnerTestHelper.INDEX_DOUBLE_SUM,
            QueryRunnerTestHelper.INDEX_LONG_MAX,
            beep
        ),
        buildSpec.getAggregators()
    );
    Assert.assertEquals(
        VirtualColumns.create(
            Granularities.toVirtualColumn(query.getGranularity(), Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
            virtualColumns.getVirtualColumns()[0]
        ),
        buildSpec.getVirtualColumns()
    );
    Assert.assertEquals(
        descending ? Cursors.descendingTimeOrder() : Cursors.ascendingTimeOrder(),
        buildSpec.getPreferredOrdering()
    );
  }
}
