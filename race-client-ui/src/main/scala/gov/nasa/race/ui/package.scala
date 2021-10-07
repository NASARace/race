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
package gov.nasa.race

import gov.nasa.race.util.ClassUtils
import scalatags.Text
import scalatags.Text.all._

/**
  * common types and functions for browser based client UIs
  *
  * this contains mostly convenience functions to reduce the amount of scalatags / ui boilerplate
  */
package object ui {

  type UiID = String

  lazy val htmlHead = scalatags.Text.all.head  // 'head' collides with a lot of other packages

  def cssLink (url:String): Text.TypedTag[String] = link(rel:="stylesheet", tpe:="text/css", href:=url)

  def extScript (url:String): Text.TypedTag[String] = script(src:=url)

  def embeddedScript (code: String): Text.RawFrag = raw(s"""<script>$code</script>""")

  // id required by Cesium scripts
  def fullWindowCesiumContainer(): Text.TypedTag[String] = div(id:="cesiumContainer", cls:="ui_full_window")

  // shortcuts for UI controls
  def uiIcon (src: String, action: String, eid: UiID): Text.TypedTag[String] = div(cls:="ui_icon", id:=eid, data("src"):=src, onclick:=action)

  def uiWindow (title: String, eid: UiID=""): Text.TypedTag[String] = {
    var mods = List(cls:="ui_window", data("title"):=title)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiPanel (title: String, show: Boolean=true, eid: UiID=""): Text.TypedTag[String] = {
    val uiClasses = if (!show)  "ui_panel collapsed" else "ui_panel expanded"
    var mods = List(cls:=uiClasses, data("panel"):=title)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiButton (text: String, action: String, eid: UiID=""): Text.TypedTag[String] = {
    var mods = List(cls:="ui_button", tpe:="button", value:=text, onclick:=action)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    input(mods: _*)
  }

  def uiNumField (label: String, eid: UiID) = {
    var mods = List(cls:="ui_field num", data("id"):=eid, data("label"):= label)
    div(mods: _*)
  }

  lazy val uiScript: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui.js").get
  lazy val uiCSS: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui.css").get
  lazy val uiThemeDarkCSS: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui_theme_dark.css").get
}
