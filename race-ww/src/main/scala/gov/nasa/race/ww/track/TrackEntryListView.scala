/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{GBPanel, ItemRenderPanel, ListItemRenderer, SelectionPreserving, Style, VisibilityBounding}
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.util.{DateTimeUtils, StringUtils}

import scala.swing.{Component, Label, ListView}

/**
  * component to render track entries
  */
class TrackEntryRenderer[T <: TrackedObject](config: Config)  extends ItemRenderPanel[TrackEntry[T]] {
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
  val idLabel = new Label styled('fixedFieldValue)
  val dateLabel = new Label styled('fixedFieldValue)
  val dataLabel = new Label styled('fixedFieldValue)

  val c = new Constraints(fill=Fill.Horizontal,anchor=Anchor.West)
  layout(displayLabel) = c(0,0)
  layout(pathLabel)    = c(1,0)
  layout(infoLabel)    = c(2,0)
  layout(markLabel)    = c(3,0)
  layout(idLabel)      = c(4,0).weightx(0.5)
  layout(dateLabel)    = c(5,0).weightx(0).anchor(Anchor.East)
  layout(dataLabel)    = c(6,0)

  def configure(list: ListView[_], isSelected: Boolean, focused: Boolean, e: TrackEntry[T], index: Int) = {
    displayLabel.icon = if (e.isFocused) centerIcon else if (!e.hasSymbol) hiddenIcon else blankIcon
    pathLabel.icon = if (e.hasPath) pathIcon else blankIcon
    infoLabel.icon = if (e.hasInfo) infoIcon else blankIcon
    markLabel.icon = if (e.hasMark) markIcon else blankIcon

    setTextLabels(e)
  }

  protected def setTextLabels (e: TrackEntry[T]): Unit = {
    val obj = e.obj
    idLabel.text = f" ${StringUtils.capLength(obj.cs)(8)}%-8s"
    dateLabel.text = DateTimeUtils.hhmmss.print(obj.date)
    setDataLabel(obj)
  }

  protected def setDataLabel (obj: T): Unit = {
    val alt = obj.altitude.toFeet.toInt
    val hdg = obj.heading.toNormalizedDegrees.toInt
    val spd = obj.speed.toKnots.toInt
    dataLabel.text = f" $alt%6d $hdg%3d° $spd%4d"
  }

  def createColumnHeaderView: Component = {
    new GBPanel {
      val c = new Constraints(fill=Fill.Horizontal)
      //layout(new Label("show  ").styled('fixedHeader)) = c(0,0).anchor(Anchor.West)
      layout(new Label("     c/s").styled('fixedHeader)) = c(0,0).anchor(Anchor.West).weightx(0.5).gridwidth(2)
      layout(new Label("t-pos   [ft]  hdg [kn]").styled('fixedHeader)) = c(2,0).weightx(0).anchor(Anchor.East)
    }.styled()
  }
}

/**
  * standard ListView for track entries used by TrackLayerInfoPanel
  */
class TrackEntryListView[T <: TrackedObject](val config: Config) extends ListView[TrackEntry[T]]
                                                with VisibilityBounding[TrackEntry[T]]
                                                with SelectionPreserving[TrackEntry[T]] {
  val itemRenderPanel = createTrackEntryRenderer

  maxVisibleRows = config.getIntOrElse("max-rows", 10)
  renderer = new ListItemRenderer(itemRenderPanel)

  //--- override these for specialized track entry rendering and popup menus

  def createTrackEntryRenderer: TrackEntryRenderer[T] = new TrackEntryRenderer[T](config).styled('fieldGrid)

  def createColumnHeaderView: Component = itemRenderPanel.createColumnHeaderView

  def selectAll: Unit = peer.setSelectionInterval(0,peer.getModel.getSize-1)

  def clearSelection: Unit = peer.clearSelection()
}

