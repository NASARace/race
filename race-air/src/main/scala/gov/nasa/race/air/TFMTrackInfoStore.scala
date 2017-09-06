package gov.nasa.race.air

import com.typesafe.config.Config
import gov.nasa.race.track.ConfigurableTrackInfoStore

class TFMTrackInfoStore (val config: Config) extends ConfigurableTrackInfoStore {

  val parser = new TFMTrackInfoParser

  override def handleMessage(msg: Any): Boolean = {
    msg match {
      case txt: String =>
        parser.parse(txt) match {
          case Some(tInfos) =>
            tInfos foreach { ti =>
              trackInfos += ti.cs -> ti.accumulate(trackInfos.get(ti.cs))
            }
            true
          case None => false
        }
      case _ => false // not handled
    }
  }
}
