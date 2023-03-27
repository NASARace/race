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

import akka.http.scaladsl.model.headers.`User-Agent`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.http.{CachedFileAssetRoute, ConfigScriptRoute, RaceRouteInfo, ResponseData}
import scalatags.Text

import scala.util.matching.Regex

case class UiTheme (name: String, pathName: String, userAgentMatcher: Option[Regex])

object UiRoute {

  val DEFAULT_THEME = "dark"
  val fallbackTheme = "ui_theme_dark.css"

  val defaultThemes: Seq[UiTheme] = Seq(
    UiTheme( DEFAULT_THEME, "ui_theme_dark.css", None),
    UiTheme( "night", "ui_theme_night.css", None)
  )

  def getThemes (config: Config): Seq[UiTheme] = {
    val themeConfigs: Seq[Config] = config.getConfigSeq("ui-themes")
    if (themeConfigs.isEmpty) {
      defaultThemes
    } else {
      themeConfigs.map { cfg =>
        UiTheme(
          cfg.getString("name"),
          cfg.getString("path"),
          cfg.getOptionalString("user-agent").map( pattern => new Regex(pattern))
        )
      }
    }
  }
}
import gov.nasa.race.ui.UiRoute._

/**
  * a RaceRouteInfo that serves race-ui-client content
  */
trait UiRoute extends  RaceRouteInfo with ConfigScriptRoute with CachedFileAssetRoute {

  protected val themeMap: Map[String,Seq[UiTheme]] = UiRoute.getThemes(config).foldLeft( Map.empty[String,Seq[UiTheme]]) { (m,t) =>
    m.get(t.name) match {
      case Some(list) => m + (t.name -> (t +: list))
      case None => m + (t.name -> Seq(t))
    }
  }

  val themeSourcePath: Option[String] = config.getOptionalString("theme-path")
  addFileAssetResolver(".*ui_theme.*\\.css".r, classOf[UiRoute], isStatic=true, themeSourcePath)

  //--- route handling

  override def route: Route = uiRoute ~ super.route

  def uiRoute: Route = {
    get {
      fileAssetPath("ui.js") ~
      fileAssetPath("ui_util.js") ~
      fileAssetPath("ui_data.js") ~
      fileAssetPath("ui.css") ~
      path( "ui_theme.css") {
        parameterMap { qps =>
          val theme: String = qps.getOrElse("theme", DEFAULT_THEME)
          optionalHeaderValueByType(`User-Agent`) { ua =>
            complete( ResponseData.forExtension( "css", getFileAssetContent( getThemePathName(theme,ua.map(_.value())))))
          }
        }
      }
    }
  }

  def getThemePathName (theme: String, userAgent: Option[String]): String = {
    themeMap.get(theme) match {
      case Some(uiThemes) =>
        userAgent match {
          case Some(ua) =>
            // we choose the first theme with a matching userAgent spec (if no matcher is set that matches every client)
            uiThemes.find( ut=> ut.userAgentMatcher.isEmpty || ut.userAgentMatcher.get.matches(ua)).map(_.pathName).getOrElse(fallbackTheme)
          case None =>
            // no userAgent header - choose the first candidate
            uiThemes.head.pathName
        }
      case None => fallbackTheme
    }
  }

  //--- document content

  // NOTE - this always puts UiRoute resources at the top, which is important for initialization order
  override def getHeaderFragments: Seq[Text.TypedTag[String]] = uiResources ++ super.getHeaderFragments

  def uiResources: Seq[Text.TypedTag[String]] = {
    Seq(
      cssLink("ui_theme.css", "theme"),  // logical URL, resolved in route
      cssLink("ui.css"),

      extModule("ui_data.js"),  // TODO - should this be optional?
      extModule("ui_util.js"),
      extModule("ui.js")
    )
  }
}
