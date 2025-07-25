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

package org.apache.druid.segment.virtual;

public class VirtualColumnCacheHelper
{
  public static final byte CACHE_TYPE_ID_MAP = 0x00;
  public static final byte CACHE_TYPE_ID_EXPRESSION = 0x01;
  public static final byte CACHE_TYPE_ID_LIST_FILTERED = 0x02;
  public static final byte CACHE_TYPE_ID_LIST_FALLBACK = 0x03;
  public static final byte CACHE_TYPE_ID_REGEX_FILTERED = 0x04;
  public static final byte CACHE_TYPE_ID_PREFIX_FILTERED = 0x05;

  // Starting byte 0xFF is reserved for site-specific virtual columns.
  @SuppressWarnings("unused")
  public static final byte CACHE_TYPE_ID_USER_DEFINED = (byte) 0xFF;

  private VirtualColumnCacheHelper()
  {
    // No instantiation.
  }
}
