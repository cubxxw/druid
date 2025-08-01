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

package org.apache.druid.client.cache;

import com.google.inject.Inject;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.guice.annotations.LoadScope;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.java.util.emitter.service.ServiceMetricEvent;
import org.apache.druid.java.util.metrics.AbstractMonitor;

@LoadScope(roles = {
    NodeRole.BROKER_JSON_NAME,
    NodeRole.HISTORICAL_JSON_NAME,
    NodeRole.INDEXER_JSON_NAME,
    NodeRole.PEON_JSON_NAME
})
public class CacheMonitor extends AbstractMonitor
{
  // package private for tests
  volatile Cache cache;

  private final CachePopulatorStats cachePopulatorStats;
  private volatile CacheStats prevCacheStats = null;
  private volatile CachePopulatorStats.Snapshot prevCachePopulatorStats = null;

  @Inject
  public CacheMonitor(final CachePopulatorStats cachePopulatorStats)
  {
    this.cachePopulatorStats = cachePopulatorStats;
  }

  // make it possible to enable CacheMonitor even if cache is not bound
  // (e.g. some index tasks may have a cache, others may not)
  @Inject(optional = true)
  public void setCache(Cache cache)
  {
    this.cache = cache;
  }

  @Override
  public boolean doMonitor(ServiceEmitter emitter)
  {
    if (cache != null) {
      final CacheStats currCacheStats = cache.getStats();
      final CacheStats deltaCacheStats = currCacheStats.delta(prevCacheStats);

      final CachePopulatorStats.Snapshot currCachePopulatorStats = cachePopulatorStats.snapshot();
      final CachePopulatorStats.Snapshot deltaCachePopulatorStats = currCachePopulatorStats.delta(
          prevCachePopulatorStats
      );

      final ServiceMetricEvent.Builder builder = new ServiceMetricEvent.Builder();
      emitStats(emitter, "query/cache/delta", deltaCachePopulatorStats, deltaCacheStats, builder);
      emitStats(emitter, "query/cache/total", currCachePopulatorStats, currCacheStats, builder);

      prevCachePopulatorStats = currCachePopulatorStats;
      prevCacheStats = currCacheStats;

      // Any custom cache statistics that need monitoring
      cache.doMonitor(emitter);
    }
    return true;
  }

  private void emitStats(
      final ServiceEmitter emitter,
      final String metricPrefix,
      final CachePopulatorStats.Snapshot cachePopulatorStats,
      final CacheStats cacheStats,
      final ServiceMetricEvent.Builder builder
  )
  {
    if (cache != null) {
      // Cache stats.
      emitter.emit(builder.setMetric(StringUtils.format("%s/numEntries", metricPrefix), cacheStats.getNumEntries()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/sizeBytes", metricPrefix), cacheStats.getSizeInBytes()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/hits", metricPrefix), cacheStats.getNumHits()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/misses", metricPrefix), cacheStats.getNumMisses()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/evictions", metricPrefix), cacheStats.getNumEvictions()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/hitRate", metricPrefix), cacheStats.hitRate()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/averageBytes", metricPrefix), cacheStats.averageBytes()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/timeouts", metricPrefix), cacheStats.getNumTimeouts()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/errors", metricPrefix), cacheStats.getNumErrors()));

      // Cache populator stats.
      emitter.emit(builder.setMetric(StringUtils.format("%s/put/ok", metricPrefix), cachePopulatorStats.getNumOk()));
      emitter.emit(builder.setMetric(StringUtils.format("%s/put/error", metricPrefix), cachePopulatorStats.getNumError()));
      emitter.emit(
          builder.setMetric(StringUtils.format("%s/put/oversized", metricPrefix), cachePopulatorStats.getNumOversized())
      );
    }
  }
}
