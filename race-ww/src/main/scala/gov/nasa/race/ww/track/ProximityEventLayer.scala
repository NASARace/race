package gov.nasa.race.ww.track

import java.awt.Color

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race._
import gov.nasa.race.swing.{FieldPanel, GBPanel}
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.track.{ProximityEvent, TrackTerminationMessage, Trajectory}
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.util.{DateTimeUtils, StringUtils}
import gov.nasa.race.ww.{Images, RaceViewer}
import gov.nasa.race.swing.Style._
import gov.nasa.race.util.DateTimeUtils.hhmmss

import scala.swing.Label

class ProximityEntry (o: ProximityEvent, trajectory: Trajectory, layer: ProximityEventLayer)
                                       extends TrackEntry[ProximityEvent](o,trajectory,layer) {
  override def symbolImgScale = 1.0
  override def symbolHeading = 0.0 // no rotation for event symbols
}

class ProximityEntryListView (config: Config) extends TrackEntryListView[ProximityEvent](config) {
  class EntryRenderer extends TrackEntryRenderer[ProximityEvent](config) {
    override protected def setTextLabels (e: TrackEntry[ProximityEvent]): Unit = {
      val obj = e.obj
      idLabel.text = f" ${StringUtils.capLength(obj.cs)(10)}%-10s"
      dateLabel.text = DateTimeUtils.hhmmss.print(obj.date)
      dataLabel.text = f"   ${obj.distance.toMeters}%8.0f"
    }
  }

  override def createTrackEntryRenderer = new EntryRenderer

  override def createColumnHeaderView = {
    new GBPanel {
      val c = new Constraints(fill=Fill.Horizontal)
      //layout(new Label("show  ").styled('fixedHeader)) = c(0,0).anchor(Anchor.West)
      layout(new Label("     id").styled('fixedHeader)) = c(0,0).anchor(Anchor.West).weightx(0.5).gridwidth(2)
      layout(new Label("time   dist [m]").styled('fixedHeader)) = c(2,0).weightx(0).anchor(Anchor.East)
    }.styled()
  }
}

class ProximityEventLayerInfoPanel (raceView: RaceViewer, trackLayer: TrackLayer[ProximityEvent])
                                  extends TrackLayerInfoPanel[ProximityEvent](raceView,trackLayer) {
  override def createTrackEntryListView = new ProximityEntryListView(raceView.config)
}

class ProximityEventEntryPanel (raceView: RaceViewer, trackLayer: TrackLayer[ProximityEvent])
                                            extends TrackEntryPanel[ProximityEvent](raceView,trackLayer) {
  class ProximityEntryFields extends TrackEntryFields[ProximityEvent] {
    val id   = addField("id:")
    val date = addField("date:")
    val pos  = addField("position:")
    val alt  = addField("altitude:")
    addSeparator
    val ref  = addField("ref:")
    val prox = addField("proximity:")
    val dist = addField("distance:")
    setContents

    override def update (obj: ProximityEvent) = {
      id.text = obj.id
      date.text = hhmmss.print(obj.date)
      pos.text = f"${obj.position.φ.toDegrees}%.6f° , ${obj.position.λ.toDegrees}%.6f°"
      alt.text = s"${obj.altitude.toFeet.toInt} ft"
      ref.text = obj.ref.id
      prox.text = obj.track.cs
      dist.text = s"${obj.distance.toMeters.toInt} m"
    }
  }

  override def createFieldPanel = new ProximityEntryFields
}

class ProximityEventLayer (val raceViewer: RaceViewer, val config: Config) extends TrackLayer[ProximityEvent]{

  override def defaultColor = Color.red
  override def defaultSymbolImg = Images.getEventImage(color)

  override def defaultIconThreshold: Length = Meters(100000)
  override def defaultLabelThreshold: Length = Meters(1000000)

  override def getTrackKey(ev: ProximityEvent): String = ev.id
  override def queryLocation(id: String): Option[GeoPosition] = None

  override def createTrackEntry(ev: ProximityEvent) = new ProximityEntry(ev,createTrajectory(ev),this)
  override def createLayerInfoPanel = new ProximityEventLayerInfoPanel(raceViewer,this)
  override def createEntryPanel = new ProximityEventEntryPanel(raceViewer,this)

  def handleProximityEventLayerMessage: Receive = {
    case BusEvent(_,ev: ProximityEvent,_) =>
      incUpdateCount

      getTrackEntry(ev) match {
        case Some(evEntry) =>
          if (ev.isDroppedOrCompleted) removeTrackEntry(evEntry) else updateTrackEntry(evEntry, ev)

        case None => addTrackEntry(ev)
      }

    case BusEvent(_,msg: TrackTerminationMessage,_)  =>
      incUpdateCount
      ifSome(trackEntries.get(msg.cs)) {removeTrackEntry}
  }

  override def handleMessage = handleProximityEventLayerMessage orElse super.handleMessage
}
