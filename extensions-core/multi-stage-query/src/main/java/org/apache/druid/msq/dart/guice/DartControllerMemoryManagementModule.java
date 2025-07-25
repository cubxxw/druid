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

package org.apache.druid.msq.dart.guice;

import com.google.inject.Binder;
import com.google.inject.Provides;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.guice.annotations.LoadScope;
import org.apache.druid.initialization.DruidModule;
import org.apache.druid.msq.exec.MemoryIntrospector;
import org.apache.druid.msq.exec.MemoryIntrospectorImpl;
import org.apache.druid.query.DruidProcessingConfig;
import org.apache.druid.utils.RuntimeInfo;

/**
 * Memory management module for Brokers.
 */
@LoadScope(roles = {NodeRole.BROKER_JSON_NAME})
public class DartControllerMemoryManagementModule implements DruidModule
{
  @Override
  public void configure(Binder binder)
  {
    // Nothing to do.
  }

  @Provides
  public MemoryIntrospector createMemoryIntrospector(
      final RuntimeInfo runtimeInfo,
      final DruidProcessingConfig processingConfig,
      final DartControllerConfig controllerConfig
  )
  {
    return new MemoryIntrospectorImpl(
        runtimeInfo.getMaxHeapSizeBytes(),
        controllerConfig.getHeapFraction(),
        controllerConfig.getConcurrentQueries(),
        processingConfig.getNumThreads(),
        null
    );
  }
}
