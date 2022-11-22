package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.{Failure, SuccessValue}
import gov.nasa.race.actor.Replayer
import gov.nasa.race.air.IffTrackPointParser
import gov.nasa.race.archive.{ArchiveEntry, StreamArchiveReader}
import gov.nasa.race.common.ConfigurableStreamCreator.{configuredPathName, createInputStream}
import gov.nasa.race.common.LineBuffer
import gov.nasa.race.config.ConfigUtils.ConfigWrapper

import java.io.InputStream

class IffTrackReplayActor (val config: Config) extends Replayer {
  type R = IffTrackArchiveReader
  override def createReader = new IffTrackArchiveReader(config)
}

class IffTrackArchiveReader (val iStream: InputStream, val pathName: String="<unknown>", bufLen: Int)
                                                    extends StreamArchiveReader with IffTrackPointParser {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf), conf.getIntOrElse("buffer-size",4096))

  val lineBuffer = new LineBuffer(iStream,bufLen)

  override def readNextEntry(): Option[ArchiveEntry] = {
    while (lineBuffer.nextLine()) {  // we read until we find a track
      if (initialize(lineBuffer)) {
        readNextValue().toInt match {
          case 3 =>
            parseTrackPoint() match {
              case SuccessValue(track) => return archiveEntry(track.date, track) // there is no separate replay time
              case Failure(_) => // ignore
            }
          case 2 => // not yet
          case 4 => // not yet
          case _ => // ignore
        }
      }
    }
    None
  }
}