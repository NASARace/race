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
  final val Linux = Symbol("Linux")
  final val Windows = Symbol("Windows")
  final val UnknownOS = Symbol("Unknown")

  final val javaVersion: Int = getJavaVersion
  final val os: Symbol = getOS

  def getJavaVersion: Int = {
    val s = System.getProperty("java.version")
    if (s.startsWith("1.8")) 8
    else if (s.startsWith("9.")) 9
    else if (s.startsWith("10.")) 10
    else if (s.startsWith("11.")) 11
    else throw new RuntimeException(s"unknown Java version $s")
  }

  def getOS: Symbol = {
    val s = System.getProperty("os.name")
    if (s.startsWith("Linux")) Linux
    else if (s.startsWith("Mac OS X")) OS_X
    else if (s.startsWith("Windows")) Windows
    else UnknownOS
  }

  def isMacOS = os eq OS_X
  def isJava8 = javaVersion == 8

  def useScreenMenuBar = {
    if (isMacOS) {
      System.setProperty("apple.laf.useScreenMenuBar", "true")
    } else {
      // not supported
    }
  }

  def enableNativePopups = {
    // should be the same for all OSes
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)
  }

  def enableFullScreen (frame: Frame) = {
    if (isMacOS && isJava8){
      enableOSXFullScreen(frame)
    } else {
      // not required
    }
  }

  def requestFullScreen (frame: Frame) = {
    if (isMacOS && isJava8) {
      requestOSXFullScreen(frame)
    } else {
      // not supported yet
    }
  }

  //--- OS X specifics (note these cause "illegal reflective access" warnings under Java > 8)

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
