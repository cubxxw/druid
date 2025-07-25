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

package org.apache.druid.indexing.overlord;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.ByteSink;
import com.google.common.io.ByteStreams;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import com.google.common.math.IntMath;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Inject;
import org.apache.druid.guice.annotations.Self;
import org.apache.druid.indexer.RunnerTaskState;
import org.apache.druid.indexer.TaskLocation;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.TaskStorageDirTracker;
import org.apache.druid.indexing.common.config.TaskConfig;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.common.tasklogs.ConsoleLoggingEnforcementConfigurationFactory;
import org.apache.druid.indexing.common.tasklogs.LogUtils;
import org.apache.druid.indexing.overlord.autoscaling.ScalingStats;
import org.apache.druid.indexing.overlord.config.ForkingTaskRunnerConfig;
import org.apache.druid.indexing.worker.config.WorkerConfig;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.query.DruidMetrics;
import org.apache.druid.server.DruidNode;
import org.apache.druid.server.log.StartupLoggingConfig;
import org.apache.druid.server.metrics.MonitorsConfig;
import org.apache.druid.server.metrics.WorkerTaskCountStatsProvider;
import org.apache.druid.tasklogs.TaskLogPusher;
import org.apache.druid.tasklogs.TaskLogStreamer;
import org.apache.druid.utils.JvmUtils;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.RoundingMode;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs tasks in separate processes using the "internal peon" verb.
 */
