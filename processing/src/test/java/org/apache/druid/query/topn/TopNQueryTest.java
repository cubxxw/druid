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

package org.apache.druid.query.topn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.aggregation.DoubleMaxAggregatorFactory;
import org.apache.druid.query.aggregation.DoubleMinAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.post.FieldAccessPostAggregator;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.dimension.ExtractionDimensionSpec;
import org.apache.druid.query.dimension.LegacyDimensionSpec;
import org.apache.druid.query.extraction.MapLookupExtractor;
import org.apache.druid.query.lookup.LookupExtractionFn;
import org.apache.druid.query.ordering.StringComparators;
import org.apache.druid.segment.CursorBuildSpec;
import org.apache.druid.segment.Cursors;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.VirtualColumns;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.virtual.ExpressionVirtualColumn;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.util.List;

public class TopNQueryTest extends InitializedNullHandlingTest
{
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private static final ObjectMapper JSON_MAPPER = TestHelper.makeJsonMapper();

  @Test
  public void testQuerySerialization() throws IOException
  {
    Query query = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension(QueryRunnerTestHelper.MARKET_DIMENSION)
        .metric(QueryRunnerTestHelper.INDEX_METRIC)
        .threshold(4)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
        .aggregators(
            Lists.newArrayList(
                Iterables.concat(
                    QueryRunnerTestHelper.COMMON_DOUBLE_AGGREGATORS,
                    Lists.newArrayList(
                        new DoubleMaxAggregatorFactory("maxIndex", "index"),
                        new DoubleMinAggregatorFactory("minIndex", "index")
                    )
                )
            )
        )
        .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
        .build();

    String json = JSON_MAPPER.writeValueAsString(query);
    Query serdeQuery = JSON_MAPPER.readValue(json, Query.class);

    Assert.assertEquals(query, serdeQuery);
  }


  @Test
  public void testQuerySerdeWithLookupExtractionFn() throws IOException
  {
    final TopNQuery expectedQuery = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension(
            new ExtractionDimensionSpec(
                QueryRunnerTestHelper.MARKET_DIMENSION,
                QueryRunnerTestHelper.MARKET_DIMENSION,
                new LookupExtractionFn(new MapLookupExtractor(ImmutableMap.of("foo", "bar"), false), true, null, false, false)
            )
        )
        .metric(new NumericTopNMetricSpec(QueryRunnerTestHelper.INDEX_METRIC))
        .threshold(2)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .aggregators(
            Lists.newArrayList(
                Iterables.concat(
                    QueryRunnerTestHelper.COMMON_DOUBLE_AGGREGATORS,
                    Lists.newArrayList(
                        new DoubleMaxAggregatorFactory("maxIndex", "index"),
                        new DoubleMinAggregatorFactory("minIndex", "index")
                    )
                )
            )
        )
        .build();
    final String str = JSON_MAPPER.writeValueAsString(expectedQuery);
    Assert.assertEquals(expectedQuery, JSON_MAPPER.readValue(str, TopNQuery.class));
  }

  @Test
  public void testQuerySerdeWithAlphaNumericTopNMetricSpec() throws IOException
  {
    TopNQuery expectedQuery = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension(new LegacyDimensionSpec(QueryRunnerTestHelper.MARKET_DIMENSION))
        .metric(new DimensionTopNMetricSpec(null, StringComparators.ALPHANUMERIC))
        .threshold(2)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .aggregators(QueryRunnerTestHelper.ROWS_COUNT)
        .build();
    String jsonQuery = "{\n"
                       + "  \"queryType\": \"topN\",\n"
                       + "  \"dataSource\": \"testing\",\n"
                       + "  \"dimension\": \"market\",\n"
                       + "  \"threshold\": 2,\n"
                       + "  \"metric\": {\n"
                       + "    \"type\": \"dimension\",\n"
                       + "    \"ordering\": \"alphanumeric\"\n"
                       + "   },\n"
                       + "  \"granularity\": \"all\",\n"
                       + "  \"aggregations\": [\n"
                       + "    {\n"
                       + "      \"type\": \"count\",\n"
                       + "      \"name\": \"rows\"\n"
                       + "    }\n"
                       + "  ],\n"
                       + "  \"intervals\": [\n"
                       + "    \"1970-01-01T00:00:00.000Z/2020-01-01T00:00:00.000Z\"\n"
                       + "  ]\n"
                       + "}";
    TopNQuery actualQuery = JSON_MAPPER.readValue(
        JSON_MAPPER.writeValueAsString(
            JSON_MAPPER.readValue(
                jsonQuery,
                TopNQuery.class
            )
        ), TopNQuery.class
    );
    Assert.assertEquals(expectedQuery, actualQuery);
  }

