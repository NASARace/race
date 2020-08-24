/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.common

import java.io.File

import scala.concurrent.duration.{DurationInt, FiniteDuration}


case class FWClientEntry(key: Any, pathName: String, client: FileWatchClient) {
  val cpn: String = new File(pathName).getCanonicalPath
}

/**
  * interface for FileWatch clients
  */
trait FileWatchClient {

  private var watchMap = Map.empty[Any,FWClientEntry]

  /**
    * callback to notify that file has changed externally
    *
    * NOTE - this is called async from the FileWatcher background thread. If we perform any
    * non-threadsafe actions the implementation has to be synchronized
    */
  def fileChanged (key: Any): Unit

  /**
    * tell the FileWatcher we changed the file ourselves and it should
    * inform all other watchers of this file
    */
  def changedFile (key: Any): Unit = {
    watchMap.get(key) match {
      case Some(entry) => FileWatcher.changedFile(entry)
      case None => // ignore, we are not watching this
    }
  }

  def watchFile (key: Any, pathName: String, interval: FiniteDuration = FileWatcher.defaultInterval): Unit = {
    watchMap.get(key) match {
      case Some(entry) => // ignore, we already watch
        FileWatcher.requestInterval(interval) // only changes it if requested interval is shorter
      case None => // new entry
        val entry = FWClientEntry(key,pathName,this)
        watchMap = watchMap + (key -> entry)
        FileWatcher.watch(entry)
        FileWatcher.requestInterval(interval)
    }
  }

  def unWatchFile (key: Any): Unit = {
    watchMap.get(key) match {
      case Some(entry) =>
        FileWatcher.unWatch(entry)
        watchMap = watchMap - key
      case None => // ignore, nothing to unwatch
    }
  }

  def unWatchAllFiles(): Unit = {
    watchMap.foreach ( e=> FileWatcher.unWatch(e._2))
    watchMap = Map.empty
  }
}

/**
  * global mechanism to watch for file changes
  *
  * this implementation assumes there is a relatively low number of infrequently (>10sec) watched files
  * and hence minimizing the number of background threads is more important than reducing the number of
  * check invocations.
  *
  * We also assume watch/unWatch operations happen infrequently
  *
  * For all these reasons we don't tap into an existing thread pool / ExecutorService but rather use our own
  * dedicated and minimal background thread
  *
  * TODO - there should be an actor version of this which avoids threadsafety issues of aync exec of the fileChanged()
  */
object FileWatcher {

  val defaultInterval: FiniteDuration = 30.seconds

  case class WatchEntry (canonicalPathName: String, file: File, clientEntries: Set[FWClientEntry])

  private val checkThread = new Thread {
    setDaemon(true)

    override def run(): Unit = {
      while(true) {
        while (watchList.isEmpty) {
          try {
            lock.synchronized {
              lock.wait
            }
          } catch {
            case x: InterruptedException => // ignore, spurious wakeup
          }
        }

        check()
        lastCheck = System.currentTimeMillis()

        try {
          Thread.sleep(checkInterval.toMillis)
        } catch {
          case x: InterruptedException => // don't go back to sleep
        }
      }
    }
  }
  // note we don't start the thread yet

  private val lock = new Object
  private var lastCheck: Long = 0
  private var checkInterval: FiniteDuration = defaultInterval // can change if clients request shorter periods
  private var watchList: Map[String,WatchEntry] = Map.empty

  private def check(): Unit = synchronized {
    watchList.foreach { e =>
      val we = e._2
      if (we.file.lastModified > lastCheck) {
        we.clientEntries.foreach( e=> e.client.fileChanged( e.key))
      }
    }
  }

  def requestInterval (newInterval: FiniteDuration): Boolean = {
    if (newInterval < checkInterval) {
      checkInterval = newInterval
      true
    } else {
      false
    }
  }

  /**
    * note this is not supposed to be called directly, only by FileWatchClient.watchFile
    */
  def watch (newClientEntry: FWClientEntry): Unit = synchronized {
    val file = new File(newClientEntry.pathName)
    val cpn = newClientEntry.cpn

    def update (newEntry: WatchEntry): Unit = {
      val isFirst = watchList.isEmpty
      watchList = watchList + (cpn -> newEntry)
      if (isFirst) {
        if (checkThread.getState == Thread.State.NEW) {
          checkThread.start()
        } else {
          lock.synchronized( lock.notify)
        }
      }
    }

    watchList.get(cpn) match {
      case Some(entry) =>
        // note the client itself checks if it is already watching hence we don't have to do this here
        update(entry.copy(clientEntries = entry.clientEntries + newClientEntry))
      case None =>
        update( WatchEntry(cpn,file,Set(newClientEntry)))
    }
  }

  def unWatch (clientEntry: FWClientEntry): Unit = synchronized {
    val cpn = clientEntry.cpn
    watchList.get(cpn) match {
      case Some(we) =>
        val clientEntries = we.clientEntries
        if (clientEntries.contains(clientEntry)) {
          if (clientEntries.size == 1) { // this was the last one, drop this file entry altogether
            watchList = watchList - cpn
          } else {
            val newClientEntries = clientEntries - clientEntry
            watchList = watchList + (cpn -> we.copy(clientEntries = newClientEntries))
          }
          return
        }
      case None => // nothing to unwatch
    }
  }

  /**
    * internal change by one of the watchers - inform all OTHER watchers
    */
  def changedFile (clientEntry: FWClientEntry): Unit = {
    val cpn = clientEntry.cpn
    watchList.get(cpn) match {
      case Some(we) =>
        we.clientEntries.foreach { ce =>
          // don't inform the notifier itself
          if (ce ne clientEntry) ce.client.fileChanged(ce.key)
        }
      case None => // ignore, nobody watching
    }
  }

  /**
    * external file change (might not be a watcher) - inform all watchers
    */
  def changedFile (file: File): Unit = {
    val cpn = file.getCanonicalPath
    watchList.get(cpn) match {
      case Some(we) =>
        we.clientEntries.foreach { ce => ce.client.fileChanged(ce.key)}
      case None => // ignore, nobody watching
    }
  }
}
