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

package org.apache.druid.sql.calcite.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rel.rules.ProjectMergeRule;
import org.apache.calcite.schema.Schema;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.security.PolicyModule;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.jackson.JacksonModule;
import org.apache.druid.math.expr.ExprMacroTable;
import org.apache.druid.segment.join.JoinableFactoryWrapper;
import org.apache.druid.server.QueryLifecycleFactory;
import org.apache.druid.server.security.AuthorizerMapper;
import org.apache.druid.server.security.ResourceType;
import org.apache.druid.sql.SqlStatementFactory;
import org.apache.druid.sql.calcite.aggregation.SqlAggregator;
import org.apache.druid.sql.calcite.expression.SqlOperatorConversion;
import org.apache.druid.sql.calcite.parser.DruidSqlParser;
import org.apache.druid.sql.calcite.rule.ExtensionCalciteRuleProvider;
import org.apache.druid.sql.calcite.run.NativeSqlEngine;
import org.apache.druid.sql.calcite.schema.DruidSchemaCatalog;
import org.apache.druid.sql.calcite.schema.DruidSchemaName;
import org.apache.druid.sql.calcite.schema.NamedSchema;
import org.apache.druid.sql.calcite.util.CalciteTestBase;
import org.easymock.EasyMock;
import org.easymock.EasyMockExtension;
import org.easymock.Mock;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.apache.calcite.plan.RelOptRule.any;
import static org.apache.calcite.plan.RelOptRule.operand;
import static org.apache.druid.sql.calcite.planner.CalciteRulesManager.BLOAT_PROPERTY;
import static org.apache.druid.sql.calcite.planner.CalciteRulesManager.DEFAULT_BLOAT;

@ExtendWith(EasyMockExtension.class)
public class CalcitePlannerModuleTest extends CalciteTestBase
{
  private static final String SCHEMA_1 = "SCHEMA_1";
  private static final String SCHEMA_2 = "SCHEMA_2";
  private static final String DRUID_SCHEMA_NAME = "DRUID_SCHEMA_NAME";
  private static final int BLOAT = 1200;

  @Mock
  private NamedSchema druidSchema1;
  @Mock
  private NamedSchema druidSchema2;
  @Mock
  private Schema schema1;
  @Mock
  private Schema schema2;
  @Mock
  private QueryLifecycleFactory queryLifecycleFactory;
  @Mock
  private ExprMacroTable macroTable;
  @Mock
  private JoinableFactoryWrapper joinableFactoryWrapper;
  @Mock
  private AuthorizerMapper authorizerMapper;
  @Mock
  private DruidSchemaCatalog rootSchema;

  private Set<SqlAggregator> aggregators;
  private Set<SqlOperatorConversion> operatorConversions;

  private CalcitePlannerModule target;
  private Injector injector;
  private RelOptRule customRule;

  @BeforeEach
  public void setUp()
  {

    EasyMock.expect(druidSchema1.getSchema()).andStubReturn(schema1);
    EasyMock.expect(druidSchema2.getSchema()).andStubReturn(schema2);
    EasyMock.expect(druidSchema1.getSchemaName()).andStubReturn(SCHEMA_1);
    EasyMock.expect(druidSchema2.getSchemaName()).andStubReturn(SCHEMA_2);
    EasyMock.expect(druidSchema1.getSchemaResourceType(EasyMock.anyString())).andStubReturn(ResourceType.DATASOURCE);
    EasyMock.expect(druidSchema2.getSchemaResourceType(EasyMock.anyString())).andStubReturn("test");
    EasyMock.replay(druidSchema1, druidSchema2);
    aggregators = ImmutableSet.of();
    operatorConversions = ImmutableSet.of();
    target = new CalcitePlannerModule();
    customRule = new RelOptRule(operand(LogicalTableScan.class, any()), "customRule")
    {
      @Override
      public void onMatch(RelOptRuleCall call)
      {
      }
    };
    injector = Guice.createInjector(
        new JacksonModule(),
        new PolicyModule(),
        binder -> {
          binder.bind(Validator.class).toInstance(Validation.buildDefaultValidatorFactory().getValidator());
          binder.bindScope(LazySingleton.class, Scopes.SINGLETON);
          binder.bind(QueryLifecycleFactory.class).toInstance(queryLifecycleFactory);
          binder.bind(ExprMacroTable.class).toInstance(macroTable);
          binder.bind(AuthorizerMapper.class).toInstance(authorizerMapper);
          binder.bind(String.class).annotatedWith(DruidSchemaName.class).toInstance(DRUID_SCHEMA_NAME);
          binder.bind(Key.get(new TypeLiteral<Set<SqlAggregator>>() {})).toInstance(aggregators);
          binder.bind(Key.get(new TypeLiteral<Set<SqlOperatorConversion>>() {})).toInstance(operatorConversions);
          binder.bind(DruidSchemaCatalog.class).toInstance(rootSchema);
          binder.bind(JoinableFactoryWrapper.class).toInstance(joinableFactoryWrapper);
          binder.bind(CatalogResolver.class).toInstance(CatalogResolver.NULL_RESOLVER);
        },
        target,
        binder -> {
          Multibinder.newSetBinder(binder, ExtensionCalciteRuleProvider.class)
                     .addBinding()
                     .toInstance(plannerContext -> customRule);
        }
    );
  }

