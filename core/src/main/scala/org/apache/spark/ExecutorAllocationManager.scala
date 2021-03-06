/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.control.{ControlThrowable, NonFatal}

import com.codahale.metrics.{Gauge, MetricRegistry}

import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.{DYN_ALLOCATION_MAX_EXECUTORS, DYN_ALLOCATION_MIN_EXECUTORS}
import org.apache.spark.metrics.source.Source
import org.apache.spark.scheduler._
import org.apache.spark.util.{Clock, SystemClock, ThreadUtils, Utils}

/**
 * An agent that dynamically allocates and removes executors based on the workload.
 *
 * The ExecutorAllocationManager maintains a moving target number of executors which is periodically
 * synced to the cluster manager. The target starts at a configured initial value and changes with
 * the number of pending and running tasks.
 *
 * Decreasing the target number of executors happens when the current target is more than needed to
 * handle the current load. The target number of executors is always truncated to the number of
 * executors that could run all current running and pending tasks at once.
 *
 * Increasing the target number of executors happens in response to backlogged tasks waiting to be
 * scheduled. If the scheduler queue is not drained in N seconds, then new executors are added. If
 * the queue persists for another M seconds, then more executors are added and so on. The number
 * added in each round increases exponentially from the previous round until an upper bound has been
 * reached. The upper bound is based both on a configured property and on the current number of
 * running and pending tasks, as described above.
 *
 * The rationale for the exponential increase is twofold: (1) Executors should be added slowly
 * in the beginning in case the number of extra executors needed turns out to be small. Otherwise,
 * we may add more executors than we need just to remove them later. (2) Executors should be added
 * quickly over time in case the maximum number of executors is very high. Otherwise, it will take
 * a long time to ramp up under heavy workloads.
 *
 * The remove policy is simpler: If an executor has been idle for K seconds, meaning it has not
 * been scheduled to run any tasks, then it is removed.
 *
 * There is no retry logic in either case because we make the assumption that the cluster manager
 * will eventually fulfill all requests it receives asynchronously.
 *
 * The relevant Spark properties include the following:
 *
 *   spark.dynamicAllocation.enabled - Whether this feature is enabled
 *   spark.dynamicAllocation.minExecutors - Lower bound on the number of executors
 *   spark.dynamicAllocation.maxExecutors - Upper bound on the number of executors
 *   spark.dynamicAllocation.initialExecutors - Number of executors to start with
 *
 *   spark.dynamicAllocation.schedulerBacklogTimeout (M) -
 *     If there are backlogged tasks for this duration, add new executors
 *
 *   spark.dynamicAllocation.sustainedSchedulerBacklogTimeout (N) -
 *     If the backlog is sustained for this duration, add more executors
 *     This is used only after the initial backlog timeout is exceeded
 *
 *   spark.dynamicAllocation.executorIdleTimeout (K) -
 *     If an executor has been idle for this duration, remove it
 */
