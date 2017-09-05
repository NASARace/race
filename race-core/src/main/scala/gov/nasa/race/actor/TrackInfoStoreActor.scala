package gov.nasa.race.actor

import com.typesafe.config.Config
import gov.nasa.race.core.Messages.{BusEvent, ChannelTopicAccept, ChannelTopicRelease, ChannelTopicRequest}
import gov.nasa.race.track._
import gov.nasa.race.core.{ChannelTopicProvider, SubscribingRaceActor}

class TrackInfoStoreActor (val config: Config) extends ChannelTopicProvider with SubscribingRaceActor {

  var activeUpdates = Set.empty[String] // the track ids we publish updates for

  val store = createStore

  protected def createStore: TrackInfoStore = getConfigurable[ConfigurableTrackInfoStore]("store")

  def publish (tInfo: TrackInfo): Unit =  writeTo.foreach { baseChannel => publish( s"$baseChannel/${tInfo.cs}", tInfo) }

  override def handleMessage = {
    case BusEvent(_, msg: Any, _) =>
      store.handleMessage(msg)

    case RequestTrackInfo(cs) =>
      store.get(cs) match {
        case Some(tInfo) => sender ! tInfo
        case None => sender ! NoSuchTrackInfo(cs)
      }
  }

  //--- the ChannelTopicProvider part
  override def isRequestAccepted (request: ChannelTopicRequest) = {
    request.channelTopic.topic match {
      case Some(TrackInfoUpdateRequest(cs)) => true
      case other => false
    }
  }

  override def gotAccept (accept: ChannelTopicAccept) = {
    accept.channelTopic.topic match {
      case Some(TrackInfoUpdateRequest(cs)) =>
        activeUpdates = activeUpdates + cs
        store.get(cs).foreach(publish)
      case other => // ignore
    }
  }

  override def gotRelease (release: ChannelTopicRelease) = {
    release.channelTopic.topic match {
      case rel@Some(TrackInfoUpdateRequest(cs)) =>
        if(!hasClientsForTopic(rel)) activeUpdates = activeUpdates - cs
      case other => // ignore
    }
  }
}
