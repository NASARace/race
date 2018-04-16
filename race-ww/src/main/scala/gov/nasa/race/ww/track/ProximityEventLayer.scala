package gov.nasa.race.ww.track

import java.awt.Color

import akka.actor.Actor.Receive
import com.typesafe.config.Config
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.ifSome
import gov.nasa.race.track.{ProximityEvent, TrackTerminationMessage, Trajectory}
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.ww.{Images, RaceView}

class ProximityEntry (o: ProximityEvent, trajectory: Trajectory, layer: ProximityEventLayer)
                                       extends TrackEntry[ProximityEvent](o,trajectory,layer) {
  override def symbolImgScale = 1.0
  override def symbolHeading = 0.0 // no rotation for event symbols
}

class ProximityEventLayer (val raceView: RaceView, val config: Config) extends TrackLayer[ProximityEvent]{

  override def defaultColor = Color.red
  override def defaultSymbolImg = Images.getEventImage(color)

  override def defaultIconThreshold: Length = Meters(100000)
  override def defaultLabelThreshold: Length = Meters(1000000)

  override def getTrackKey(ev: ProximityEvent): String = ev.id
  override def queryLocation(id: String): Option[GeoPosition] = None

  override def createTrackEntry(ev: ProximityEvent) = new ProximityEntry(ev,createTrajectory(ev),this)

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
