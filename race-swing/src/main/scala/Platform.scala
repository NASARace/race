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

import java.awt.{Window => AWTWindow}
import javax.swing.JPopupMenu
import scala.swing.Frame


/**
 * platform specific functions (keep it low)
 *
 * <2do> if we ever encounter high frequency ops we should turn this into a factory & interface
 */
object Platform {
  final val osName = Symbol(System.getProperty("os.name"))
  final val OS_X = Symbol("Mac OS X")

  def useScreenMenuBar = {
    osName match {
      case OS_X => System.setProperty("apple.laf.useScreenMenuBar", "true")
      case _ => // not relevant
    }
  }

  def enableNativePopups = {
    // should be the same for all OSes
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)
  }

  def enableFullScreen (frame: Frame) = {
    osName match {
      case OS_X => enableOSXFullScreen(frame)
      case _ => // not yet
    }
  }

  def requestFullScreen (frame: Frame) = {
    osName match {
      case OS_X => requestOSXFullScreen(frame)
      case _ => // not yet
    }
  }

  //--- OS X specifics
  def enableOSXFullScreen(frame: Frame): Unit = {
    try {
      val utilCls = Class.forName("com.apple.eawt.FullScreenUtilities")
      val method = utilCls.getMethod("setWindowCanFullScreen", classOf[AWTWindow], java.lang.Boolean.TYPE)
      method.invoke(utilCls, frame.peer, java.lang.Boolean.TRUE);
    } catch {
      case x: Throwable => println(x)
    }
  }

  def requestOSXFullScreen (frame: Frame): Unit = {
    try {
      val appClass = Class.forName("com.apple.eawt.Application")
      val getApplication = appClass.getMethod("getApplication")
      val application = getApplication.invoke(appClass)

      val requestToggleFulLScreen = application.getClass().getMethod("requestToggleFullScreen", classOf[AWTWindow])
      requestToggleFulLScreen.invoke(application, frame.peer)
    } catch {
      case x: Throwable => println(x)
    }
  }
}
