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

  val NoAction = ""
  val NoClass = ""
  val NoId = ""
  val NoIcon = ""
  val NoWidth = ""
  val NoSrc = ""
  val NoPlaceHolder = ""
  val NoDimensions = None

  lazy val htmlHead = scalatags.Text.all.head  // 'head' collides with a lot of other packages

  def cssLink (url:String, eid: UiID=NoId, loadAction:String=NoAction): Text.TypedTag[String] = {
    var mods = List(rel:="stylesheet", tpe:="text/css", href:=url)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    if (loadAction.nonEmpty) mods = (onload:=loadAction) :: mods
    link(mods)
  }

  def extScript (url:String): Text.TypedTag[String] = script(src:=url)
  def extModule (url:String): Text.TypedTag[String] = script(src:=url, tpe:="module")

  def embeddedScript (code: String): Text.RawFrag = raw(s"""<script>$code</script>""")

  //--- various ui specific document fragments

  def uiIcon (src: String, action: String, eid: UiID=NoId): Text.TypedTag[String] = {
    var mods = List(cls:="ui_icon", data("src"):= src, onclick:=action)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiWindow (title: String, eid: UiID=NoId, icon: String=NoIcon): Text.TypedTag[String] = {
    var mods = List(cls:="ui_window", data("title"):=title)
    if (icon.nonEmpty) mods = (data("icon"):=icon) :: mods
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiPanel (title: String, show: Boolean=true, eid: UiID=NoId): Text.TypedTag[String] = {
    val uiClasses = if (!show)  "ui_panel collapsed" else "ui_panel expanded"
    var mods = List(cls:=uiClasses, data("title"):=title)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiButton (text: String, action: String, widthInRem:Double=0, eid: UiID=NoId): Text.TypedTag[String] = {
    var mods = List(cls:="ui_button", tpe:="button", value:=text, onclick:=action)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    if (widthInRem!= 0) mods = (style:=s"width:${widthInRem}rem") :: mods
    input(mods: _*)
  }

  def uiCheckBox (label: String, action: String=NoAction, eid: UiID=NoId, isSelected: Boolean = false): Text.TypedTag[String] = {
    val uiClasses = if (isSelected) "ui_checkbox checked" else "ui_checkbox"
    var mods = List(cls:=uiClasses, data("label"):= label)
    if (action.nonEmpty) mods = (onclick:=action) :: mods
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiRadio (label: String, action: String=NoAction, eid: UiID=NoId): Text.TypedTag[String] = {
    var mods = List(cls:="ui_radio", data("label"):= label)
    if (action.nonEmpty) mods = (onclick:=action) :: mods
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiNumField (label: String, eid: UiID, width: String=NoWidth, labelWidth: String=NoWidth): Text.TypedTag[String] = {
    var mods = List(cls:="ui_field num", data("id"):=eid, data("label"):= label)
    if (width.nonEmpty) mods = (data("width"):=width) :: mods
    if (labelWidth.nonEmpty) mods = (data("label_width"):=labelWidth) :: mods
    div(mods: _*)
  }

  def uiField (label: String, eid: UiID, isFixed: Boolean = false, width: String=NoWidth, labelWidth: String=NoWidth): Text.TypedTag[String] = {
    val classes = if (isFixed) "ui_field fixed" else "ui_field"
    var mods = List(cls:=classes, data("id"):=eid, data("label"):= label)
    if (width.nonEmpty) mods = (data("width"):=width) :: mods
    if (labelWidth.nonEmpty) mods = (data("label_width"):=labelWidth) :: mods
    div(mods: _*)
  }

  def uiFieldGroup (width: String=NoWidth, labelWidth: String=NoWidth): Text.TypedTag[String] = {
    var mods = List(cls:="ui_container column bordered align_right")
    if (width.nonEmpty) mods = (data("width"):=width) :: mods
    if (labelWidth.nonEmpty) mods = (data("label_width"):=labelWidth) :: mods
    div(mods: _*)
  }

  def uiImg (url: String=NoSrc, eid: UiID=NoId, placeHolder: String="no image yet", w:Option[Int] = None, h: Option[Int] = None): Text.TypedTag[String] = {
    var mods = List(cls:="ui_img")
    if (placeHolder.nonEmpty) mods = (alt:=placeHolder) :: mods
    if (url.nonEmpty) mods = (src:=url) :: mods
    ifSome(w){ x=> mods = (widthA:=x.toString) :: mods }
    ifSome(h){ x=> mods = (heightA:=x.toString) :: mods }
    img(mods: _*)
  }

  def uiContainer (layout: String, align: String="", eid: UiID=NoId, title: String="", bordered: Boolean=false): Text.TypedTag[String] = {
    var classes = s"ui_container $layout"
    if (title.nonEmpty) classes += " titled"
    if (bordered) classes += " bordered"

    if (align == "align_left") classes += " align_left"
    else if (align == "align_right") classes += " align_right"
    else if (align == "align_center") classes += " align_center"

    var mods = List(cls:=classes)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    if (title.nonEmpty) mods = (data("title"):=title) :: mods
    div(mods: _*)
  }

  def uiColumnContainer (align: String="", eid: UiID=NoId, title: String="", bordered: Boolean=false): Text.TypedTag[String] = {
    uiContainer("column", align, eid, title, bordered)
  }

  def uiRowContainer (align: String="", eid: UiID=NoId, title: String="", bordered: Boolean=false): Text.TypedTag[String] = {
    uiContainer("row", align, eid, title, bordered)
  }

  def uiTabbedContainer (eid: UiID=NoId, width: String=NoWidth): Text.TypedTag[String] = {
    var mods = List(cls:="ui_tab_container_wrapper")
    if (width.nonEmpty) mods = (style:=s"min-width: $width") :: mods
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiTab (label: String, show: Boolean = false, eid:UiID=NoId): Text.TypedTag[String] = {
    var classes = "ui_tab_container"
    if (show) classes += " show"
    var mods = List(cls:=classes, data("label"):=label)
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  // TODO - suboptimal. In general we want layout&presentation out of the view DSL
  def uiHorizontalSpacer(minWidthInRem: Double): Text.TypedTag[String] = {
    val mods = List(cls:="spacer", style:=s"min-width: ${minWidthInRem}rem")
    div(mods: _*)
  }

  def uiTextInput (label: String, eid: UiID, isFixed: Boolean = false, action: String=NoAction, placeHolder: String="", width: String=NoWidth): Text.TypedTag[String] = {
    var classes = "ui_field text input"
    if (isFixed) classes += " fixed"
    var mods = List(cls:=classes, data("id"):=eid, data("label"):= label)
    if (action.nonEmpty) mods = (onchange:=action) :: mods
    if (placeHolder.nonEmpty) mods = (data("placeholder"):=placeHolder) :: mods
    if (width.nonEmpty) mods = (data("width"):=width) :: mods
    div(mods: _*)
  }

  def uiLabel (eid: UiID, isPermanent: Boolean=false, maxWidthInRem:Int=0, minWidthInRem:Int=0): Text.TypedTag[String] = {
    val classList = if (isPermanent) "ui_label permanent" else "ui_label"
    var mods: List[AttrPair] = List(cls:=classList, id:=eid)
    mods = addRemWidthStyles( mods, minWidthInRem, maxWidthInRem)

    div(mods: _*)
  }

  def genList (eid: UiID, extraCls: String=NoClass, maxRows: Int, maxWidthInRem: Int = 0, minWidthInRem: Int = 0,
               selectAction: String=NoAction, clickAction: String=NoAction, contextMenuAction: String=NoAction, dblClickAction: String=NoAction): Text.TypedTag[String] = {
    val clsList = if (extraCls.isEmpty) "ui_list" else s"ui_list $extraCls"
    var mods = List(cls:=clsList, id:=eid, data("rows"):=maxRows)
    mods = addRemWidthStyles( mods, minWidthInRem, maxWidthInRem)

    if (clickAction.nonEmpty) mods = (onclick:=clickAction) :: mods
    if (dblClickAction.nonEmpty) mods = (ondblclick:=dblClickAction) :: mods
    if (selectAction.nonEmpty) mods = (data("onselect"):=selectAction) :: mods
    if (contextMenuAction.nonEmpty) mods = (oncontextmenu:=contextMenuAction) :: mods

    div(mods: _*)
  }

  def uiList (eid: UiID, maxRows: Int,
              selectAction: String=NoAction, clickAction: String=NoAction, contextMenuAction: String=NoAction, dblClickAction: String=NoAction): Text.TypedTag[String] = {
    genList(eid,NoClass, maxRows,0,0,selectAction,clickAction,contextMenuAction,dblClickAction)
  }

  def uiTreeList (eid: UiID, maxRows: Int, maxWidthInRem: Int = 0, minWidthInRem: Int = 0,
                  selectAction: String=NoAction, clickAction: String=NoAction, contextMenuAction: String=NoAction, dblClickAction: String=NoAction): Text.TypedTag[String] = {
    genList(eid,"ui_tree", maxRows,maxWidthInRem,minWidthInRem,selectAction,clickAction,contextMenuAction,dblClickAction)
  }

  def uiListControls (listId: UiID, first: String=NoAction, up: String=NoAction, down: String=NoAction, last: String=NoAction, clear: String=NoAction): Text.TypedTag[String] = {
    var mods = List(cls:="ui_listcontrols", data("listId"):=listId)
    if (first.nonEmpty) mods = (data("first"):=first) +: mods
    if (up.nonEmpty) mods = (data("up"):=up) +: mods
    if (down.nonEmpty) mods = (data("down"):=down) +: mods
    if (last.nonEmpty) mods = (data("last"):=last) +: mods
    if (clear.nonEmpty) mods = (data("clear"):=clear) +: mods

    div(mods: _*)
  }

  def uiClock (label: String, eid: UiID, tz: String): Text.TypedTag[String] = {
    val mods = List(cls:="ui_clock", data("id"):=eid, data("label"):= label, data("tz"):=tz)
    div(mods: _*)
  }

  def uiTimer (label: String, eid: UiID): Text.TypedTag[String] = {
    val mods = List(cls:="ui_timer", data("id"):=eid, data("label"):= label)
    div(mods: _*)
  }

  def uiPopupMenu (eid: UiID=NoId): Text.TypedTag[String] = {
    var mods = List(cls:="ui_popup_menu")
    if (eid.nonEmpty) mods = (id:=eid) :: mods
    div(mods: _*)
  }

  def uiMenuItem (text: String, action: String=NoAction, eid: UiID=NoId, isChecked: Boolean=false, isDisabled: Boolean=false): Text.TypedTag[String] = {
    var classes = "ui_menuitem"
    if (isDisabled) classes += " disabled"
    if (isChecked) classes += " checked"
    var mods = List(cls:=classes)
    if (action.nonEmpty) mods = (onclick:=action) :: mods
    div(mods: _*)(text)
  }

  def uiSlider (label: String, eid: UiID, changeAction: String=NoAction): Text.TypedTag[String] = {
    var mods = List(cls:="ui_slider", data("id"):=eid, data("label"):=label)
    if (changeAction.nonEmpty) mods = (onchange:=changeAction) :: mods
    div(mods: _*)
  }

  def uiChoice (label: String, eid: UiID, changeAction: String=NoAction): Text.TypedTag[String] = {
    var mods = List(cls:="ui_choice", data("id"):=eid, data("label"):=label)
    if (changeAction.nonEmpty) mods = (onchange:=changeAction) :: mods
    div(mods: _*)
  }

  def uiText (eid: UiID, maxWidthInRem: Int = 0, minWidthInRem: Int = 0,  text: String = ""): Text.TypedTag[String] = {
    var mods: List[AttrPair] = List(cls:="ui_text", id:= eid)
    mods = addRemWidthStyles(mods, minWidthInRem,maxWidthInRem)
    div(mods: _*)(text)
  }

  def uiTextArea (eid: UiID, visCols: Int=0, visRows: Int=0, maxLines:  Int=0, isFixed: Boolean=false, isReadOnly: Boolean=false, isVResizable: Boolean=false): Text.TypedTag[String] = {
    var classes = "ui_textarea"
    if (isReadOnly) classes = classes + " readonly"
    if (isVResizable) classes = classes + " vresize"
    if (isFixed) classes = classes + " fixed"
    var mods = List[AttrPair](cls:=classes, id:= eid)
    if (isReadOnly) mods = readonly :: mods
    if (visCols > 0) mods = (cols:=visCols) :: mods
    if (visRows > 0) mods = (rows:=visRows) :: mods
    if (maxLines > 0) mods = (data("maxlines"):=maxLines) :: mods
    textarea(mods: _*)
  }

  def uiKvTable (eid: UiID, maxRows: Int, maxWidthInRem: Int = 0, minWidthInRem: Int = 0): Text.TypedTag[String] = {
    var mods = List(cls:="ui_kvtable", id:=eid, data("rows"):=maxRows)
    mods = addRemWidthStyles(mods, minWidthInRem, maxWidthInRem)

    table(mods: _*)
  }

  def addRemWidthStyles(mods: List[AttrPair], minWidthInRem: Int, maxWidthInRem: Int): List[AttrPair] = {
    var styleSpecs = "";
    if (maxWidthInRem > 0) styleSpecs = s"max-width:${maxWidthInRem}rem;"
    if (minWidthInRem > 0) styleSpecs += s"min-width:${minWidthInRem}rem;"

    if (styleSpecs.nonEmpty) (style:=styleSpecs) :: mods else mods
  }

  def basicUiModules: Seq[Text.TypedTag[String]] = {
    Seq(
      extModule("ui_data.js"),
      extModule("ui_util.js"),
      extModule("ui.js")
    )
  }
  
  // our client-side artifacts
  lazy val uiScript: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui.js").get
  lazy val uiUtilScript: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui_util.js").get
  lazy val uiDataScript: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui_data.js").get
  lazy val uiCSS: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui.css").get
  lazy val uiThemeDarkCSS: String = ClassUtils.getResourceAsUtf8String(getClass(), "ui_theme_dark.css").get
}