  @Test
  public void testQueryNullDimensionSpec() throws IOException
  {
    expectedException.expectMessage("dimensionSpec can't be null");

    Query query = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .metric(QueryRunnerTestHelper.INDEX_METRIC)
        .threshold(4)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
        .aggregators(
            Lists.newArrayList(
                Iterables.concat(
                    QueryRunnerTestHelper.COMMON_DOUBLE_AGGREGATORS,
                    Lists.newArrayList(
                        new DoubleMaxAggregatorFactory("maxIndex", "index"),
                        new DoubleMinAggregatorFactory("minIndex", "index")
                    )
                )
            )
        )
        .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
        .build();

    String json = JSON_MAPPER.writeValueAsString(query);
    JSON_MAPPER.readValue(json, Query.class);
  }

  @Test
  public void testQueryZeroThreshold() throws IOException
  {
    expectedException.expectMessage("Threshold cannot be equal to 0.");

    Query query = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .metric(QueryRunnerTestHelper.INDEX_METRIC)
        .dimension(new LegacyDimensionSpec(QueryRunnerTestHelper.MARKET_DIMENSION))
        .threshold(0)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
        .aggregators(
            Lists.newArrayList(
                Iterables.concat(
                    QueryRunnerTestHelper.COMMON_DOUBLE_AGGREGATORS,
                    Lists.newArrayList(
                        new DoubleMaxAggregatorFactory("maxIndex", "index"),
                        new DoubleMinAggregatorFactory("minIndex", "index")
                    )
                )
            )
        )
        .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
        .build();

    String json = JSON_MAPPER.writeValueAsString(query);
    JSON_MAPPER.readValue(json, Query.class);
  }

  @Test
  public void testQueryNullMetric() throws IOException
  {
    expectedException.expectMessage("must specify a metric");

    Query query = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension(new LegacyDimensionSpec(QueryRunnerTestHelper.MARKET_DIMENSION))
        .threshold(2)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC)
        .aggregators(
            Lists.newArrayList(
                Iterables.concat(
                    QueryRunnerTestHelper.COMMON_DOUBLE_AGGREGATORS,
                    Lists.newArrayList(
                        new DoubleMaxAggregatorFactory("maxIndex", "index"),
                        new DoubleMinAggregatorFactory("minIndex", "index")
                    )
                )
            )
        )
        .postAggregators(QueryRunnerTestHelper.ADD_ROWS_INDEX_CONSTANT)
        .build();

