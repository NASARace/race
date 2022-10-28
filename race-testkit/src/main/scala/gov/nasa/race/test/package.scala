/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race

import java.lang.management.ManagementFactory

/**
  * package `gov.nasa.race.test` contains types and utilities that are used to create RACE specific tests
  */
package object test {

  val runtime = Runtime.getRuntime
  val threadMxBean = ManagementFactory.getThreadMXBean
  val memoryMxBean = ManagementFactory.getMemoryMXBean
  val gcMxBeans = ManagementFactory.getGarbageCollectorMXBeans

  def elapsedMillis(n: Int)(f: => Any): Long = {
    val t0 = System.currentTimeMillis
    var i = 0
    while (i < n) {
      f
      i += 1
    }
    System.currentTimeMillis - t0
  }

  def elapsedNanos(n: Int)(f: => Any): Long = {
    val t0 = System.nanoTime
    var i = 0
    while (i < n) {
      f
      i += 1
    }
    System.nanoTime - t0
  }

  def setThreadCpuEnabled: Unit = {
    threadMxBean.setThreadCpuTimeEnabled(true)
    if (!threadMxBean.isThreadCpuTimeEnabled) throw new RuntimeException("VM does not support thread CPU time")
  }

  def elapsedThreadCpuNanos(n: Int)(f: => Any): Long = {
    setThreadCpuEnabled
    val tid = Thread.currentThread.getId()  // should be replaced by .threadId() once JDK 19 is mainstream enough
    val t0 = threadMxBean.getThreadCpuTime(tid)
    var i = 0
    while (i < n) {
      f
      i += 1
    }
    threadMxBean.getThreadCpuTime(tid) - t0
  }

  def usedHeapMemory: Long = {
    memoryMxBean.getHeapMemoryUsage.getUsed
  }

  def usedHeapMemoryDiff (f: => Any): Long = {
    val m0 = usedHeapMemory
    f
    usedHeapMemory - m0
  }

  def gcMillis (mgr: Int): Long = {
    gcMxBeans.get(mgr).getCollectionTime
  }

  def gcCount (mgr: Int): Long = {
    gcMxBeans.get(mgr).getCollectionCount
  }

  def gcCountDiff (mgr: Int)(f: => Any): Long = {
    val c1 = gcCount(mgr)
    f
    (gcCount(mgr) - c1)
  }

  def gc: Unit = {
    Runtime.getRuntime.gc()
  }
}