private[spark] class ExecutorAllocationManager(
    client: ExecutorAllocationClient,
    listenerBus: LiveListenerBus,
    conf: SparkConf)
  extends Logging {

  allocationManager =>

  import ExecutorAllocationManager._

  // Lower and upper bounds on the number of executors.
  private val minNumExecutors = conf.get(DYN_ALLOCATION_MIN_EXECUTORS)
  private val maxNumExecutors = conf.get(DYN_ALLOCATION_MAX_EXECUTORS)
  private val initialNumExecutors = Utils.getDynamicAllocationInitialExecutors(conf)

  // 积压tasks超过schedulerBacklogTimeoutS(默认1)秒，则新增加一个executor
  // How long there must be backlogged tasks for before an addition is triggered (seconds)
  private val schedulerBacklogTimeoutS = conf.getTimeAsSeconds(
    "spark.dynamicAllocation.schedulerBacklogTimeout", "1s")

  // Same as above, but used only after `schedulerBacklogTimeoutS` is exceeded
  private val sustainedSchedulerBacklogTimeoutS = conf.getTimeAsSeconds(
    "spark.dynamicAllocation.sustainedSchedulerBacklogTimeout", s"${schedulerBacklogTimeoutS}s")

  // 一个executor的闲置时间超过executorIdleTimeoutS，则被删除
  // How long an executor must be idle for before it is removed (seconds)
  private val executorIdleTimeoutS = conf.getTimeAsSeconds(
    "spark.dynamicAllocation.executorIdleTimeout", "60s")

  // ???
  private val cachedExecutorIdleTimeoutS = conf.getTimeAsSeconds(
    "spark.dynamicAllocation.cachedExecutorIdleTimeout", s"${Integer.MAX_VALUE}s")

  // During testing, the methods to actually kill and add executors are mocked out
  private val testing = conf.getBoolean("spark.dynamicAllocation.testing", false)

  // TODO: The default value of 1 for spark.executor.cores works right now because dynamic
  // allocation is only supported for YARN and the default number of cores per executor in YARN is
  // 1, but it might need to be attained differently for different cluster managers
  private val tasksPerExecutor =
    conf.getInt("spark.executor.cores", 1) / conf.getInt("spark.task.cpus", 1)

  // 验证以上配置的有效性
  validateSettings()

  // Number of executors to add in the next round
  private var numExecutorsToAdd = 1

  // 这段时间内期望申请到的executors总数。如果我们的executors都快挂掉了，那么，该个数就是
  // 我们需要立即向cluster manager申请的executors总数。
  // The desired number of executors at this moment in time. If all our executors were to die, this
  // is the number of executors we would immediately want from the cluster manager.
  private var numExecutorsTarget = initialNumExecutors

  // 那些被请求删除但还没被kill掉的executors
  // Executors that have been requested to be removed but have not been killed yet
  private val executorsPendingToRemove = new mutable.HashSet[String]

  // 所有已知的executors
  // All known executors
  private val executorIds = new mutable.HashSet[String]

  // A timestamp of when an addition should be triggered, or NOT_SET if it is not set
  // This is set when pending tasks are added but not scheduled yet
  private var addTime: Long = NOT_SET

  // A timestamp for each executor of when the executor should be removed, indexed by the ID
  // This is set when an executor is no longer running a task, or when it first registers
  private val removeTimes = new mutable.HashMap[String, Long]

  // Polling loop interval (ms)
  private val intervalMillis: Long = if (Utils.isTesting) {
      conf.getLong(TESTING_SCHEDULE_INTERVAL_KEY, 100)
    } else {
      100
    }

  // Clock used to schedule when executors should be added and removed
  private var clock: Clock = new SystemClock()

  // Listener for Spark events that impact the allocation policy
  private val listener = new ExecutorAllocationListener

  // Executor that handles the scheduling task.
  private val executor =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("spark-dynamic-executor-allocation")

  // Metric source for ExecutorAllocationManager to expose internal status to MetricsSystem.
  val executorAllocationManagerSource = new ExecutorAllocationManagerSource

  // Whether we are still waiting for the initial set of executors to be allocated.
  // While this is true, we will not cancel outstanding executor requests. This is
  // set to false when:
  //   (1) a stage is submitted, or
  //   (2) an executor idle timeout has elapsed.
  @volatile private var initializing: Boolean = true

  // Number of locality aware tasks, used for executor placement.
  private var localityAwareTasks = 0

  // Host to possible task running on it, used for executor placement.
  private var hostToLocalTaskCount: Map[String, Int] = Map.empty

  /**
   * Verify that the settings specified through the config are valid.
   * If not, throw an appropriate exception.
   */
  private def validateSettings(): Unit = {
    if (minNumExecutors < 0 || maxNumExecutors < 0) {
      throw new SparkException("spark.dynamicAllocation.{min/max}Executors must be positive!")
    }
    if (maxNumExecutors == 0) {
      throw new SparkException("spark.dynamicAllocation.maxExecutors cannot be 0!")
    }
    if (minNumExecutors > maxNumExecutors) {
      throw new SparkException(s"spark.dynamicAllocation.minExecutors ($minNumExecutors) must " +
        s"be less than or equal to spark.dynamicAllocation.maxExecutors ($maxNumExecutors)!")
    }
    if (schedulerBacklogTimeoutS <= 0) {
      throw new SparkException("spark.dynamicAllocation.schedulerBacklogTimeout must be > 0!")
    }
    if (sustainedSchedulerBacklogTimeoutS <= 0) {
      throw new SparkException(
        "spark.dynamicAllocation.sustainedSchedulerBacklogTimeout must be > 0!")
    }
    if (executorIdleTimeoutS < 0) {
      throw new SparkException("spark.dynamicAllocation.executorIdleTimeout must be >= 0!")
    }
    if (cachedExecutorIdleTimeoutS < 0) {
      throw new SparkException("spark.dynamicAllocation.cachedExecutorIdleTimeout must be >= 0!")
    }
    // Require external shuffle service for dynamic allocation
    // Otherwise, we may lose shuffle files when killing executors
    if (!conf.getBoolean("spark.shuffle.service.enabled", false) && !testing) {
      throw new SparkException("Dynamic allocation of executors requires the external " +
        "shuffle service. You may enable this through spark.shuffle.service.enabled.")
    }
    if (tasksPerExecutor == 0) {
      throw new SparkException("spark.executor.cores must not be less than spark.task.cpus.")
    }
  }

  /**
   * Use a different clock for this allocation manager. This is mainly used for testing.
   */
  def setClock(newClock: Clock): Unit = {
    clock = newClock
  }

  /**
   * Register for scheduler callbacks to decide when to add and remove executors, and start
   * the scheduling task.
   */
  def start(): Unit = {
    // 将ExecutorAllocationListener添加到listenerBus中，
    // 这样的话，如果有stage提交、task开始执行或结束、executor增加或删除等等事件
    // 发生时，ExecutorAllocationManager就能通过ExecutorAllocationListener知道，
    // 并根据这些事件所携带的信息来动态地分配executors的需求。
    listenerBus.addToManagementQueue(listener)

    val scheduleTask = new Runnable() {
      override def run(): Unit = {
        try {
          schedule()
        } catch {
          case ct: ControlThrowable =>
            throw ct
          case t: Throwable =>
            logWarning(s"Uncaught exception in thread ${Thread.currentThread().getName}", t)
        }
      }
    }
    // 默认没100ms执行一个schedule()
    executor.scheduleWithFixedDelay(scheduleTask, 0, intervalMillis, TimeUnit.MILLISECONDS)

    // 通过client向cluster manager发生初始executors的需求
    client.requestTotalExecutors(numExecutorsTarget, localityAwareTasks, hostToLocalTaskCount)
  }

  /**
   * Stop the allocation manager.
   */
  def stop(): Unit = {
    executor.shutdown()
    executor.awaitTermination(10, TimeUnit.SECONDS)
  }

  /**
   * Reset the allocation manager when the cluster manager loses track of the driver's state.
   * This is currently only done in YARN client mode, when the AM is restarted.
   *
   * This method forgets about any state about existing executors, and forces the scheduler to
   * re-evaluate the number of needed executors the next time it's run.
   */
  def reset(): Unit = synchronized {
    addTime = 0L
    numExecutorsTarget = initialNumExecutors
    executorsPendingToRemove.clear()
    removeTimes.clear()
  }

  /**
   * The maximum number of executors we would need under the current load to satisfy all running
   * and pending tasks, rounded up.
   */
  private def maxNumExecutorsNeeded(): Int = {
    val numRunningOrPendingTasks = listener.totalPendingTasks + listener.totalRunningTasks
    // 这个四舍五入的写法可以的。
    (numRunningOrPendingTasks + tasksPerExecutor - 1) / tasksPerExecutor
  }

  private def totalRunningTasks(): Int = synchronized {
    listener.totalRunningTasks
  }

  /**
   * This is called at a fixed interval to regulate the number of pending executor requests
   * and number of executors running.
   *
   * First, adjust our requested executors based on the add time and our current needs.
   * Then, if the remove time for an existing executor has expired, kill the executor.
   *
   * This is factored out into its own method for testing.
   */
  private def schedule(): Unit = synchronized {
    val now = clock.getTimeMillis

    updateAndSyncNumExecutorsTarget(now)

    val executorIdsToBeRemoved = ArrayBuffer[String]()
    removeTimes.retain { case (executorId, expireTime) =>
      // 如果一个executor闲置时间过长，则删除它
      val expired = now >= expireTime
      if (expired) {
        initializing = false
        executorIdsToBeRemoved += executorId
      }
      !expired
    }
    if (executorIdsToBeRemoved.nonEmpty) {
      // 删除这些闲置时间过长的executors
      removeExecutors(executorIdsToBeRemoved)
    }
  }

  /**
   * Updates our target number of executors and syncs the result with the cluster manager.
   *
   * Check to see whether our existing allocation and the requests we've made previously exceed our
   * current needs. If so, truncate our target and let the cluster manager know so that it can
   * cancel pending requests that are unneeded.
   *
   * If not, and the add time has expired, see if we can request new executors and refresh the add
   * time.
   *
   * @return the delta in the target number of executors.
   */
  private def updateAndSyncNumExecutorsTarget(now: Long): Int = synchronized {
    // 当前需要的最多的executors（包括正在运行的和还需要向master申请的），
    // 也就是在cluster manger中app的executorLimit（即该app能申请的executors总数的上限）
    val maxNeeded = maxNumExecutorsNeeded

    if (initializing) {
      // Do not change our target while we are still initializing,
      // Otherwise the first job may have to ramp up unnecessarily
      0
    } else if (maxNeeded < numExecutorsTarget) {
      // The target number exceeds the number we actually need, so stop adding new
      // executors and inform the cluster manager to cancel the extra pending requests
      val oldNumExecutorsTarget = numExecutorsTarget
      // 保证我们的target大于等于minNumExecutors
      numExecutorsTarget = math.max(maxNeeded, minNumExecutors)
      // 重置numExecutorsToAdd为1
      numExecutorsToAdd = 1

      // If the new target has not changed, avoid sending a message to the cluster manager
      if (numExecutorsTarget < oldNumExecutorsTarget) {
        client.requestTotalExecutors(numExecutorsTarget, localityAwareTasks, hostToLocalTaskCount)
        logDebug(s"Lowering target number of executors to $numExecutorsTarget (previously " +
          s"$oldNumExecutorsTarget) because not all requested executors are actually needed")
      }
      // 所以这里 <=0???
      // 对的 没错
      numExecutorsTarget - oldNumExecutorsTarget
    } else if (addTime != NOT_SET && now >= addTime) {
      // 如果maxNeeded > numExecutorsTarget(说明当前executors的需求量变大了)，且
      // now >= addTime(说明已经超时了)，则尝试增加executors
      val delta = addExecutors(maxNeeded)
      logDebug(s"Starting timer to add more executors (to " +
        s"expire in $sustainedSchedulerBacklogTimeoutS seconds)")
      // 更新addTime（设置超时时长为sustainedSchedulerBacklogTimeoutS秒）
      addTime = now + (sustainedSchedulerBacklogTimeoutS * 1000)
      delta
    } else {
      0
    }
  }

  /**
   * Request a number of executors from the cluster manager.
   * If the cap on the number of executors is reached, give up and reset the
   * number of executors to add next round instead of continuing to double it.
   *
   * @param maxNumExecutorsNeeded the maximum number of executors all currently running or pending
   *                              tasks could fill
   * @return the number of additional executors actually requested.
   */
  private def addExecutors(maxNumExecutorsNeeded: Int): Int = {
    // Do not request more executors if it would put our target over the upper bound
    if (numExecutorsTarget >= maxNumExecutors) {
      logDebug(s"Not adding executors because our current target total " +
        s"is already $numExecutorsTarget (limit $maxNumExecutors)")
      numExecutorsToAdd = 1
      return 0
    }

    val oldNumExecutorsTarget = numExecutorsTarget
    // There's no point in wasting time ramping up to the number of executors we already have, so
    // make sure our target is at least as much as our current allocation:
    numExecutorsTarget = math.max(numExecutorsTarget, executorIds.size)
    // Boost our target with the number to add for this round:
    numExecutorsTarget += numExecutorsToAdd
    // Ensure that our target doesn't exceed what we need at the present moment:
    numExecutorsTarget = math.min(numExecutorsTarget, maxNumExecutorsNeeded)
    // Ensure that our target fits within configured bounds:
    numExecutorsTarget = math.max(math.min(numExecutorsTarget, maxNumExecutors), minNumExecutors)

    val delta = numExecutorsTarget - oldNumExecutorsTarget

    // If our target has not changed, do not send a message
    // to the cluster manager and reset our exponential growth
    if (delta == 0) {
      // QUESTION: pendingTask等于0，而pendingSpeculativeTasks大于0，
      // 就让maxNumExecutorsNeeded +1。为什么 +1 ??? 没看懂???
      // Check if there is any speculative jobs pending
      if (listener.pendingTasks == 0 && listener.pendingSpeculativeTasks > 0) {
        // +1 ??? why? 如果我pendingSpeculativeTasks很多呢???(不过反正我们delta已经等于0了
        // 应该不会很多吧)
        numExecutorsTarget =
          math.max(math.min(maxNumExecutorsNeeded + 1, maxNumExecutors), minNumExecutors)
      } else {
        // else的情况是什么???
        numExecutorsToAdd = 1
        return 0
      }
    }

    // 通知cluster manager我们当前所需要的executors的数量numExecutorsTarget
    val addRequestAcknowledged = try {
      testing ||
        client.requestTotalExecutors(numExecutorsTarget, localityAwareTasks, hostToLocalTaskCount)
    } catch {
      case NonFatal(e) =>
        // Use INFO level so the error it doesn't show up by default in shells. Errors here are more
        // commonly caused by YARN AM restarts, which is a recoverable issue, and generate a lot of
        // noisy output.
        logInfo("Error reaching cluster manager.", e)
        false
    }
    // 如果cluster manager认可了我们的请求
    if (addRequestAcknowledged) {
      val executorsString = "executor" + { if (delta > 1) "s" else "" }
      logInfo(s"Requesting $delta new $executorsString because tasks are backlogged" +
        s" (new desired total will be $numExecutorsTarget)")
      numExecutorsToAdd = if (delta == numExecutorsToAdd) {
        numExecutorsToAdd * 2
      } else {
        // 说明我们的target已经达到maxNumExecutorsNeeded了
        // 举个例子:
        // target   add   new_add  new_target  max_need  delta  delta == add
        //  10       1       2        11          20        1        yes
        //  11       2       4        13          20        2        yes
        //  13       4       8        17          20        4        yes
        //  17       8       1(reset) 20          20        3        no
        //  20       1       1        20          20        0        no
        // 可见我们的executors是成倍逐渐增加的，而不是一次性就申请到最多的
        1
      }
      delta
    } else {
      logWarning(
        s"Unable to reach the cluster manager to request $numExecutorsTarget total executors!")
      numExecutorsTarget = oldNumExecutorsTarget
      0
    }
  }

  /**
   * 请求cluster manager删除这些给定的executors。
   * 返回那些被删除的executors。
   * Request the cluster manager to remove the given executors.
   * Returns the list of executors which are removed.
   */
  private def removeExecutors(executors: Seq[String]): Seq[String] = synchronized {
    val executorIdsToBeRemoved = new ArrayBuffer[String]

    logInfo("Request to remove executorIds: " + executors.mkString(", "))
    val numExistingExecutors = allocationManager.executorIds.size - executorsPendingToRemove.size

    var newExecutorTotal = numExistingExecutors
    executors.foreach { executorIdToBeRemoved =>
      if (newExecutorTotal - 1 < minNumExecutors) {
        logDebug(s"Not removing idle executor $executorIdToBeRemoved because there are only " +
          s"$newExecutorTotal executor(s) left (minimum number of executor limit $minNumExecutors)")
      } else if (newExecutorTotal - 1 < numExecutorsTarget) {
        logDebug(s"Not removing idle executor $executorIdToBeRemoved because there are only " +
          s"$newExecutorTotal executor(s) left (number of executor target $numExecutorsTarget)")
      } else if (canBeKilled(executorIdToBeRemoved)) {
        // 注意：它俩差了一个's'
        executorIdsToBeRemoved += executorIdToBeRemoved
        newExecutorTotal -= 1
      }
    }

    if (executorIdsToBeRemoved.isEmpty) {
      return Seq.empty[String]
    }

    // Send a request to the backend to kill this executor(s)
    val executorsRemoved = if (testing) {
      executorIdsToBeRemoved
    } else {
      // 发送请求给backend，让它去删除这些executors
      client.killExecutors(executorIdsToBeRemoved)
    }
    // [SPARK-21834] killExecutors api reduces the target number of executors.
    // So we need to update the target with desired value.
    client.requestTotalExecutors(numExecutorsTarget, localityAwareTasks, hostToLocalTaskCount)
    // reset the newExecutorTotal to the existing number of executors
    newExecutorTotal = numExistingExecutors
    if (testing || executorsRemoved.nonEmpty) {
      executorsRemoved.foreach { removedExecutorId =>
        newExecutorTotal -= 1
        logInfo(s"Removing executor $removedExecutorId because it has been idle for " +
          s"$executorIdleTimeoutS seconds (new desired total will be $newExecutorTotal)")
        // 暂时把要删除的executors加入到executorsPendingToRemove中，当executor真正被master命令
        // 删除之后，再从executorsPendingToRemove中删除。
        executorsPendingToRemove.add(removedExecutorId)
      }
      executorsRemoved
    } else {
      logWarning(s"Unable to reach the cluster manager to kill executor/s " +
        s"${executorIdsToBeRemoved.mkString(",")} or no executor eligible to kill!")
      Seq.empty[String]
    }
  }

  /**
   * Request the cluster manager to remove the given executor.
   * Return whether the request is acknowledged.
   */
  private def removeExecutor(executorId: String): Boolean = synchronized {
    val executorsRemoved = removeExecutors(Seq(executorId))
    executorsRemoved.nonEmpty && executorsRemoved(0) == executorId
  }

  /**
   * Determine if the given executor can be killed.
   */
  private def canBeKilled(executorId: String): Boolean = synchronized {
    // 如果该并不知道这个executor，则不要贸然的去kill它
    // Do not kill the executor if we are not aware of it (should never happen)
    if (!executorIds.contains(executorId)) {
      logWarning(s"Attempted to remove unknown executor $executorId!")
      return false
    }

    // 如果该executor已经在kill的pending队列中，则不要重复去删除它
    // Do not kill the executor again if it is already pending to be killed (should never happen)
    if (executorsPendingToRemove.contains(executorId)) {
      logWarning(s"Attempted to remove executor $executorId " +
        s"when it is already pending to be removed!")
      return false
    }

    true
  }

  /**
   * Callback invoked when the specified executor has been added.
   */
  private def onExecutorAdded(executorId: String): Unit = synchronized {
    if (!executorIds.contains(executorId)) {
      executorIds.add(executorId)
      // If an executor (call this executor X) is not removed because the lower bound
      // has been reached, it will no longer be marked as idle. When new executors join,
      // however, we are no longer at the lower bound, and so we must mark executor X
      // as idle again so as not to forget that it is a candidate for removal. (see SPARK-4951)
      executorIds.filter(listener.isExecutorIdle).foreach(onExecutorIdle)
      logInfo(s"New executor $executorId has registered (new total is ${executorIds.size})")
    } else {
      logWarning(s"Duplicate executor $executorId has registered")
    }
  }

  /**
   * Callback invoked when the specified executor has been removed.
   */
  private def onExecutorRemoved(executorId: String): Unit = synchronized {
    if (executorIds.contains(executorId)) {
      executorIds.remove(executorId)
      removeTimes.remove(executorId)
      logInfo(s"Existing executor $executorId has been removed (new total is ${executorIds.size})")
      // 该被删除的executor必须在executorsPendingToRemove里包含。
      // （我猜是这样的：ExecutorAllocationManager由于某些原因（比如executor长时间闲置）删除了
      // 一些executors，这些被manager删除的executors是放在executorsPendingToRemove里的。然后
      // 通知StandaloneSchedulerBackend， backend再通知Cluster Manager去真正删除executors。
      // 当driver收到executor真的被删除的事件后，将该事件post到listener bus上，然后
      // ExecutorAllocationListener监听到该事件后，再从executorsPendingToRemove中删除这些已
      // 经被真正删除的executors。）
      if (executorsPendingToRemove.contains(executorId)) {
        executorsPendingToRemove.remove(executorId)
        logDebug(s"Executor $executorId is no longer pending to " +
          s"be removed (${executorsPendingToRemove.size} left)")
      }
    } else {
      logWarning(s"Unknown executor $executorId has been removed!")
    }
  }

  /**
   * Callback invoked when the scheduler receives new pending tasks.
   * This sets a time in the future that decides when executors should be added
   * if it is not already set.
   */
  private def onSchedulerBacklogged(): Unit = synchronized {
    if (addTime == NOT_SET) {
      logDebug(s"Starting timer to add executors because pending tasks " +
        s"are building up (to expire in $schedulerBacklogTimeoutS seconds)")
      addTime = clock.getTimeMillis + schedulerBacklogTimeoutS * 1000
    }
  }

  /**
   * Callback invoked when the scheduler queue is drained.
   * This resets all variables used for adding executors.
   */
  private def onSchedulerQueueEmpty(): Unit = synchronized {
    logDebug("Clearing timer to add executors because there are no more pending tasks")
    addTime = NOT_SET
    numExecutorsToAdd = 1
  }

  /**
   * Callback invoked when the specified executor is no longer running any tasks.
   * This sets a time in the future that decides when this executor should be removed if
   * the executor is not already marked as idle.
   */
  private def onExecutorIdle(executorId: String): Unit = synchronized {
    if (executorIds.contains(executorId)) {
      if (!removeTimes.contains(executorId) && !executorsPendingToRemove.contains(executorId)) {
        // Note that it is not necessary to query the executors since all the cached
        // blocks we are concerned with are reported to the driver. Note that this
        // does not include broadcast blocks.
        val hasCachedBlocks = SparkEnv.get.blockManager.master.hasCachedBlocks(executorId)
        val now = clock.getTimeMillis()
        val timeout = {
          if (hasCachedBlocks) {
            // cachedExecutorIdleTimeoutS默认值是Int.MaxValue；也就是说，如果一个idle的
            // executor上有缓存的blocks，则默认不删除该executor
            // Use a different timeout if the executor has cached blocks.
            now + cachedExecutorIdleTimeoutS * 1000
          } else {
            now + executorIdleTimeoutS * 1000
          }
        }
        val realTimeout = if (timeout <= 0) Long.MaxValue else timeout // overflow
        removeTimes(executorId) = realTimeout
        logDebug(s"Starting idle timer for $executorId because there are no more tasks " +
          s"scheduled to run on the executor (to expire in ${(realTimeout - now)/1000} seconds)")
      }
    } else {
      logWarning(s"Attempted to mark unknown executor $executorId idle")
    }
  }

  /**
   * Callback invoked when the specified executor is now running a task.
   * This resets all variables used for removing this executor.
   */
  private def onExecutorBusy(executorId: String): Unit = synchronized {
    logDebug(s"Clearing idle timer for $executorId because it is now running a task")
    // 如果该executor处于busy（即至少有一个task正在执行）状态，则直接把它从removeTimes中移除
    removeTimes.remove(executorId)
  }

  /**
   * A listener that notifies the given allocation manager of when to add and remove executors.
   *
   * This class is intentionally conservative in its assumptions about the relative ordering
   * and consistency of events returned by the listener.
   */
  private class ExecutorAllocationListener extends SparkListener {

    // 存储一个stage和它所包含的tasks数量之间的映射
    private val stageIdToNumTasks = new mutable.HashMap[Int, Int]
    // 记录每个stage中正在执行的tasks总数（包括speculative tasks）
    // Number of running tasks per stage including speculative tasks.
    // Should be 0 when no stages are active.
    private val stageIdToNumRunningTask = new mutable.HashMap[Int, Int]
    // 该stage中已经开始调度执行的tasks（即不在pending队列中）
    private val stageIdToTaskIndices = new mutable.HashMap[Int, mutable.HashSet[Int]]
    private val executorIdToTaskIds = new mutable.HashMap[String, mutable.HashSet[Long]]
    // Number of speculative tasks to be scheduled in each stage
    private val stageIdToNumSpeculativeTasks = new mutable.HashMap[Int, Int]
    // The speculative tasks started in each stage
    private val stageIdToSpeculativeTaskIndices = new mutable.HashMap[Int, mutable.HashSet[Int]]

    // stageId to tuple (the number of task with locality preferences, a map where each pair is a
    // node and the number of tasks that would like to be scheduled on that node) map,
    // maintain the executor placement hints for each stage Id used by resource framework to better
    // place the executors.
    private val stageIdToExecutorPlacementHints = new mutable.HashMap[Int, (Int, Map[String, Int])]

    override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted): Unit = {
      initializing = false
      val stageId = stageSubmitted.stageInfo.stageId
      val numTasks = stageSubmitted.stageInfo.numTasks
      allocationManager.synchronized {
        // numTasks对应该stage的TaskSet的tasks的数量
        stageIdToNumTasks(stageId) = numTasks
        // 现在stage刚刚提交，肯定没有正在运行的tasks啊
        stageIdToNumRunningTask(stageId) = 0
        // 初始化addTime
        allocationManager.onSchedulerBacklogged()

        // 计算该stage，属于各个host的tasks的数量
        // Compute the number of tasks requested by the stage on each host
        var numTasksPending = 0
        val hostToLocalTaskCountPerStage = new mutable.HashMap[String, Int]()
        stageSubmitted.stageInfo.taskLocalityPreferences.foreach { locality =>
          if (!locality.isEmpty) {
            numTasksPending += 1
            locality.foreach { location =>
              val count = hostToLocalTaskCountPerStage.getOrElse(location.host, 0) + 1
              hostToLocalTaskCountPerStage(location.host) = count
            }
          }
        }
        stageIdToExecutorPlacementHints.put(stageId,
          (numTasksPending, hostToLocalTaskCountPerStage.toMap))

        // Update the executor placement hints
        updateExecutorPlacementHints()
      }
    }

    override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {
      val stageId = stageCompleted.stageInfo.stageId
      allocationManager.synchronized {
        stageIdToNumTasks -= stageId
        stageIdToNumRunningTask -= stageId
        stageIdToNumSpeculativeTasks -= stageId
        stageIdToTaskIndices -= stageId
        stageIdToSpeculativeTaskIndices -= stageId
        stageIdToExecutorPlacementHints -= stageId

        // Update the executor placement hints
        updateExecutorPlacementHints()

        // If this is the last stage with pending tasks, mark the scheduler queue as empty
        // This is needed in case the stage is aborted for any reason
        if (stageIdToNumTasks.isEmpty && stageIdToNumSpeculativeTasks.isEmpty) {
          allocationManager.onSchedulerQueueEmpty()
        }
      }
    }

    override def onTaskStart(taskStart: SparkListenerTaskStart): Unit = {
      val stageId = taskStart.stageId
      val taskId = taskStart.taskInfo.taskId
      val taskIndex = taskStart.taskInfo.index
      val executorId = taskStart.taskInfo.executorId

      allocationManager.synchronized {
        if (stageIdToNumRunningTask.contains(stageId)) {
          stageIdToNumRunningTask(stageId) += 1
        }
        // This guards against the race condition in which the `SparkListenerTaskStart`
        // event is posted before the `SparkListenerBlockManagerAdded` event, which is
        // possible because these events are posted in different threads. (see SPARK-4951)
        if (!allocationManager.executorIds.contains(executorId)) {
          allocationManager.onExecutorAdded(executorId)
        }

        // If this is the last pending task, mark the scheduler queue as empty
        if (taskStart.taskInfo.speculative) {
          stageIdToSpeculativeTaskIndices.getOrElseUpdate(stageId, new mutable.HashSet[Int]) +=
            taskIndex
        } else {
          stageIdToTaskIndices.getOrElseUpdate(stageId, new mutable.HashSet[Int]) += taskIndex
        }
        // 如果所有的pending tasks都没有了，则我们就不需要增加新的executors了
        // 设置addTime = NOT_SET，就不会有新的executors添加
        if (totalPendingTasks() == 0) {
          allocationManager.onSchedulerQueueEmpty()
        }

        // 在该executor上添加新开始执行的task
        // Mark the executor on which this task is scheduled as busy
        executorIdToTaskIds.getOrElseUpdate(executorId, new mutable.HashSet[Long]) += taskId
        // 该executor上有task执行，则标记该executor为busy
        allocationManager.onExecutorBusy(executorId)
      }
    }

    override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
      val executorId = taskEnd.taskInfo.executorId
      val taskId = taskEnd.taskInfo.taskId
      val taskIndex = taskEnd.taskInfo.index
      val stageId = taskEnd.stageId
      allocationManager.synchronized {
        if (stageIdToNumRunningTask.contains(stageId)) {
          stageIdToNumRunningTask(stageId) -= 1
        }
        // If the executor is no longer running any scheduled tasks, mark it as idle
        if (executorIdToTaskIds.contains(executorId)) {
          executorIdToTaskIds(executorId) -= taskId
          // 如果该executor上没有任何执行的tasks了，则标记它为idle状态
          if (executorIdToTaskIds(executorId).isEmpty) {
            executorIdToTaskIds -= executorId
            allocationManager.onExecutorIdle(executorId)
          }
        }

        // If the task failed, we expect it to be resubmitted later. To ensure we have
        // enough resources to run the resubmitted task, we need to mark the scheduler
        // as backlogged again if it's not already marked as such (SPARK-8366)
        if (taskEnd.reason != Success) {
          // 如果没有pending的task（则有可能executor因为idle了很久，都被删除了），
          // 则该失败的task在成为新的pending task后，可能没有可以分配的executor了。
          // 为了保证有计算资源，我们需要调用onSchedulerBacklogged，表示又有积压的task了。
          // ExecutorAllocationManager会在之后根据积压的资源，（如果有必要），为我们分配新的executors。
          if (totalPendingTasks() == 0) {
            allocationManager.onSchedulerBacklogged()
          }
          if (taskEnd.taskInfo.speculative) {
            stageIdToSpeculativeTaskIndices.get(stageId).foreach {_.remove(taskIndex)}
          } else {
            stageIdToTaskIndices.get(stageId).foreach {_.remove(taskIndex)}
          }
        }
      }
    }

    override def onExecutorAdded(executorAdded: SparkListenerExecutorAdded): Unit = {
      val executorId = executorAdded.executorId
      if (executorId != SparkContext.DRIVER_IDENTIFIER) {
        // This guards against the race condition in which the `SparkListenerTaskStart`
        // event is posted before the `SparkListenerBlockManagerAdded` event, which is
        // possible because these events are posted in different threads. (see SPARK-4951)
        if (!allocationManager.executorIds.contains(executorId)) {
          allocationManager.onExecutorAdded(executorId)
        }
      }
    }

    override def onExecutorRemoved(executorRemoved: SparkListenerExecutorRemoved): Unit = {
      allocationManager.onExecutorRemoved(executorRemoved.executorId)
    }

    override def onSpeculativeTaskSubmitted(speculativeTask: SparkListenerSpeculativeTaskSubmitted)
      : Unit = {
       val stageId = speculativeTask.stageId

      allocationManager.synchronized {
        stageIdToNumSpeculativeTasks(stageId) =
          stageIdToNumSpeculativeTasks.getOrElse(stageId, 0) + 1
        allocationManager.onSchedulerBacklogged()
      }
    }

    /**
     * An estimate of the total number of pending tasks remaining for currently running stages. Does
     * not account for tasks which may have failed and been resubmitted.
     *
     * Note: This is not thread-safe without the caller owning the `allocationManager` lock.
     */
    def pendingTasks(): Int = {
      // stageIdToNumTasks记录一个stage中的所有tasks总数（不包含speculative tasks）
      // stageIdToTaskIndices记录一个stage中已经开始调度执行tasks
      stageIdToNumTasks.map { case (stageId, numTasks) =>
        numTasks - stageIdToTaskIndices.get(stageId).map(_.size).getOrElse(0)
      }.sum
    }

    def pendingSpeculativeTasks(): Int = {
      // stageIdToNumSpeculativeTasks记录一个stage中等待被调度执行的speculative tasks总数
      // stageIdToSpeculativeTaskIndices包含一个stage中，已经开始调度执行的speculative tasks
      stageIdToNumSpeculativeTasks.map { case (stageId, numTasks) =>
        numTasks - stageIdToSpeculativeTaskIndices.get(stageId).map(_.size).getOrElse(0)
      }.sum
    }

    def totalPendingTasks(): Int = {
      pendingTasks + pendingSpeculativeTasks
    }

    /**
     * The number of tasks currently running across all stages.
     */
    def totalRunningTasks(): Int = {
      stageIdToNumRunningTask.values.sum
    }

    /**
     * Return true if an executor is not currently running a task, and false otherwise.
     *
     * Note: This is not thread-safe without the caller owning the `allocationManager` lock.
     */
    def isExecutorIdle(executorId: String): Boolean = {
      !executorIdToTaskIds.contains(executorId)
    }

    /**
     * Update the Executor placement hints (the number of tasks with locality preferences,
     * a map where each pair is a node and the number of tasks that would like to be scheduled
     * on that node).
     *
     * These hints are updated when stages arrive and complete, so are not up-to-date at task
     * granularity within stages.
     */
    def updateExecutorPlacementHints(): Unit = {
      var localityAwareTasks = 0
      val localityToCount = new mutable.HashMap[String, Int]()
      stageIdToExecutorPlacementHints.values.foreach { case (numTasksPending, localities) =>
        localityAwareTasks += numTasksPending
        localities.foreach { case (hostname, count) =>
          val updatedCount = localityToCount.getOrElse(hostname, 0) + count
          localityToCount(hostname) = updatedCount
        }
      }

      allocationManager.localityAwareTasks = localityAwareTasks
      allocationManager.hostToLocalTaskCount = localityToCount.toMap
    }
  }

  /**
   * Metric source for ExecutorAllocationManager to expose its internal executor allocation
   * status to MetricsSystem.
   * Note: These metrics heavily rely on the internal implementation of
   * ExecutorAllocationManager, metrics or value of metrics will be changed when internal
   * implementation is changed, so these metrics are not stable across Spark version.
   */
  private[spark] class ExecutorAllocationManagerSource extends Source {
    val sourceName = "ExecutorAllocationManager"
    val metricRegistry = new MetricRegistry()

    private def registerGauge[T](name: String, value: => T, defaultValue: T): Unit = {
      metricRegistry.register(MetricRegistry.name("executors", name), new Gauge[T] {
        override def getValue: T = synchronized { Option(value).getOrElse(defaultValue) }
      })
    }

    registerGauge("numberExecutorsToAdd", numExecutorsToAdd, 0)
    registerGauge("numberExecutorsPendingToRemove", executorsPendingToRemove.size, 0)
    registerGauge("numberAllExecutors", executorIds.size, 0)
    registerGauge("numberTargetExecutors", numExecutorsTarget, 0)
    registerGauge("numberMaxNeededExecutors", maxNumExecutorsNeeded(), 0)
  }
}

private object ExecutorAllocationManager {
  val NOT_SET = Long.MaxValue
  val TESTING_SCHEDULE_INTERVAL_KEY = "spark.testing.dynamicAllocation.scheduleInterval"
}
