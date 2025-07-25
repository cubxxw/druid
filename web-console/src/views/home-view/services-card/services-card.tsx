/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from 'react';

import { PluralPairIfNeeded } from '../../../components';
import { getConsoleViewIcon } from '../../../druid-models';
import type { Capabilities } from '../../../helpers';
import { useQueryManager } from '../../../hooks';
import { getApiArray, lookupBy, queryDruidSql } from '../../../utils';
import { HomeViewCard } from '../home-view-card/home-view-card';

export interface ServiceCounts {
  coordinator?: number;
  overlord?: number;
  router?: number;
  broker?: number;
  historical?: number;
  middle_manager?: number;
  peon?: number;
  indexer?: number;
}

export interface ServicesCardProps {
  capabilities: Capabilities;
}

export const ServicesCard = React.memo(function ServicesCard(props: ServicesCardProps) {
  const [serviceCountState] = useQueryManager<Capabilities, ServiceCounts>({
    processQuery: async (capabilities, cancelToken) => {
      if (capabilities.hasSql()) {
        const serviceCountsFromQuery = await queryDruidSql<{
          service_type: string;
          count: number;
        }>(
          {
            query: `SELECT server_type AS "service_type", COUNT(*) as "count" FROM sys.servers GROUP BY 1`,
          },
          cancelToken,
        );
        return lookupBy(
          serviceCountsFromQuery,
          x => x.service_type,
          x => x.count,
        );
      } else if (capabilities.hasCoordinatorAccess()) {
        const services = await getApiArray('/druid/coordinator/v1/servers?simple', cancelToken);

        const middleManager = capabilities.hasOverlordAccess()
          ? await getApiArray('/druid/indexer/v1/workers', cancelToken)
          : [];

        return {
          historical: services.filter((s: any) => s.type === 'historical').length,
          middle_manager: middleManager.length,
          peon: services.filter((s: any) => s.type === 'indexer-executor').length,
        };
      } else {
        throw new Error(`must have SQL or coordinator access`);
      }
    },
    initQuery: props.capabilities,
  });

  const serviceCounts = serviceCountState.data;
  return (
    <HomeViewCard
      className="services-card"
      href="#services"
      icon={getConsoleViewIcon('services')}
      title="Services"
      loading={serviceCountState.loading}
      error={serviceCountState.error}
    >
      <PluralPairIfNeeded
        firstCount={serviceCounts ? serviceCounts.overlord : 0}
        firstSingular="overlord"
        secondCount={serviceCounts ? serviceCounts.coordinator : 0}
        secondSingular="coordinator"
      />
      <PluralPairIfNeeded
        firstCount={serviceCounts ? serviceCounts.router : 0}
        firstSingular="router"
        secondCount={serviceCounts ? serviceCounts.broker : 0}
        secondSingular="broker"
      />
      <PluralPairIfNeeded
        firstCount={serviceCounts ? serviceCounts.historical : 0}
        firstSingular="historical"
        secondCount={serviceCounts ? serviceCounts.middle_manager : 0}
        secondSingular="middle manager"
      />
      <PluralPairIfNeeded
        firstCount={serviceCounts ? serviceCounts.peon : 0}
        firstSingular="peon"
        secondCount={serviceCounts ? serviceCounts.indexer : 0}
        secondSingular="indexer"
      />
    </HomeViewCard>
  );
});