    String json = JSON_MAPPER.writeValueAsString(query);
    JSON_MAPPER.readValue(json, Query.class);
  }

  @Test
  public void testGetRequiredColumns()
  {
    final TopNQuery query = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .intervals(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .virtualColumns(new ExpressionVirtualColumn("v", "\"other\"", ColumnType.STRING, ExprMacroTable.nil()))
        .dimension(DefaultDimensionSpec.of("v"))
        .aggregators(QueryRunnerTestHelper.ROWS_COUNT, new LongSumAggregatorFactory("idx", "index"))
        .granularity(QueryRunnerTestHelper.DAY_GRAN)
        .postAggregators(ImmutableList.of(new FieldAccessPostAggregator("x", "idx")))
        .metric(new NumericTopNMetricSpec("idx"))
        .threshold(100)
        .build();

    Assert.assertEquals(ImmutableSet.of("__time", "other", "index"), query.getRequiredColumns());
  }

  @Test
  public void testAsCursorBuildSpecAllGranularity()
  {
    final VirtualColumns virtualColumns = VirtualColumns.create(
        new ExpressionVirtualColumn("v", "\"other\"", ColumnType.STRING, ExprMacroTable.nil())
    );
    final LongSumAggregatorFactory longSum = new LongSumAggregatorFactory("idx", "index");
    final TopNQuery query = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .intervals(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .virtualColumns(virtualColumns)
        .dimension(DefaultDimensionSpec.of("v"))
        .aggregators(QueryRunnerTestHelper.ROWS_COUNT, longSum)
        .granularity(Granularities.ALL)
        .postAggregators(ImmutableList.of(new FieldAccessPostAggregator("x", "idx")))
        .metric(new NumericTopNMetricSpec("idx"))
        .threshold(100)
        .build();

    final CursorBuildSpec buildSpec = TopNQueryEngine.makeCursorBuildSpec(query, null);
    Assert.assertEquals(QueryRunnerTestHelper.FIRST_TO_THIRD.getIntervals().get(0), buildSpec.getInterval());
    Assert.assertEquals(ImmutableList.of("v"), buildSpec.getGroupingColumns());
    Assert.assertEquals(ImmutableList.of(QueryRunnerTestHelper.ROWS_COUNT, longSum), buildSpec.getAggregators());
    Assert.assertEquals(virtualColumns, buildSpec.getVirtualColumns());
    Assert.assertEquals(List.of(), buildSpec.getPreferredOrdering());
  }

  @Test
  public void testAsCursorBuildSpecDayGranularity()
  {
    final VirtualColumns virtualColumns = VirtualColumns.create(
        new ExpressionVirtualColumn("v", "\"other\"", ColumnType.STRING, ExprMacroTable.nil())
    );
    final LongSumAggregatorFactory longSum = new LongSumAggregatorFactory("idx", "index");
    final TopNQuery query = new TopNQueryBuilder()
        .dataSource(QueryRunnerTestHelper.DATA_SOURCE)
        .intervals(QueryRunnerTestHelper.FIRST_TO_THIRD)
        .virtualColumns(virtualColumns)
        .dimension(DefaultDimensionSpec.of("v"))
        .aggregators(QueryRunnerTestHelper.ROWS_COUNT, longSum)
        .granularity(Granularities.DAY)
        .postAggregators(ImmutableList.of(new FieldAccessPostAggregator("x", "idx")))
        .metric(new NumericTopNMetricSpec("idx"))
        .threshold(100)
        .build();

    final CursorBuildSpec buildSpec = TopNQueryEngine.makeCursorBuildSpec(query, null);
    Assert.assertEquals(QueryRunnerTestHelper.FIRST_TO_THIRD.getIntervals().get(0), buildSpec.getInterval());
    Assert.assertEquals(
        ImmutableList.of(Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME, "v"),
        buildSpec.getGroupingColumns()
    );
    Assert.assertEquals(ImmutableList.of(QueryRunnerTestHelper.ROWS_COUNT, longSum), buildSpec.getAggregators());
    Assert.assertEquals(
        VirtualColumns.create(
            Granularities.toVirtualColumn(query.getGranularity(), Granularities.GRANULARITY_VIRTUAL_COLUMN_NAME),
            virtualColumns.getVirtualColumns()[0]
        ),
        buildSpec.getVirtualColumns()
    );
    Assert.assertEquals(Cursors.ascendingTimeOrder(), buildSpec.getPreferredOrdering());
  }
}
