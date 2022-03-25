package gov.nasa.race.track

import gov.nasa.race.uom.DateTime
import gov.nasa.race.{Dated, IdentifiableObject}
import scala.collection.Seq

/**
  * base type for in-flight related messages
  */
trait TrackMessage {
  def id: String
  def cs: String
  def date: DateTime

  def source: Option[String] = None // override if message is associated to a source
}

trait TrackListMessage {
  def tracks: Seq[Tracked3dObject]
}

trait TrackTerminationMessage extends TrackMessage

/**
  * track was regularly completed by reaching target destination
  */
case class TrackCompleted(id: String,
                          cs: String,
                          arrivalPoint: String,
                          date: DateTime,
                          override val source: Option[String] = None) extends Dated with IdentifiableObject with TrackTerminationMessage

/**
  * track was dropped (no updates received for a given duration)
  */
case class TrackDropped(id: String,
                        cs: String,
                        date: DateTime,
                        override val source: Option[String] = None) extends Dated with IdentifiableObject with TrackTerminationMessage

/**
  * track changed call sign
  */
case class TrackCsChanged(id: String,
                          cs: String,
                          oldCS: String,
                          date: DateTime) extends Dated with IdentifiableObject