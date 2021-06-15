/*
 * Copyright (c) 2019, United States Government, as represented by the
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
package gov.nasa.race.ww

import java.awt.Insets

import gov.nasa.race.swing.GBPanel._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{Filler, GBPanel, ItemRenderPanel, ListItemRenderer, SelectionPreserving, Style, VisibilityBounding}
import gov.nasa.race.util.{SeqUtils, StringUtils}
import gov.nasa.race.ww.LayerObjectAttribute.LayerObjectAttribute

import scala.swing.Swing._
import scala.swing.event._
import scala.swing.{Action, BoxPanel, Button, ButtonGroup, CheckBox, Component, Label, ListView, MenuItem, Orientation, PopupMenu, RadioButton, ScrollPane, TextField}


/**
  * a LayerInfoPanel that supports (delegated) queries and result lists that
  * can be used to select and control display of matching entries
  */
class InteractiveLayerInfoPanel[T <: LayerObject](override val layer: InteractiveRaceLayer[T])
                                                               extends DynamicLayerInfoPanel(layer) {

  class LayerObjectListView extends ListView[T]
                                         with VisibilityBounding[T] with SelectionPreserving[T]{
    class LayerObjectRenderer extends ItemRenderPanel[T] {

      //--- the standard icons
      val iconClr = Style.getIconColor("default")
      val blankIcon = Images.blankFlightIcon
      val centerIcon = Images.getFlightCenteredIcon(iconClr)
      val hiddenIcon = Images.getFlightHiddenIcon(iconClr)
      val pathIcon = Images.getFlightPathIcon(iconClr)
      val infoIcon = Images.getFlightInfoIcon(iconClr)
      val markIcon = Images.getFlightMarkIcon(iconClr)

      //--- the icon labels
      val displayLabel = new Label().styled()
      val pathLabel = new Label().styled()
      val infoLabel = new Label().styled()
      val markLabel = new Label().styled()

      //--- the text labels
      val idLabel = new Label().styled("fixedFieldValue")
      val dateLabel = new Label().styled("fixedFieldValue")
      val dataLabel = new Label().styled("fixedFieldValue")

      val c = new Constraints(fill=Fill.Horizontal,anchor=Anchor.West)
      layout(displayLabel) = c(0,0)
      layout(pathLabel)    = c(1,0)
      layout(infoLabel)    = c(2,0)
      layout(markLabel)    = c(3,0)
      layout(idLabel)      = c(4,0).weightx(0.5)
      layout(dataLabel)    = c(5,0).weightx(0).anchor(Anchor.East)

      def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, e: T, index: Int) = {
        displayLabel.icon = if (e.isFocused) centerIcon else if (!e.isVisible) hiddenIcon else blankIcon
        pathLabel.icon = if (e.isAttrSet(LayerObjectAttribute.Path)) pathIcon else blankIcon
        infoLabel.icon = if (e.isAttrSet(LayerObjectAttribute.Info)) infoIcon else blankIcon
        markLabel.icon = if (e.isAttrSet(LayerObjectAttribute.Mark)) markIcon else blankIcon

        idLabel.text = f" ${StringUtils.capLength(layer.layerObjectIdText(e))(8)}%-8s"
        dataLabel.text = layer.layerObjectDataText(e)
      }
    }

    renderer = new ListItemRenderer(new LayerObjectRenderer)

    // this has to match the renderer
    def createColumnHeaderView: Component = {
      val idTxt = f"     ${StringUtils.capLength(layer.layerObjectIdHeader)(8)}%-8s"
      val dataTxt = layer.layerObjectDataHeader

      new GBPanel {
        val c = new Constraints(fill=Fill.Horizontal)
        layout(new Label(idTxt).styled("fixedHeader")) = c(0,0).anchor(Anchor.West).weightx(0.5).gridwidth(2)
        layout(new Label(dataTxt).styled("fixedHeader")) = c(2,0).weightx(0).anchor(Anchor.East)
      }.styled()
    }

    def selectAll: Unit = peer.setSelectionInterval(0,peer.getModel.getSize-1)

    def clearSelection: Unit = peer.clearSelection()
  }

  //--- model data

  var matchList: Seq[T] = Seq.empty

  //--- UI elements for query and matchList display

  val displayAllRb = new RadioButton("all").styled()
  val displayMatchesRb = new RadioButton("match").styled()
  val displaySelectedRb = new RadioButton("selection").styled()
  val displayGroup = new ButtonGroup(displayAllRb,displayMatchesRb,displaySelectedRb)
  displayAllRb.selected = true

  val displayBox = new BoxPanel(Orientation.Horizontal) {
    border = TitledBorder(EtchedBorder, "display")
    contents ++= Seq(displayAllRb,displayMatchesRb,displaySelectedRb)
  }.styled("titled")

  val updateMatchCb = new CheckBox("update").styled()
  val inViewMatchCb = new CheckBox("in view").styled()
  val queryAttrBox = new BoxPanel(Orientation.Vertical) {
    contents ++= Seq(updateMatchCb,inViewMatchCb)
  }.styled()

  val queryEntry = new TextField().styled("queryField")
  val queryButton = new Button(Action("query") {
    if (queryEntry.text.isEmpty){queryEntry.text = "all"}
    updateMatchList(queryEntry.text)
  }).styled()

  val listView: LayerObjectListView = createMatchListView
  listView.maxVisibleRows = layer.maxLayerObjectRows

  val listViewScroller = new ScrollPane(listView).styled("verticalIfNeeded")
  listViewScroller.columnHeaderView = listView.createColumnHeaderView

  val pathCb = new CheckBox("path").styled()
  val infoCb = new CheckBox("info").styled()
  val markCb = new CheckBox("mark").styled()
  val inheritCb = new CheckBox("inherit").styled()

  val optionBox = new GBPanel {
    val c = new Constraints(insets = new Insets(5, 0, 0, 0), anchor = Anchor.West)
    layout(pathCb) = c(0,0)
    layout(infoCb) = c(1,0)
    layout(markCb) = c(2,0)
    layout(new Filler().styled()) = c(3,0).weightx(0.5)
    layout(inheritCb) = c(4,0).weightx(0)
  }.styled()

  val listBox = new BoxPanel(Orientation.Vertical){
    contents ++= Seq(listViewScroller, optionBox)
    visible = false
  }.styled()

  val popUpMenu = createPopupMenu

  listenTo(queryEntry.keys, listView.selection, listView.mouse.clicks,
    pathCb, infoCb, markCb, displayAllRb, displayMatchesRb, displaySelectedRb)
  reactions += {
    // note that EditDone would also be triggererd by a FocusLost, running the query again if we select a listView item
    case KeyReleased(`queryEntry`, Key.Enter, _,_) => updateMatchList(queryEntry.text)
    case ListSelectionChanged(`listView`,range,live) => updateMatchOptions
    case ButtonClicked(`displayAllRb`) => layer.filterLayerObjectDisplay(None)
    case ButtonClicked(`displayMatchesRb`) => layer.filterLayerObjectDisplay(Some(matchDisplayFilter))
    case ButtonClicked(`displaySelectedRb`) => layer.filterLayerObjectDisplay(Some(selectionDisplayFilter))

    case MousePressed(`listView`,p,_,_,true) => popUpMenu.show(listView,p.x,p.y)
    case MouseClicked(`listView`,_,_,2,false) => lastSelectedMatch.foreach(layer.doubleClickLayerObject)

    case ButtonClicked(`pathCb`) => setSelectedAttr(LayerObjectAttribute.Path,pathCb.selected)
    case ButtonClicked(`infoCb`) => setSelectedAttr(LayerObjectAttribute.Info,infoCb.selected)
    case ButtonClicked(`markCb`) => setSelectedAttr(LayerObjectAttribute.Mark,markCb.selected)
  }

  val matchDisplayFilter: T=>Boolean = (f) => matchList.contains(f)
  val selectionDisplayFilter: T=>Boolean = (f) => listView.selection.items.contains(f)

  val trackSelectorPanel = new GBPanel {
    val c = new Constraints(fill = Fill.Horizontal, insets = new Insets(5,0,0,0))
    layout(displayBox)       = c(0,0).anchor(Anchor.West)
    layout(queryAttrBox)     = c(1,0).anchor(Anchor.East)
    layout(queryEntry)       = c(0,1).weightx(0.5f).anchor(Anchor.West)
    layout(queryButton)      = c(1,1).weightx(0).anchor(Anchor.East)
    layout(listBox)          = c(0,2).weightx(1.0f).gridwidth(2).anchor(Anchor.West)
  }.styled()

  contents += trackSelectorPanel

  def raceViewer = layer.raceViewer

  //--- overridable init methods

  def createMatchListView: LayerObjectListView = new LayerObjectListView().styled("itemList")

  def createPopupMenu: PopupMenu = {
    new PopupMenu {
      contents += new MenuItem(Action("select all"){listView.selectAll}).styled()
      contents += new MenuItem(Action("clear selection"){listView.clearSelection}).styled()
    }
  }

  //--- entry and matchList management

  def sortById (a: T, b: T): Boolean = a.id < b.id

  def lastSelectedMatch: Option[T] = {
    val sel = listView.selection
    if (sel.leadIndex >= 0) Some(matchList(sel.leadIndex)) else None
  }

  def getVisibleEntries (entries: Iterable[T]): Iterable[T] = {
    if (entries.nonEmpty && inViewMatchCb.selected) {
      val inViewChecker = raceViewer.getInViewChecker
      entries.filter(e=> inViewChecker.isInView(e.pos.position) )
    } else {
      entries
    }
  }

  def updateMatchList (queryString: String): Unit = {
    val candidates = layer.layerObjectQuery.getMatchingItems(queryString,layer.layerObjects)
    matchList = SeqUtils.sortedSeq( getVisibleEntries(candidates))(sortById)

    if (inheritCb.selected) matchList.foreach(setEntryOptions)

    listView.listData = matchList
    listBox.visible = matchList.size > 0

    if (displayMatchesRb.selected) {
      layer.layerObjects.foreach { e =>
        val isMatch = matchList.contains(e) // TODO - not very efficient
        if (e.isVisible != isMatch) e.setVisible(isMatch)
      }
    } else if (displaySelectedRb.selected) {
      layer.layerObjects.foreach { e =>
        if (!e.isVisible) e.setVisible(true)
      }
    }
  }

  def updateMatchList: Unit = updateMatchList(queryEntry.text)

  def setSelectedAttr (attr: LayerObjectAttribute, showIt: Boolean): Unit = {
    listView.selection.items.foreach { e=> layer.setLayerObjectAttribute(e,attr,showIt) }
    //listView.repaint
    //layer.redraw
  }

  def setEntryOptions (e: LayerObject) = {
    e.setAttr(LayerObjectAttribute.Path, pathCb.selected)
    e.setAttr(LayerObjectAttribute.Info, infoCb.selected)
    e.setAttr(LayerObjectAttribute.Mark, markCb.selected)
  }

  def updateMatchOptions = {
    val sel = listView.selection.items

    if (inheritCb.selected){ // options -> selection
      sel.foreach(setEntryOptions)

    } else {  // selected -> options
      if (sel.isEmpty) {
        pathCb.selected = false
        infoCb.selected = false
        markCb.selected = false
      } else {
        val e = sel.head
        pathCb.selected = e.isAttrSet(LayerObjectAttribute.Path)
        infoCb.selected = e.isAttrSet(LayerObjectAttribute.Info)
        markCb.selected = e.isAttrSet(LayerObjectAttribute.Mark)
      }
    }
  }

  def trySelectEntry(e: T) = {
    val idx = matchList.indexOf(e)
    if (idx >= 0){
      listView.selectIndices(idx)
      listView.ensureIndexIsVisible(idx)
    }
  }

  def removedAllEntries = {
    matchList = Seq.empty[T]
    listView.listData = matchList
  }

  def removedEntry(e: T) = {
    if (matchList.contains(e)){
      matchList = matchList.filterNot( _ eq e)
      listView.listData = matchList
    }
  }

  // notification that track entry attributes have been changed externally
  def updateEntryAttributes = {
    listView.repaint()
    updateMatchOptions
  }

  override def processTimerEvent = {
    super.processTimerEvent
    if (updateMatchCb.selected){
      updateMatchList
    }
  }
}
