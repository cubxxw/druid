---
id: datasketches-quantiles
title: "DataSketches Quantiles Sketch module"
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->


This module provides Apache Druid aggregators based on numeric quantiles DoublesSketch from [Apache DataSketches](https://datasketches.apache.org/) library. Quantiles sketch is a mergeable streaming algorithm to estimate the distribution of values, and approximately answer queries about the rank of a value, probability mass function of the distribution (PMF) or histogram, cumulative distribution function (CDF), and quantiles (median, min, max, 95th percentile and such). See [Quantiles Sketch Overview](https://datasketches.apache.org/docs/Quantiles/QuantilesSketchOverview.html).

There are three major modes of operation:

1. Ingesting sketches built outside of Druid (say, with Pig or Hive)
2. Building sketches from raw data during ingestion
3. Building sketches from raw data at query time

To use this aggregator, make sure you [include](../../configuration/extensions.md#loading-extensions) the extension in your config file:

```
druid.extensions.loadList=["druid-datasketches"]
```

For additional sketch types supported in Druid, see [DataSketches extension](datasketches-extension.md).

## Aggregator

The result of the aggregation is a DoublesSketch that is the union of all sketches either built from raw data or read from the segments.

```json
{
  "type" : "quantilesDoublesSketch",
  "name" : <output_name>,
  "fieldName" : <metric_name>,
  "k": <parameter that controls size and accuracy>
 }
```

|Property|Description|Required?|
|--------|-----------|---------|
|`type`|This string should always be "quantilesDoublesSketch"|yes|
|`name`|String representing the output column to store sketch values.|yes|
|`fieldName`|A string for the name of the input field (can contain sketches or raw numeric values).|yes|
|`k`|Parameter that determines the accuracy and size of the sketch. Higher k means higher accuracy but more space to store sketches. Must be a power of 2 from 2 to 32768. See [accuracy information](https://datasketches.apache.org/docs/Quantiles/ClassicQuantilesSketch.html#accuracy-and-size) in the DataSketches documentation for details.|no, defaults to 128|
|`maxStreamLength`|This parameter defines the number of items that can be presented to each sketch before it may need to move from off-heap to on-heap memory. This is relevant to query types that use off-heap memory, including [TopN](../../querying/topnquery.md) and [GroupBy](../../querying/groupbyquery.md). Ideally, should be set high enough such that most sketches can stay off-heap.|no, defaults to 1000000000|
|`shouldFinalize`|Return the final double type representing the estimate rather than the intermediate sketch type itself. In addition to controlling the finalization of this aggregator, you can control whether all aggregators are finalized with the query context parameters [`finalize`](../../querying/query-context-reference.md) and [`sqlFinalizeOuterSketches`](../../querying/sql-query-context.md).|no, defaults to `true`|

## Post aggregators

### Quantile

This returns an approximation to the value that would be preceded by a given fraction of a hypothetical sorted version of the input stream.

```json
{
  "type"  : "quantilesDoublesSketchToQuantile",
  "name": <output name>,
  "field"  : <post aggregator that refers to a DoublesSketch (fieldAccess or another post aggregator)>,
  "fraction" : <fractional position in the hypothetical sorted stream, number from 0 to 1 inclusive>
}
```

### Quantiles

This returns an array of quantiles corresponding to a given array of fractions

```json
{
  "type"  : "quantilesDoublesSketchToQuantiles",
  "name": <output name>,
  "field"  : <post aggregator that refers to a DoublesSketch (fieldAccess or another post aggregator)>,
  "fractions" : <array of fractional positions in the hypothetical sorted stream, number from 0 to 1 inclusive>
}
```

### Histogram

This returns an approximation to the histogram given an array of split points that define the histogram bins or a number of bins (not both). An array of <i>m</i> unique, monotonically increasing split points divide the real number line into <i>m+1</i> consecutive disjoint intervals. The definition of an interval is inclusive of the left split point and exclusive of the right split point. If the number of bins is specified instead of split points, the interval between the minimum and maximum values is divided into the given number of equally-spaced bins.

```json
{
  "type"  : "quantilesDoublesSketchToHistogram",
  "name": <output name>,
  "field"  : <post aggregator that refers to a DoublesSketch (fieldAccess or another post aggregator)>,
  "splitPoints" : <array of split points (optional)>,
  "numBins" : <number of bins (optional, defaults to 10)>
}
```

### Rank

This returns an approximation to the rank of a given value that is the fraction of the distribution less than that value.

```json
{
  "type"  : "quantilesDoublesSketchToRank",
  "name": <output name>,
  "field"  : <post aggregator that refers to a DoublesSketch (fieldAccess or another post aggregator)>,
  "value" : <value>
}
```
### CDF

This returns an approximation to the Cumulative Distribution Function given an array of split points that define the edges of the bins. An array of <i>m</i> unique, monotonically increasing split points divide the real number line into <i>m+1</i> consecutive disjoint intervals. The definition of an interval is inclusive of the left split point and exclusive of the right split point. The resulting array of fractions can be viewed as ranks of each split point with one additional rank that is always 1.

```json
{
  "type"  : "quantilesDoublesSketchToCDF",
  "name": <output name>,
  "field"  : <post aggregator that refers to a DoublesSketch (fieldAccess or another post aggregator)>,
  "splitPoints" : <array of split points>
}
```

### Sketch summary

This returns a summary of the sketch that can be used for debugging. This is the result of calling toString() method.

```json
{
  "type"  : "quantilesDoublesSketchToString",
  "name": <output name>,
  "field"  : <post aggregator that refers to a DoublesSketch (fieldAccess or another post aggregator)>
}
```
