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
import gov.nasa.race.util.StringUtils
import scalatags.Text

import java.net.InetSocketAddress

object TestKvTableRoute {
  val icon = "test-icon.svg"
  val jsModule = "ui_test_kvtable.js"

  def main (args: Array[String]): Unit = ConsoleMain.main(Array("src/main/resources/testKvTable.conf"))
}
import TestKvTableRoute._

class TestKvTableRoute (val parent: ParentActor, val config: Config) extends DocumentRoute
  with UiRoute with ConfigScriptRoute with CachedFileAssetRoute {

  //--- route
  override def route: Route = uiTestTabsRoute ~ super.route

  def uiTestTabsRoute: Route = {
    get {
      fileAssetPath(jsModule) ~ fileAssetPath(icon)
    }
  }

  //--- document fragments
  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiTestKvTableResources
  override def getBodyFragments: Seq[Text.TypedTag[String]] = super.getBodyFragments ++ Seq(uiTestKvTableWindow(), uiTestKvTableIcon)
  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + testKvTableConfig(requestUri,remoteAddr)

  def uiTestKvTableWindow() : Text.TypedTag[String] = {
    uiWindow("test","test", icon)(
      uiKvTable("test.kv_table", 4, 20)
    )
  }

  def uiTestKvTableIcon: Text.TypedTag[String] = {
    uiIcon(icon, "main.toggleWindow(event,'test')", "test_icon")
  }

  def uiTestKvTableResources: Seq[Text.TypedTag[String]] = {
    Seq( extModule(jsModule))
  }

  def testKvTableConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    val kvs = config.getKeyValuePairsOrElse("kv-list", Seq.empty).map { e=>
      s"['${e._1}',${if (StringUtils.isFractionalNumber(e._2)) e._2 else StringUtils.quote(e._2)}]"
    }

    s"""
export const kvs = {
  data: [${StringUtils.mkSepString( kvs,',')}]
};
     """
  }

}