  @Test
  public void testDruidOperatorTableIsInjectable()
  {
    DruidOperatorTable operatorTable = injector.getInstance(DruidOperatorTable.class);
    Assert.assertNotNull(operatorTable);

    // Should be a singleton.
    DruidOperatorTable other = injector.getInstance(DruidOperatorTable.class);
    Assert.assertSame(other, operatorTable);
  }

  @Test
  public void testPlannerFactoryIsInjectable()
  {
    PlannerFactory plannerFactory = injector.getInstance(PlannerFactory.class);
    Assert.assertNotNull(PlannerFactory.class);

    // Should be a singleton.
    PlannerFactory other = injector.getInstance(PlannerFactory.class);
    Assert.assertSame(other, plannerFactory);
  }

  @Test
  public void testPlannerConfigIsInjected()
  {
    PlannerConfig plannerConfig = injector.getInstance(PlannerConfig.class);
    Assert.assertNotNull(plannerConfig);
  }

  @Test
  public void testExtensionCalciteRule()
  {
    ObjectMapper mapper = new DefaultObjectMapper();
    PlannerToolbox toolbox = injector.getInstance(PlannerFactory.class);

    final String sql = "SELECT 1";
    PlannerContext context = PlannerContext.create(
        toolbox,
        sql,
        DruidSqlParser.parse(sql, false).getMainStatement(),
        new NativeSqlEngine(queryLifecycleFactory, mapper, (SqlStatementFactory) null),
        Collections.emptyMap(),
        null
    );

    boolean containsCustomRule = injector.getInstance(CalciteRulesManager.class)
                                         .druidConventionRuleSet(context)
                                         .contains(customRule);
    Assert.assertTrue(containsCustomRule);
  }

  @Test
  public void testConfigurableBloat()
  {
    ObjectMapper mapper = new DefaultObjectMapper();
    PlannerToolbox toolbox = injector.getInstance(PlannerFactory.class);

    final String sql = "SELECT 1";
    PlannerContext contextWithBloat = PlannerContext.create(
            toolbox,
            sql,
            DruidSqlParser.parse(sql, false).getMainStatement(),
            new NativeSqlEngine(queryLifecycleFactory, mapper, (SqlStatementFactory) null),
            Collections.singletonMap(BLOAT_PROPERTY, BLOAT),
            null
    );

    PlannerContext contextWithoutBloat = PlannerContext.create(
            toolbox,
            sql,
            DruidSqlParser.parse(sql, false).getMainStatement(),
            new NativeSqlEngine(queryLifecycleFactory, mapper, (SqlStatementFactory) null),
            Collections.emptyMap(),
            null
    );

    assertBloat(contextWithBloat, BLOAT);
    assertBloat(contextWithoutBloat, DEFAULT_BLOAT);
  }

  private void assertBloat(PlannerContext context, int expectedBloat)
  {
    Optional<ProjectMergeRule> firstProjectMergeRule = injector.getInstance(CalciteRulesManager.class).baseRuleSet(context).stream()
            .filter(rule -> rule instanceof ProjectMergeRule)
            .map(rule -> (ProjectMergeRule) rule)
            .findAny();
    Assert.assertTrue(firstProjectMergeRule.isPresent() && firstProjectMergeRule.get().config.bloat() == expectedBloat);
  }
}
