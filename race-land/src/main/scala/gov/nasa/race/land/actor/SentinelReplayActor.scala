package gov.nasa.race.land.actor

import com.typesafe.config.Config
import gov.nasa.race.actor.Replayer
import gov.nasa.race.archive.TaggedArchiveReader
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.land.{Sentinel, SentinelParser}

import java.io.InputStream
import scala.collection.mutable

/**
  * replay actor for Sentinel update archives
  */
class SentinelReplayActor (val config: Config) extends Replayer[TaggedArchiveReader] {
  val sentinels = mutable.Map.empty[Int,Sentinel]  // map of all sentinel states we know of

  class SentinelReader (val iStream: InputStream, val pathName: String="<unknown>", val initBufferSize: Int)
                                                                     extends SentinelParser with TaggedArchiveReader {
    def this(conf: Config) = this(createInputStream(conf), // this takes care of optional compression
      configuredPathName(conf),
      conf.getIntOrElse("buffer-size",8192))

    val updates = mutable.Map.empty[Int,Sentinel] // the set of sentinels changed in this entry

    override protected def parseEntryData(limit: Int): Any = {
      if (initialize(buf,limit)) {
        updates.clear()
        parse().foreach { sensorUpdate =>
          val deviceId = sensorUpdate.deviceId
          val updatedSentinel = sentinels.getOrElseUpdate(deviceId, new Sentinel(deviceId)).updateWith(sensorUpdate)

          updates += deviceId -> updatedSentinel
          sentinels += deviceId -> updatedSentinel
        }
        updates.values.toSeq

      } else Seq.empty[Sentinel]
    }
  }

  override def createReader = new SentinelReader(config)
}
