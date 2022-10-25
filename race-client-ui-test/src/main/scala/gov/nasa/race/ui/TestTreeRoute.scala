/*
 * Copyright (c) 2022, United States Government, as represented by the
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
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.ParentActor
import gov.nasa.race.http.{CachedFileAssetRoute, ConfigScriptRoute, DocumentRoute}
import gov.nasa.race.main.ConsoleMain
import scalatags.Text

import java.net.InetSocketAddress

object TestTreeRoute {
  val icon = "test-icon.svg"
  val jsModule = "ui_test_tree.js"

  def main (args: Array[String]): Unit = ConsoleMain.main(Array("src/main/resources/testTree.conf"))
}
import TestTreeRoute._

/**
 * integration test for tree list client-ui components
 * this includes both server-side document creation and communication between client and server
 */
class TestTreeRoute  (val parent: ParentActor, val config: Config) extends DocumentRoute
  with UiRoute with ConfigScriptRoute with CachedFileAssetRoute {

  //--- route
  override def route: Route = uiTestRoute ~ super.route

  def uiTestRoute: Route = {
    get {
      fileAssetPath(jsModule) ~ fileAssetPath(icon)
    }
  }

  //--- document fragments
  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiTestResources
  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiTestWindow, uiTestIcon)
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + testConfig(requestUri,remoteAddr)

  def uiTestWindow : Text.TypedTag[String] = {
    uiWindow("test","test", icon)(
      uiPanel("layers", true)(
        uiTreeList(eid = "test.tree", maxRows = 10, minWidthInRem = 20, selectAction = "main.selectSource(event)")
      )
    )
  }

  def uiTestIcon: Text.TypedTag[String] = {
    uiIcon(icon, "main.toggleWindow(event,'test')", "test_icon")
  }

  def uiTestResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule(jsModule))
  }

  def testConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val srcs = config.getConfigSeq("sources").map( jsFromConfig)
    s"""export const sources = [\n${srcs.mkString(",\n")}\n];"""
  }

  def jsFromConfig (cfg: Config): String = {
    val pathName = cfg.getString("pathname")
    s"{ pathName: '$pathName' }"
  }
}
