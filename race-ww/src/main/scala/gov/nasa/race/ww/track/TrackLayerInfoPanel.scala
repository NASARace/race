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

package gov.nasa.race.ww.track

import java.awt.Insets

import gov.nasa.race.swing.GBPanel._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{Filler, GBPanel}
import gov.nasa.race.track.{TrackFilter, TrackQueryContext, TrackQueryParser, TrackedObject}
import gov.nasa.race.ww.Implicits._
import gov.nasa.race.ww._

import scala.swing.Swing._
import scala.swing.event._
import scala.swing.{Action, BoxPanel, Button, ButtonGroup, CheckBox, MenuItem, Orientation, PopupMenu, RadioButton, ScrollPane, TextField}

/**
  * LayerInfoPanel for track layers, which adds a panel to query items of
  * this layer and set displayed items accordingly
  */
class TrackLayerInfoPanel[T <: TrackedObject](raceView: RaceViewer, trackLayer: TrackLayer[T])
          extends DynamicLayerInfoPanel with TrackQueryContext {

  override def itemsText (l: SubscribingRaceLayer) = s"${l.size} / ${matchList.size}"  // we display both total and matching number of items

  val displayAllRb = new RadioButton("all").styled()
  val displayMatchesRb = new RadioButton("match").styled()
  val displaySelectedRb = new RadioButton("selection").styled()
  val displayGroup = new ButtonGroup(displayAllRb,displayMatchesRb,displaySelectedRb)
  displayAllRb.selected = true

  val displayBox = new BoxPanel(Orientation.Horizontal) {
    border = TitledBorder(EtchedBorder, "display")
    contents ++= Seq(displayAllRb,displayMatchesRb,displaySelectedRb)
  } styled 'titled

  val updateMatchCb = new CheckBox("update").styled()
  val inViewMatchCb = new CheckBox("in view").styled()
  val queryAttrBox = new BoxPanel(Orientation.Vertical) {
    contents ++= Seq(updateMatchCb,inViewMatchCb)
  } styled()

  val queryEntry = new TextField().styled('queryField)
  val queryButton = new Button(Action("query") {
    if (queryEntry.text.isEmpty){queryEntry.text = "all"}
    updateMatchList(queryEntry.text)
  }) styled()


  val listView = createTrackEntryListView.styled('itemList)

  val listViewScroller = new ScrollPane(listView).styled('verticalIfNeeded)
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
  } styled()

  val popUpMenu = createPopupMenu

  listenTo(queryEntry.keys, listView.selection, listView.mouse.clicks,
           pathCb, infoCb, markCb, displayAllRb, displayMatchesRb, displaySelectedRb)
  reactions += {
    // note that EditDone would also be triggererd by a FocusLost, running the query again if we select a listView item
    case KeyReleased(`queryEntry`, Key.Enter, _,_) => updateMatchList(queryEntry.text)
    case ListSelectionChanged(`listView`,range,live) => updateMatchOptions
    case ButtonClicked(`displayAllRb`) => trackLayer.setDisplayFilter(trackLayer.noDisplayFilter)
    case ButtonClicked(`displayMatchesRb`) => trackLayer.setDisplayFilter(matchDisplayFilter)
    case ButtonClicked(`displaySelectedRb`) => trackLayer.setDisplayFilter(selectionDisplayFilter)

    case MousePressed(`listView`,p,_,_,true) => popUpMenu.show(listView,p.x,p.y)
    case MouseClicked(`listView`,_,_,2,false) => raceView.trackUserAction {
      trackLayer.setTrackEntryPanel(lastSelectedMatch)
    }
    case ButtonClicked(`pathCb`) => raceView.trackUserAction { setPath(pathCb.selected) }
    case ButtonClicked(`infoCb`) => raceView.trackUserAction { setInfo(infoCb.selected) }
    case ButtonClicked(`markCb`) => raceView.trackUserAction { setMark(markCb.selected) }
  }

  val matchDisplayFilter: (TrackEntry[T])=>Boolean = (f) => matchList.contains(f)
  val selectionDisplayFilter: (TrackEntry[T])=>Boolean = (f) => listView.selection.items.contains(f)

  val trackSelectorPanel = new GBPanel {
    val c = new Constraints(fill = Fill.Horizontal, insets = new Insets(5,0,0,0))
    layout(displayBox)       = c(0,0).anchor(Anchor.West)
    layout(queryAttrBox)     = c(1,0).anchor(Anchor.East)
    layout(queryEntry)       = c(0,1).weightx(0.5f).anchor(Anchor.West)
    layout(queryButton)      = c(1,1).weightx(0).anchor(Anchor.East)
    layout(listBox)          = c(0,2).weightx(1.0f).gridwidth(2).anchor(Anchor.West)
  }.styled()

  contents += trackSelectorPanel

  var matchList: Seq[TrackEntry[T]] = Seq.empty
  var trackQuery: Option[TrackFilter] = None
  val queryParser = new TrackQueryParser(this)

  //--- override these for specialized item rendering and popup menus

  def createTrackEntryListView: TrackEntryListView[T] = new TrackEntryListView[T](trackLayer.config)

  def createPopupMenu: PopupMenu = {
    val listPeer = listView.peer
    new PopupMenu {
      contents += new MenuItem(Action("select all"){listPeer.setSelectionInterval(0,listPeer.getModel.getSize-1)})
      contents += new MenuItem(Action("clear selection"){listPeer.clearSelection})
    }
  }

  //--- TrackQueryContext implementation (we just forward to our layer)
  override def queryDate = trackLayer.queryDate
  override def queryTrack(cs: String) = trackLayer.queryTrack(cs)
  override def queryLocation(id: String) = trackLayer.queryLocation(id)
  override def reportQueryError(msg: String) = trackLayer.reportQueryError(msg)

  def getTrackQuery(queryString: String): Option[TrackFilter] = {
    if (queryString == null || queryString.isEmpty) {
      None
    } else {
      queryParser.parseQuery(queryString) match {
        case queryParser.Success(filter:TrackFilter, _) => Some(filter)
        case failure: queryParser.NoSuccess => reportQueryError(failure.msg); None
      }
    }
  }

  def getMatchingTrackEntries = {
    trackQuery match {
      case None => Seq.empty
      case Some(filter) =>
        @inline def _filter(query: TrackFilter, e: TrackEntry[T], acc: Seq[TrackEntry[T]]) = {
          if (query.pass(e.obj)(this)) e +: acc else acc
        }
        if (inViewMatchCb.selected) {
          val inViewChecker = raceView.getInViewChecker
          trackLayer.trackEntries.foldLeft(Seq.empty[TrackEntry[T]]) { (acc, e) =>
            if (!inViewChecker.isInView(e._2.obj)) acc else _filter(filter, e._2, acc)
          }
        } else {
          trackLayer.trackEntries.foldLeft(Seq.empty[TrackEntry[T]]) { (acc, e) => _filter(filter, e._2, acc) }
        }.sortWith(_.obj.cs < _.obj.cs)
    }
  }

  def updateMatchList (queryString: String): Unit = {
    trackQuery = getTrackQuery(queryString)
    updateMatchList
  }
  def updateMatchList: Unit = {
    matchList = getMatchingTrackEntries

    if (inheritCb.selected) matchList.foreach(setEntryOptions)

    listView.listData = matchList
    listBox.visible = matchList.size > 0

    if (displayMatchesRb.selected) trackLayer.setDisplayFilter(matchDisplayFilter)
    else if (displaySelectedRb.selected) trackLayer.setDisplayFilter(selectionDisplayFilter)
  }

  def lastSelectedMatch = matchList(listView.selection.leadIndex)

  def removedEntry(e: TrackEntry[T]) = {
    if (matchList.contains(e)){
      matchList = matchList.filterNot( _ eq e)
      listView.listData = matchList
    }
  }

  def setEntryOptions (e: TrackEntry[T]) = {
    e.setPath(pathCb.selected)
    e.setInfo(infoCb.selected)
    e.setMark(markCb.selected)
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
        pathCb.selected = e.hasPath
        infoCb.selected = e.hasInfo
        markCb.selected = e.hasMark
      }
    }
  }

  protected def setSelected (f: (TrackEntry[T])=>Unit): Unit = {
    listView.selection.items.foreach { f }
    listView.repaint
    trackLayer.redraw
  }

  def setPath(showIt: Boolean) = setSelected { e => trackLayer.setPath(e,showIt) }
  def setInfo(showIt: Boolean) = setSelected { e => trackLayer.setInfo(e,showIt) }
  def setMark(showIt: Boolean) = setSelected { e => trackLayer.setMark(e,showIt) }

  // we don't allow to set/unset focus objects here because this is a global setting
  // that can only be applied to one object at a time

  def trySelectTrackEntry(e: TrackEntry[T]) = {
    val idx = matchList.indexOf(e)
    if (idx >= 0){
      listView.selectIndices(idx)
      listView.ensureIndexIsVisible(idx)
    }
  }

  // notification that track entry attributes have been changed externally
  def updateTrackEntryAttributes = {
    listView.repaint
    updateMatchOptions
  }

  override def processTimerEvent = {
    super.processTimerEvent
    if (updateMatchCb.selected){
      updateMatchList
    }
  }
}
