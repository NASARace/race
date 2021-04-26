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

import java.awt.{GraphicsDevice, GraphicsEnvironment, Frame => AWTFrame, Window => AWTWindow}

import javax.swing.JPopupMenu

import scala.swing.Frame


/**
  * platform specific functions (keep it small!)
  *
  * the fullscreen support requires a separate gov.nasa.race.swing.MacOSHelper class
  * (which uses com.apple.eawt.* to work around a nasty fullscreen bug with Java 9+ - see
  * comments in MacOSHelper source). Since this class can only be built on macOS we
  * cannot keep it in the sources and provide a pre-built jar as a un-managed dependency that
  * is only used in case we run RACE on macOS (com.apple.eawt does not export the
  * required classes and cross-module reflection is not allowed on Java 9+)
  *
  */
object Platform {
  final val osName = Symbol(System.getProperty("os.name"))
  final val OS_X = Symbol("Mac OS X")
  final val Linux = Symbol("Linux")
  final val Windows = Symbol("Windows")
  final val UnknownOS = Symbol("Unknown")

  final val javaVersion: Int = getJavaVersion
  final val os: Symbol = getOS

  private var fullScreenWindow: AWTWindow = _

  def getJavaVersion: Int = {
    val s = System.getProperty("java.specification.version")
    if (s.startsWith("1.8")) 8 else Integer.parseInt(s)
  }

  def getOS: Symbol = {
    val s = System.getProperty("os.name")
    if (s.startsWith("Linux")) Linux
    else if (s.startsWith("Mac OS X")) OS_X
    else if (s.startsWith("Windows")) Windows
    else UnknownOS
  }

  def isMacOS = os eq OS_X  // TODO - revisit this since it runs into "not exported" IllegalAccessExceptions for com.apple.eawt
  def isJava8 = javaVersion == 8

  def useScreenMenuBar = {
    if (isMacOS) {
      System.setProperty("apple.laf.useScreenMenuBar", "true")
    } else {
      // not supported
    }
  }

  def getScreenDevice: GraphicsDevice = {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment
    env.getDefaultScreenDevice
  }

  def enableNativePopups = {
    // should be the same for all OSes
    JPopupMenu.setDefaultLightWeightPopupEnabled(false)
  }

  def enableLightweightPopups = {
    JPopupMenu.setDefaultLightWeightPopupEnabled(true)
  }

  def isFullScreen: Boolean = {
    getScreenDevice.getFullScreenWindow != null
  }

  def enableFullScreen (frame: Frame) = {
    if (getScreenDevice.isFullScreenSupported) {
      if (isMacOS) {
        MacOSHelper.enableMacOSFullScreen(frame.peer)
      } else {
        // not required
      }
    }
  }

  def requestToggleFullScreen(frame: AWTFrame) = {

    if (isMacOS) {
      MacOSHelper.requestToggleMacOSFullScreen(frame)
      if (fullScreenWindow == null) fullScreenWindow = frame else fullScreenWindow = null

    } else {
      // this is supposed to be the portable Java way to request fullscreen but
      // at least on macOS 10.13.6 it does not allow to switch between workspaces
      // and disables popups, i.e. can cause a user lockout
      // update: in fullscreen mode popups also don't show on 10.14.6, regardless of calling enableLightweightPopups

      val device = getScreenDevice

      if (device.isFullScreenSupported) {

        if (fullScreenWindow == null) { // set this frame fullscreen
          fullScreenWindow = frame
          try {
            device.setFullScreenWindow(frame)
          } catch {
            case _:Throwable => device.setFullScreenWindow(null)
          }

        } else { // reset fullscreen
          fullScreenWindow = null
          try {
            device.setFullScreenWindow(null)
          } catch {
            case _:Throwable => // nothing we can do
          }
        }
      }
    }

  }
}
