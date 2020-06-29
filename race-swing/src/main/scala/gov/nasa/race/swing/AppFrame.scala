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

package gov.nasa.race.swing

import java.awt.{EventQueue, GraphicsEnvironment, Toolkit, Window => AWTWindow}
import java.awt.event.WindowEvent

import javax.swing.WindowConstants

import scala.collection.mutable
import scala.ref.WeakReference
import scala.swing.{Component, Frame}

object AppFrame {
  val appFrames = new mutable.WeakHashMap[AWTWindow,WeakReference[AppFrame]]

  def appFrameOf (c: Component): Option[AppFrame] = {
    val top = c.peer.topLevel
    if (top != null){
      appFrames.get(top) match {
        case Some(wr) => wr.get
        case None => None
      }
    } else None
  }
}
import AppFrame._

/**
 * a Frame with a graceful, non-System.exit() shutdown behavior
 *
 * (see https://docs.oracle.com/javase/8/docs/api/java/awt/doc-files/AWTThreadIssues.html)
 */
class AppFrame extends Frame {
  protected var closing = false

  appFrames.put(peer,new WeakReference(this))

  // this disposes AFTER notifying the listeners - this is important
  // for native components such as JOGLs AWT-AppKit, which otherwise
  // causes a SEGV if we dispose prematurely
  peer.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)

  override def close(): Unit = {
    def _closing = {
      closing = true
      visible = false
      appFrames.remove(peer)
      //Toolkit.getDefaultToolkit.getSystemEventQueue.postEvent(new WindowEvent(peer, WindowEvent.WINDOW_CLOSING))
      //peer.dispatchEvent(new WindowEvent(peer, WindowEvent.WINDOW_CLOSING))
      
      if (Platform.javaVersion == 8 || !Platform.isMacOS) dispose() // this causes segfaults on Java 10/MacOS but without we get X11Util complaints on Java 8/Linux
    }

    if (!closing) {
      if (EventQueue.isDispatchThread) {
        _closing
      } else {
        invokeAndWait(_closing)
      }
    }
  }

  def flushEventQueue() = {
    val queue = Toolkit.getDefaultToolkit.getSystemEventQueue
    while (queue.peekEvent() != null) {
      queue.getNextEvent // remove and discard event
    }
  }

  def toggleFullScreen: Unit = Platform.requestToggleFullScreen(this.peer)

}
