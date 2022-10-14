/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.ui

import akka.http.scaladsl.model.Uri
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{CachedFileAssetRoute, ConfigScriptRoute, DocumentRoute}
import gov.nasa.race.main.{ConsoleMain, ConsoleMainBase}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.get
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.util.StringUtils
import scalatags.Text

import java.net.InetSocketAddress

/**
 * test server for tabbed UI containers
 */
object TestTabsRoute {
  val icon = "test-icon.svg"
  val jsModule = "ui_test_tabs.js"

  def main (args: Array[String]): Unit = ConsoleMain.main(Array("src/main/resources/testTabs.conf"))
}
import TestTabsRoute._

/**
 * a simple single window route to test tabbed containers
 *
 * Note this could be refactored into a test base but this also serves as a RaceRouteInfo example
 */
class TestTabsRoute (val parent: ParentActor, val config: Config) extends DocumentRoute
                                                    with UiRoute with ConfigScriptRoute with CachedFileAssetRoute {

  //--- route
  override def route: Route = uiTestTabsRoute ~ super.route

  def uiTestTabsRoute: Route = {
    get {
      fileAssetPath(jsModule) ~ fileAssetPath(icon)
    }
  }

  //--- document fragments
  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiTestTabsResources
  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiTestTabsWindow(), uiTestTabsIcon)
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + testTabsConfig(requestUri,remoteAddr)

  def uiTestTabsWindow() : Text.TypedTag[String] = {
    uiWindow("tab test","tabs", icon)(
      uiTabbedContainer(width = "17rem")(
        uiTab("first", true)(
          uiRowContainer("align_center")(
            uiList("tabs.numbers", 5),
            uiColumnContainer()(
              uiButton("click me",NoAction),
              uiButton("don't click me",NoAction)
            )
          )
        ),
        uiTab("second")(
          uiList("tabs.files", 5)
        )
      )
    )
  }

  def uiTestTabsIcon: Text.TypedTag[String] = {
    uiIcon(icon, "main.toggleWindow(event,'tabs')", "tabs_icon")
  }

  def uiTestTabsResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule(jsModule))
  }

  def testTabsConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val cfg = config.getConfig("tabs")
    val numbers = cfg.getStringSeq("numbers")
    val files = cfg.getStringSeq("files")

    s"""
export const tabs = {
  numbers: [${StringUtils.mkString( numbers,',')(s=> s"'$s'")}],
  files: [${StringUtils.mkString( files,',')(s=> s"'$s'")}],
};
     """
  }
}