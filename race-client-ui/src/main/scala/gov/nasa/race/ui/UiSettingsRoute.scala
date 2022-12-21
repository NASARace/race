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
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import gov.nasa.race.http.{CachedFileAssetRoute, ConfigScriptRoute}
import scalatags.Text

import java.net.InetSocketAddress


/**
  * a route that has support for interactive render settings such as themes
  */
trait UiSettingsRoute extends UiRoute with ConfigScriptRoute with CachedFileAssetRoute {

  override def route: Route = uiSettingsRoute ~ super.route

  def uiSettingsRoute: Route = {
    get {
      fileAssetPath ("settings-icon.svg") ~
        fileAssetPath ("ui_settings.js")
    }
  }

  def uiSettingsIcon: Text.TypedTag[String] = {
    uiIcon("settings-icon.svg", "main.toggleWindow(event,'settings')", "settings_icon")
  }

  def uiSettingsWindow: Text.TypedTag[String] = {
    uiWindow("Settings", "settings", "settings-icon.svg")(
      uiRowContainer("align_center")(
        uiChoice("theme","settings.theme", "main.selectTheme(event)"),
        uiButton("Edit", "main.editTheme()"),
        uiButton("Remove", "main.removeLocalTheme()", eid="settings.remove")
      ),
      uiPanel("theme vars", false)(
        uiColumnContainer("align_right")(
          uiList("settings.themeVars", 20, "main.selectThemeVar(event)"),
          uiTextInput("value", "settings.value", false, "main.themeVarChange(event)","", "15rem")
        ),
        uiRowContainer()(
          uiButton("Save", "main.saveLocalTheme()", eid="settings.save")
        )
      )
    )
  }

  override def getBodyFragments: Seq[Text.TypedTag[String]] = {
    super.getBodyFragments ++ Seq(uiSettingsWindow, uiSettingsIcon)
  }

  override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    super.getConfig(requestUri,remoteAddr) + uiSettingsConfig(requestUri,remoteAddr)
  }

  def uiSettingsConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
    themeMap.keys.mkString("export const serverThemes = ['", "','", "'];")
  }

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ uiSettingsResources

  def uiSettingsResources: Seq[Text.TypedTag[String]] = {
    Seq(
      extModule("ui_settings.js")
    )
  }
}