public class ForkingTaskRunner
    extends BaseRestorableTaskRunner<ForkingTaskRunner.ForkingTaskRunnerWorkItem>
    implements TaskLogStreamer, WorkerTaskCountStatsProvider
{
  private static final EmittingLogger LOGGER = new EmittingLogger(ForkingTaskRunner.class);
  private static final String CHILD_PROPERTY_PREFIX = "druid.indexer.fork.property.";

  /**
   * Properties to add on Java 11+. When updating this list, update all four:
   *  1) ForkingTaskRunner#STRONG_ENCAPSULATION_PROPERTIES (here) -->
   *  2) docs/operations/java.md, "Strong encapsulation" section -->
   *  3) pom.xml, jdk.strong.encapsulation.argLine -->
   *  4) examples/bin/run-java script
   */
  private static final List<String> STRONG_ENCAPSULATION_PROPERTIES = ImmutableList.of(
      "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
      "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED"
  );

  private final ForkingTaskRunnerConfig config;
  private final Properties props;
  private final TaskLogPusher taskLogPusher;
  private final DruidNode node;
  private final ListeningExecutorService exec;
  private final PortFinder portFinder;
  private final StartupLoggingConfig startupLoggingConfig;
  private final WorkerConfig workerConfig;

  private volatile int numProcessorsPerTask = -1;
  private volatile boolean stopping = false;

  private final AtomicLong lastReportedFailedTaskCount = new AtomicLong();
  private final AtomicLong failedTaskCount = new AtomicLong();
  private final AtomicLong successfulTaskCount = new AtomicLong();
  private final AtomicLong lastReportedSuccessfulTaskCount = new AtomicLong();

  @Inject
  public ForkingTaskRunner(
      ForkingTaskRunnerConfig config,
      TaskConfig taskConfig,
      WorkerConfig workerConfig,
      Properties props,
      TaskLogPusher taskLogPusher,
      ObjectMapper jsonMapper,
      @Self DruidNode node,
      StartupLoggingConfig startupLoggingConfig,
      TaskStorageDirTracker dirTracker
  )
  {
    super(jsonMapper, taskConfig, dirTracker);
    this.config = config;
    this.props = props;
    this.taskLogPusher = taskLogPusher;
    this.node = node;
    this.portFinder = new PortFinder(config.getStartPort(), config.getEndPort(), config.getPorts());
    this.startupLoggingConfig = startupLoggingConfig;
    this.workerConfig = workerConfig;
    this.exec = MoreExecutors.listeningDecorator(
        Execs.multiThreaded(workerConfig.getCapacity(), "forking-task-runner-%d")
    );
  }

  @Override
  public ListenableFuture<TaskStatus> run(final Task task)
  {
    synchronized (tasks) {
      tasks.computeIfAbsent(
          task.getId(), k ->
          new ForkingTaskRunnerWorkItem(
            task,
            exec.submit(
              new Callable<>() {
                @Override
                public TaskStatus call()
                {
                  final TaskStorageDirTracker.StorageSlot storageSlot;
                  try {
                    storageSlot = getTracker().pickStorageSlot(task.getId());
                  }
                  catch (RuntimeException e) {
                    LOG.warn(e, "Failed to get storage slot for task [%s], cannot schedule.", task.getId());
                    return TaskStatus.failure(
                        task.getId(),
                        StringUtils.format("Failed to get storage slot due to error [%s]", e.getMessage())
                    );
                  }

                  final File taskDir = new File(storageSlot.getDirectory(), task.getId());
                  final String attemptId = String.valueOf(getNextAttemptID(taskDir));
                  final File attemptDir = Paths.get(taskDir.getAbsolutePath(), "attempt", attemptId).toFile();

                  final ProcessHolder processHolder;
                  final String childHost = node.getHost();
                  int childPort = -1;
                  int tlsChildPort = -1;

                  if (node.isEnablePlaintextPort()) {
                    childPort = portFinder.findUnusedPort();
                  }

                  if (node.isEnableTlsPort()) {
                    tlsChildPort = portFinder.findUnusedPort();
                  }

                  final TaskLocation taskLocation = TaskLocation.create(childHost, childPort, tlsChildPort);

                  try {
                    final Closer closer = Closer.create();
                    try {
                      final File taskFile = new File(taskDir, "task.json");
                      final File statusFile = new File(attemptDir, "status.json");
                      final File logFile = new File(taskDir, "log");
                      final File reportsFile = new File(attemptDir, "report.json");

                      // time to adjust process holders
                      synchronized (tasks) {
                        final ForkingTaskRunnerWorkItem taskWorkItem = tasks.get(task.getId());

                        if (taskWorkItem == null) {
                          LOGGER.makeAlert("TaskInfo disappeared!").addData("task", task.getId()).emit();
                          throw new ISE("TaskInfo disappeared for task[%s]!", task.getId());
                        }

                        if (taskWorkItem.shutdown) {
                          throw new IllegalStateException("Task has been shut down!");
                        }

                        if (taskWorkItem.processHolder != null) {
                          LOGGER.makeAlert("TaskInfo already has a processHolder")
                                .addData("task", task.getId())
                                .emit();
                          throw new ISE("TaskInfo already has processHolder for task[%s]!", task.getId());
                        }

                        final CommandListBuilder command = new CommandListBuilder();
                        final String taskClasspath;
                        if (task.getClasspathPrefix() != null && !task.getClasspathPrefix().isEmpty()) {
                          taskClasspath = Joiner.on(File.pathSeparator).join(
                            task.getClasspathPrefix(),
                            config.getClasspath()
                          );
                        } else {
                          taskClasspath = config.getClasspath();
                        }

                        command.add(config.getJavaCommand());

                        if (JvmUtils.majorVersion() >= 11) {
                          command.addAll(STRONG_ENCAPSULATION_PROPERTIES);
                        }

                        command.add("-cp");
                        command.add(taskClasspath);

                        if (numProcessorsPerTask < 1) {
                          // numProcessorsPerTask is set by start()
                          throw new ISE("Not started");
                        }

                        command.add(StringUtils.format("-XX:ActiveProcessorCount=%d", numProcessorsPerTask));

                        command.addAll(new QuotableWhiteSpaceSplitter(config.getJavaOpts()));
                        command.addAll(config.getJavaOptsArray());

                        // Override task specific javaOpts
                        Object taskJavaOpts = task.getContextValue(
                            ForkingTaskRunnerConfig.JAVA_OPTS_PROPERTY
                        );
                        if (taskJavaOpts != null) {
                          command.addAll(new QuotableWhiteSpaceSplitter((String) taskJavaOpts));
                        }

                        // Override task specific javaOptsArray
                        try {
                          List<String> taskJavaOptsArray = jsonMapper.convertValue(
                              task.getContextValue(ForkingTaskRunnerConfig.JAVA_OPTS_ARRAY_PROPERTY),
                              new TypeReference<>() {}
                          );
                          if (taskJavaOptsArray != null) {
                            command.addAll(taskJavaOptsArray);
                          }
                        }
                        catch (Exception e) {
                          throw new IllegalArgumentException(
                              ForkingTaskRunnerConfig.JAVA_OPTS_ARRAY_PROPERTY
                              + " in context of task: " + task.getId() + " must be an array of strings.",
                              e
                          );
                        }

                        for (String propName : props.stringPropertyNames()) {
                          for (String allowedPrefix : config.getAllowedPrefixes()) {
                            // See https://github.com/apache/druid/issues/1841
                            if (propName.startsWith(allowedPrefix)
                                && !ForkingTaskRunnerConfig.JAVA_OPTS_PROPERTY.equals(propName)
                                && !ForkingTaskRunnerConfig.JAVA_OPTS_ARRAY_PROPERTY.equals(propName)
                            ) {
                              command.addSystemProperty(propName, props.getProperty(propName));
                            }
                          }
                        }

                        // Override child JVM specific properties
                        for (String propName : props.stringPropertyNames()) {
                          if (propName.startsWith(CHILD_PROPERTY_PREFIX)) {
                            command.addSystemProperty(
                                propName.substring(CHILD_PROPERTY_PREFIX.length()),
                                props.getProperty(propName)
                            );
                          }
                        }

                        // Override task specific properties
                        final Map<String, Object> context = task.getContext();
                        if (context != null) {
                          for (String propName : context.keySet()) {
                            if (propName.startsWith(CHILD_PROPERTY_PREFIX)) {
                              Object contextValue = task.getContextValue(propName);
                              if (contextValue != null) {
                                command.addSystemProperty(
                                    propName.substring(CHILD_PROPERTY_PREFIX.length()),
                                    String.valueOf(contextValue)
                                );
                              }
                            }
                          }
                        }

                        // add the attemptId as a system property
                        command.addSystemProperty("attemptId", "1");

                        // Add dataSource, taskId and taskType for metrics or logging
                        command.addSystemProperty(
                            MonitorsConfig.METRIC_DIMENSION_PREFIX + DruidMetrics.DATASOURCE,
                            task.getDataSource()
                        );
                        command.addSystemProperty(
                            MonitorsConfig.METRIC_DIMENSION_PREFIX + DruidMetrics.TASK_ID,
                            task.getId()
                        );
                        command.addSystemProperty(
                            MonitorsConfig.METRIC_DIMENSION_PREFIX + DruidMetrics.TASK_TYPE,
                            task.getType()
                        );
                        command.addSystemProperty(
                            MonitorsConfig.METRIC_DIMENSION_PREFIX + DruidMetrics.GROUP_ID,
                            task.getGroupId()
                        );


                        command.addSystemProperty("druid.host", childHost);
                        command.addSystemProperty("druid.plaintextPort", childPort);
                        command.addSystemProperty("druid.tlsPort", tlsChildPort);

                        // Let tasks know where they are running on.
                        // This information is used in native parallel indexing with shuffle.
                        command.addSystemProperty("druid.task.executor.service", node.getServiceName());
                        command.addSystemProperty("druid.task.executor.host", node.getHost());
                        command.addSystemProperty("druid.task.executor.plaintextPort", node.getPlaintextPort());
                        command.addSystemProperty("druid.task.executor.enablePlaintextPort", node.isEnablePlaintextPort());
                        command.addSystemProperty("druid.task.executor.tlsPort", node.getTlsPort());
                        command.addSystemProperty("druid.task.executor.enableTlsPort", node.isEnableTlsPort());
                        command.addSystemProperty("log4j2.configurationFactory", ConsoleLoggingEnforcementConfigurationFactory.class.getName());

                        command.addSystemProperty("druid.indexer.task.baseTaskDir", storageSlot.getDirectory().getAbsolutePath());
                        command.addSystemProperty("druid.indexer.task.tmpStorageBytesPerTask", storageSlot.getNumBytes());

                        command.add("org.apache.druid.cli.Main");
                        command.add("internal");
                        command.add("peon");
                        command.add(taskDir.toString());
                        command.add(attemptId);
                        String nodeType = task.getNodeType();
                        if (nodeType != null) {
                          command.add("--nodeType");
                          command.add(nodeType);
                        }

                        // If the task type is queryable, we need to load broadcast segments on the peon, used for
                        // join queries. This is replaced by --loadBroadcastDatasourceMode option, but is preserved here
                        // for backwards compatibility and can be removed in a future release.
                        if (task.supportsQueries()) {
                          command.add("--loadBroadcastSegments");
                          command.add("true");
                        }

                        command.add("--loadBroadcastDatasourceMode");
                        command.add(task.getBroadcastDatasourceLoadingSpec().getMode().toString());

                        if (!taskFile.exists()) {
                          jsonMapper.writeValue(taskFile, task);
                        }

                        LOGGER.info(
                            "Running command[%s]",
                            getMaskedCommand(startupLoggingConfig.getMaskProperties(), command.getCommandList())
                        );
                        taskWorkItem.processHolder = runTaskProcess(command.getCommandList(), logFile, taskLocation);

                        processHolder = taskWorkItem.processHolder;
                        processHolder.registerWithCloser(closer);
                      }

                      TaskRunnerUtils.notifyLocationChanged(listeners, task.getId(), taskLocation);
                      TaskRunnerUtils.notifyStatusChanged(
                          listeners,
                          task.getId(),
                          TaskStatus.running(task.getId())
                      );

                      LOGGER.info("Logging output of task[%s] to file[%s].", task.getId(), logFile);
                      final int exitCode = waitForTaskProcessToComplete(task, processHolder, logFile, reportsFile);
                      final TaskStatus status;
                      if (exitCode == 0) {
                        LOGGER.info("Process exited successfully for task[%s]", task.getId());
                        // Process exited successfully
                        status = jsonMapper.readValue(statusFile, TaskStatus.class);
                      } else {
                        LOGGER.error("Process exited with code[%d] for task[%s]", exitCode, task.getId());
                        // Process exited unsuccessfully
                        status = TaskStatus.failure(
                            task.getId(),
                            StringUtils.format(
                                "Task execution process exited unsuccessfully with code[%s]. "
                                + "See middleManager logs for more details.",
                                exitCode
                            )
                        );
                      }
                      if (status.isSuccess()) {
                        successfulTaskCount.incrementAndGet();
                      } else {
                        failedTaskCount.incrementAndGet();
                      }
                      TaskRunnerUtils.notifyStatusChanged(listeners, task.getId(), status);
                      return status;
                    }
                    catch (Throwable t) {
                      throw closer.rethrow(t);
                    }
                    finally {
                      closer.close();
                    }
                  }
                  catch (Throwable t) {
                    LOGGER.info(t, "Exception caught during execution");
                    throw new RuntimeException(t);
                  }
                  finally {
                    try {
                      synchronized (tasks) {
                        final ForkingTaskRunnerWorkItem taskWorkItem = tasks.remove(task.getId());
                        if (taskWorkItem != null && taskWorkItem.processHolder != null) {
                          taskWorkItem.processHolder.shutdown();
                        }
                        if (!stopping) {
                          saveRunningTasks();
                        }
                      }

                      if (node.isEnablePlaintextPort()) {
                        portFinder.markPortUnused(childPort);
                      }
                      if (node.isEnableTlsPort()) {
                        portFinder.markPortUnused(tlsChildPort);
                      }

                      getTracker().returnStorageSlot(storageSlot);

                      try {
                        if (!stopping && taskDir.exists()) {
                          FileUtils.deleteDirectory(taskDir);
                          LOGGER.info("Removing task directory: %s", taskDir);
                        }
                      }
                      catch (Exception e) {
                        LOGGER.makeAlert(e, "Failed to delete task directory")
                              .addData("taskDir", taskDir.toString())
                              .addData("task", task.getId())
                              .emit();
                      }
                    }
                    catch (Exception e) {
                      LOGGER.error(e, "Suppressing exception caught while cleaning up task");
                    }
                  }
                }

              }
            )
          )
      );
      saveRunningTasks();
      return tasks.get(task.getId()).getResult();
    }
  }

  @VisibleForTesting
  ProcessHolder runTaskProcess(List<String> command, File logFile, TaskLocation taskLocation) throws IOException
  {
    return new ProcessHolder(
        new ProcessBuilder(ImmutableList.copyOf(command)).redirectErrorStream(true).start(),
        logFile,
        taskLocation
    );
  }

  @VisibleForTesting
  int waitForTaskProcessToComplete(Task task, ProcessHolder processHolder, File logFile, File reportsFile)
      throws IOException, InterruptedException
  {
    final ByteSink logSink = Files.asByteSink(logFile, FileWriteMode.APPEND);

    // This will block for a while. So we append the thread information with more details
    final String priorThreadName = Thread.currentThread().getName();
    Thread.currentThread().setName(StringUtils.format("%s-[%s]", priorThreadName, task.getId()));

    try (final OutputStream toLogfile = logSink.openStream()) {
      ByteStreams.copy(processHolder.process.getInputStream(), toLogfile);
      return processHolder.process.waitFor();
    }
    finally {
      Thread.currentThread().setName(priorThreadName);
        // Upload task logs
      try {
        taskLogPusher.pushTaskLog(task.getId(), logFile);
      }
      catch (IOException e) {
        LOGGER.error("Task[%s] failed to push task logs to [%s]: Exception[%s]",
            task.getId(), logFile.getName(), e.getMessage());
      }
      if (reportsFile.exists()) {
        try {
          taskLogPusher.pushTaskReports(task.getId(), reportsFile);
        }
        catch (IOException e) {
          LOGGER.error("Task[%s] failed to push task reports to [%s]: Exception[%s]",
              task.getId(), reportsFile.getName(), e.getMessage());
        }
      }
    }
  }

  @Override
  @LifecycleStop
  public void stop()
  {
    stopping = true;
    exec.shutdown();

    synchronized (tasks) {
      for (ForkingTaskRunnerWorkItem taskWorkItem : tasks.values()) {
        shutdownTaskProcess(taskWorkItem);
      }
    }

    final DateTime start = DateTimes.nowUtc();
    final long timeout = new Interval(start, taskConfig.getGracefulShutdownTimeout()).toDurationMillis();

    // Things should be terminating now. Wait for it to happen so logs can be uploaded and all that good stuff.
    LOGGER.info("Waiting up to %,dms for shutdown.", timeout);
    if (timeout > 0) {
      try {
        final boolean terminated = exec.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        final long elapsed = System.currentTimeMillis() - start.getMillis();
        if (terminated) {
          LOGGER.info("Finished stopping in %,dms.", elapsed);
        } else {
          final Set<String> stillRunning;
          synchronized (tasks) {
            stillRunning = ImmutableSet.copyOf(tasks.keySet());
          }

          LOGGER.makeAlert("Failed to stop forked tasks")
                .addData("stillRunning", stillRunning)
                .addData("elapsed", elapsed)
                .emit();

          LOGGER.warn(
              "Executor failed to stop after %,dms, not waiting for it! Tasks still running: [%s]",
              elapsed,
              Joiner.on("; ").join(stillRunning)
          );
        }
      }
      catch (InterruptedException e) {
        LOGGER.warn(e, "Interrupted while waiting for executor to finish.");
        Thread.currentThread().interrupt();
      }
    } else {
      LOGGER.warn("Ran out of time, not waiting for executor to finish!");
    }
  }

  @Override
  public void shutdown(final String taskid, String reason)
  {
    LOGGER.info("Shutdown [%s] because: [%s]", taskid, reason);
    final ForkingTaskRunnerWorkItem taskInfo;

    synchronized (tasks) {
      taskInfo = tasks.get(taskid);

      if (taskInfo == null) {
        LOGGER.info("Ignoring request to cancel unknown task: %s", taskid);
        return;
      }

      taskInfo.shutdown = true;

      shutdownTaskProcess(taskInfo);
    }
  }

  @Override
  public Collection<TaskRunnerWorkItem> getRunningTasks()
  {
    synchronized (tasks) {
      final List<TaskRunnerWorkItem> ret = new ArrayList<>();
      for (final ForkingTaskRunnerWorkItem taskWorkItem : tasks.values()) {
        if (taskWorkItem.processHolder != null) {
          ret.add(taskWorkItem);
        }
      }
      return ret;
    }
  }

  @Override
  public Collection<TaskRunnerWorkItem> getPendingTasks()
  {
    synchronized (tasks) {
      final List<TaskRunnerWorkItem> ret = new ArrayList<>();
      for (final ForkingTaskRunnerWorkItem taskWorkItem : tasks.values()) {
        if (taskWorkItem.processHolder == null) {
          ret.add(taskWorkItem);
        }
      }
      return ret;
    }
  }

  @Nullable
  @Override
  public RunnerTaskState getRunnerTaskState(String taskId)
  {
    final ForkingTaskRunnerWorkItem workItem = tasks.get(taskId);
    if (workItem == null) {
      return null;
    } else {
      if (workItem.processHolder == null) {
        return RunnerTaskState.PENDING;
      } else if (workItem.processHolder.process.isAlive()) {
        return RunnerTaskState.RUNNING;
      } else {
        return RunnerTaskState.NONE;
      }
    }
  }

  @Override
  public Optional<ScalingStats> getScalingStats()
  {
    return Optional.absent();
  }

  @Override
  @LifecycleStart
  public void start()
  {
    setNumProcessorsPerTask();
  }

  @Override
  public Optional<InputStream> streamTaskLog(final String taskid, final long offset) throws IOException
  {
    final ProcessHolder processHolder;

    synchronized (tasks) {
      final ForkingTaskRunnerWorkItem taskWorkItem = tasks.get(taskid);
      if (taskWorkItem != null && taskWorkItem.processHolder != null) {
        processHolder = taskWorkItem.processHolder;
      } else {
        return Optional.absent();
      }
    }
    return Optional.of(LogUtils.streamFile(processHolder.logFile, offset));
  }

  /**
   * Close task output stream (input stream of process) sending EOF telling process to terminate, destroying the process
   * if an exception is encountered.
   */
  private void shutdownTaskProcess(ForkingTaskRunnerWorkItem taskInfo)
  {
    if (taskInfo.processHolder != null) {
      // Will trigger normal failure mechanisms due to process exit
      LOGGER.info("Closing output stream to task[%s].", taskInfo.getTask().getId());
      try {
        taskInfo.processHolder.process.getOutputStream().close();
      }
      catch (Exception e) {
        LOGGER.warn(e, "Failed to close stdout to task[%s]. Destroying task.", taskInfo.getTask().getId());
        taskInfo.processHolder.process.destroy();
      }
    }
  }

  public static String getMaskedCommand(List<String> maskedProperties, List<String> command)
  {
    final Set<String> maskedPropertiesSet = Sets.newHashSet(maskedProperties);
    final Iterator<String> maskedIterator = command.stream().map(element -> {
      String[] splits = element.split("=", 2);
      if (splits.length == 2) {
        for (String masked : maskedPropertiesSet) {
          if (splits[0].contains(masked)) {
            return StringUtils.format("%s=%s", splits[0], "<masked>");
          }
        }
      }
      return element;
    }).iterator();
    return Joiner.on(" ").join(maskedIterator);
  }

  @Override
  public Map<String, Long> getTotalTaskSlotCount()
  {
    return Map.of(workerConfig.getCategory(), getWorkerTotalTaskSlotCount());
  }

  @Override
  public Map<String, Long> getIdleTaskSlotCount()
  {
    return Map.of(
        workerConfig.getCategory(),
        Math.max(getWorkerTotalTaskSlotCount() - getWorkerUsedTaskSlotCount(), 0)
    );
  }

  @Override
  public Map<String, Long> getUsedTaskSlotCount()
  {
    return Map.of(workerConfig.getCategory(), getWorkerUsedTaskSlotCount());
  }

  @Override
  public Map<String, Long> getLazyTaskSlotCount()
  {
    return ImmutableMap.of(workerConfig.getCategory(), 0L);
  }

  @Override
  public Map<String, Long> getBlacklistedTaskSlotCount()
  {
    return ImmutableMap.of(workerConfig.getCategory(), 0L);
  }

  @Override
  public Long getWorkerFailedTaskCount()
  {
    long failedTaskCount = this.failedTaskCount.get();
    long lastReportedFailedTaskCount = this.lastReportedFailedTaskCount.get();
    this.lastReportedFailedTaskCount.set(failedTaskCount);
    return failedTaskCount - lastReportedFailedTaskCount;
  }

  @Override
  public Long getWorkerIdleTaskSlotCount()
  {
    return Math.max(getWorkerTotalTaskSlotCount() - getWorkerUsedTaskSlotCount(), 0);
  }

  @Override
  public Long getWorkerUsedTaskSlotCount()
  {
    return getTracker().getNumUsedSlots();
  }

  @Override
  public Long getWorkerTotalTaskSlotCount()
  {
    return (long) workerConfig.getCapacity();
  }

  @Override
  public String getWorkerCategory()
  {
    return workerConfig.getCategory();
  }

  @Override
  public String getWorkerVersion()
  {
    return workerConfig.getVersion();
  }

  @Override
  public Long getWorkerSuccessfulTaskCount()
  {
    long successfulTaskCount = this.successfulTaskCount.get();
    long lastReportedSuccessfulTaskCount = this.lastReportedSuccessfulTaskCount.get();
    this.lastReportedSuccessfulTaskCount.set(successfulTaskCount);
    return successfulTaskCount - lastReportedSuccessfulTaskCount;
  }

  @VisibleForTesting
  void setNumProcessorsPerTask()
  {
    // Divide number of available processors by the number of tasks.
    // This prevents various automatically-sized thread pools from being unreasonably large (we don't want each
    // task to size its pools as if it is the only thing on the entire machine).

    final int availableProcessors = JvmUtils.getRuntimeInfo().getAvailableProcessors();
    numProcessorsPerTask = Math.max(
        1,
        IntMath.divide(availableProcessors, workerConfig.getCapacity(), RoundingMode.CEILING)
    );
  }

  protected static class ForkingTaskRunnerWorkItem extends TaskRunnerWorkItem
  {
    private final Task task;

    private volatile boolean shutdown = false;
    private volatile ProcessHolder processHolder = null;

    private ForkingTaskRunnerWorkItem(
        Task task,
        ListenableFuture<TaskStatus> statusFuture
    )
    {
      super(task.getId(), statusFuture);
      this.task = task;
    }

    public Task getTask()
    {
      return task;
    }

    @Override
    public TaskLocation getLocation()
    {
      if (processHolder == null) {
        return TaskLocation.unknown();
      } else {
        return processHolder.location;
      }
    }

    @Override
    public String getTaskType()
    {
      return task.getType();
    }

    @Override
    public String getDataSource()
    {
      return task.getDataSource();
    }
  }

  public static class ProcessHolder
  {
    private final Process process;
    private final File logFile;
    private final TaskLocation location;

    public ProcessHolder(Process process, File logFile, TaskLocation location)
    {
      this.process = process;
      this.logFile = logFile;
      this.location = location;
    }

    private void registerWithCloser(Closer closer)
    {
      closer.register(process.getInputStream());
      closer.register(process.getOutputStream());
    }

    private void shutdown()
    {
      process.destroy();
    }
  }

  @VisibleForTesting
  static int getNextAttemptID(File taskDir)
  {
    File attemptDir = new File(taskDir, "attempt");
    try {
      FileUtils.mkdirp(attemptDir);
    }
    catch (IOException e) {
      throw new ISE("Error creating directory", e);
    }
    int maxAttempt =
        Arrays.stream(attemptDir.listFiles(File::isDirectory))
              .mapToInt(x -> Integer.parseInt(x.getName()))
              .max().orElse(0);
    // now make the directory
    File attempt = new File(attemptDir, String.valueOf(maxAttempt + 1));
    try {
      FileUtils.mkdirp(attempt);
    }
    catch (IOException e) {
      throw new ISE("Error creating directory", e);
    }
    return maxAttempt + 1;
  }

  public static class CommandListBuilder
  {
    ArrayList<String> commandList = new ArrayList<>();

    public CommandListBuilder add(String arg)
    {
      commandList.add(arg);
      return this;
    }

    public CommandListBuilder addSystemProperty(String property, int value)
    {
      return addSystemProperty(property, String.valueOf(value));
    }

    public CommandListBuilder addSystemProperty(String property, long value)
    {
      return addSystemProperty(property, String.valueOf(value));
    }

    public CommandListBuilder addSystemProperty(String property, boolean value)
    {
      return addSystemProperty(property, String.valueOf(value));
    }

    public CommandListBuilder addSystemProperty(String property, String value)
    {
      return add(StringUtils.format("-D%s=%s", property, value));
    }

    public CommandListBuilder addAll(Iterable<String> args)
    {
      for (String arg : args) {
        add(arg);
      }
      return this;
    }

    public ArrayList<String> getCommandList()
    {
      return commandList;
    }

  }
}

