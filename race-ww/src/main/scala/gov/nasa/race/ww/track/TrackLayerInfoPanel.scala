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

import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.swing.GBPanel._
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{Filler, GBPanel, ItemRenderPanel, ListItemRenderer, SelectionPreserving, Style, VisibilityBounding}
import gov.nasa.race.track.{TrackFilter, TrackQueryContext, TrackQueryParser, TrackedObject}
import gov.nasa.race.util.{DateTimeUtils, StringUtils}
import gov.nasa.race.ww._
import gov.nasa.race.ww.Implicits._

import scala.swing.Swing._
import scala.swing.event._
import scala.swing.{Action, BoxPanel, Button, ButtonGroup, CheckBox, Label, ListView, MenuItem, Orientation, PopupMenu, RadioButton, ScrollPane, TextField}

/**
  * LayerInfoPanel for track layers, which adds a panel to query items of
  * this layer and set displayed items accordingly
  */
class TrackLayerInfoPanel[T <: TrackedObject](raceView: RaceView, trackLayer: TrackLayer[T])
          extends DynamicLayerInfoPanel with TrackQueryContext {

  override def itemsText (l: DynamicRaceLayerInfo) = s"${l.size} / ${matchList.size}"  // we display both total and matching number of items

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

  //-- the match list render panel (needs to be a named class to avoid reflection calls when accessing vals)
  class MatchRenderer extends ItemRenderPanel[TrackEntry[T]] {
    val blankIcon = Style.getIcon('flightNone)
    val centerIcon = Style.getIcon('flightCentered)
    val hiddenIcon = Style.getIcon('flightHidden)
    val pathIcon = Style.getIcon('flightPath)
    val infoIcon = Style.getIcon('flightInfo)
    val markIcon = Style.getIcon('flightMark)

    //--- the icon labels
    val displayLabel = new Label styled()
    val pathLabel = new Label styled()
    val infoLabel = new Label styled()
    val markLabel = new Label styled()

    //--- the text labels
    val csLabel = new Label styled('fixedFieldValue)
    val dateLabel = new Label styled('fixedFieldValue)
    val dataLabel = new Label styled('fixedFieldValue)

    val c = new Constraints(fill=Fill.Horizontal,anchor=Anchor.West)
    layout(displayLabel) = c(0,0)
    layout(pathLabel)    = c(1,0)
    layout(infoLabel)    = c(2,0)
    layout(markLabel)    = c(3,0)
    layout(csLabel)      = c(4,0).weightx(0.5)
    layout(dateLabel)    = c(5,0).weightx(0).anchor(Anchor.East)
    layout(dataLabel)    = c(6,0)

    def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, e: TrackEntry[T], index: Int) = {
      displayLabel.icon = if (e.isCentered) centerIcon else if (!e.hasSymbol) hiddenIcon else blankIcon
      pathLabel.icon = if (e.hasPath) pathIcon else blankIcon
      infoLabel.icon = if (e.hasInfo) infoIcon else blankIcon
      markLabel.icon = if (e.hasMark) markIcon else blankIcon

      val obj = e.obj
      val alt = obj.altitude.toFeet.toInt
      val hdg = obj.heading.toNormalizedDegrees.toInt
      val spd = obj.speed.toKnots.toInt
      csLabel.text = f" ${StringUtils.capLength(obj.cs)(8)}%-8s"
      dateLabel.text = DateTimeUtils.hhmmss.print(obj.date)
      dataLabel.text = f" $alt%6d $hdg%3d° $spd%4d"
    }
  }
  val matchRenderer = new MatchRenderer().styled('fieldGrid)

  val listView = new ListView[TrackEntry[T]]
                       with VisibilityBounding[TrackEntry[T]]
                       with SelectionPreserving[TrackEntry[T]].styled('itemList)
  listView.maxVisibleRows = trackLayer.config.getIntOrElse("max-rows", 10)
  listView.renderer = new ListItemRenderer(matchRenderer)
  val listViewScroller = new ScrollPane(listView).styled('verticalIfNeeded)
  listViewScroller.columnHeaderView = new GBPanel {
    val c = new Constraints(fill=Fill.Horizontal)
    //layout(new Label("show  ").styled('fixedHeader)) = c(0,0).anchor(Anchor.West)
    layout(new Label("     c/s").styled('fixedHeader)) = c(0,0).anchor(Anchor.West).weightx(0.5).gridwidth(2)
    layout(new Label("t-pos   [ft]  hdg [kn]").styled('fixedHeader)) = c(2,0).weightx(0).anchor(Anchor.East)
  }.styled()

  val listPopup = new PopupMenu {
    contents += new MenuItem(Action("select all"){listView.peer.setSelectionInterval(0,matchList.size-1)})
    contents += new MenuItem(Action("clear selection"){listView.peer.clearSelection})
  }

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

  listenTo(queryEntry.keys, listView.mouse.clicks, listView.selection,
           pathCb, infoCb, markCb, displayAllRb, displayMatchesRb, displaySelectedRb)
  reactions += {
    // note that EditDone would also be triggererd by a FocusLost, running the query again if we select a listView item
    case KeyReleased(`queryEntry`, Key.Enter, _,_) => updateMatchList(queryEntry.text)
    case ListSelectionChanged(`listView`,range,live) => updateMatchOptions
    case MousePressed(`listView`,p,_,_,true) => listPopup.show(listView,p.x,p.y)
    case ButtonClicked(`displayAllRb`) => trackLayer.setDisplayFilter(trackLayer.noDisplayFilter)
    case ButtonClicked(`displayMatchesRb`) => trackLayer.setDisplayFilter(matchDisplayFilter)
    case ButtonClicked(`displaySelectedRb`) => trackLayer.setDisplayFilter(selectionDisplayFilter)

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

  //--- TrackQueryContext implementation
  override def now = raceView.updatedSimTime
  override def track (cs: String) = trackLayer.track(cs)
  override def location (id: String) = trackLayer.location(id)
  override def error (msg: String) = trackLayer.error(msg)

  def getTrackQuery(queryString: String): Option[TrackFilter] = {
    if (queryString == null || queryString.isEmpty) {
      None
    } else {
      queryParser.parseQuery(queryString) match {
        case queryParser.Success(filter:TrackFilter, _) => Some(filter)
        case failure: queryParser.NoSuccess => error(failure.msg); None
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
          trackLayer.tracks.foldLeft(Seq.empty[TrackEntry[T]]) { (acc, e) =>
            if (!inViewChecker.isInView(e._2.obj)) acc else _filter(filter, e._2, acc)
          }
        } else {
          trackLayer.tracks.foldLeft(Seq.empty[TrackEntry[T]]) { (acc, e) => _filter(filter, e._2, acc) }
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

  def setSelected (f: (TrackEntry[T])=>Any, action: String): Unit = {
    listView.selection.items.foreach { e =>
      f(e)
      trackLayer.changedTrackEntryOptions(e,action)
    }
    listView.repaint
    trackLayer.redraw
  }

  def setPath(showIt: Boolean) = {
    setSelected(_.setPath(showIt), if (showIt) trackLayer.ShowPath else trackLayer.HidePath)
  }
  def setInfo(showIt: Boolean) = {
    setSelected(_.setInfo(showIt), if (showIt) trackLayer.ShowInfo else trackLayer.HideInfo)
  }
  def setMark(showIt: Boolean) = {
    setSelected(_.setMark(showIt), if (showIt) trackLayer.ShowMark else trackLayer.HideMark)
  }

  def trySelectFlightEntry (e: TrackEntry[T]) = {
    val idx = matchList.indexOf(e)
    if (idx >= 0){
      listView.selectIndices(idx)
      listView.ensureIndexIsVisible(idx)
    }
  }

  def centerSelectedEntry (centerIt: Boolean) = {
    listView.selection.items.headOption.foreach { e =>
      if (centerIt) trackLayer.startCenteringTrackEntry(e)
      else trackLayer.stopCenteringTrackEntry
    }
  }

  def changedFlightEntryOptions = {
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
