package gov.nasa.race.earth.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.actor.Replayer
import gov.nasa.race.archive.{ArchiveEntry, ArchiveReader}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.config.SubConfigurable
import gov.nasa.race.earth._
import gov.nasa.race.uom.Time.Seconds
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.{FileUtils, ThreadUtils}

import java.util.concurrent.LinkedBlockingQueue
import scala.concurrent.duration.DurationInt

/**
 * the "archive" is just a directory that contains GOES-R data sets (*.nc files)
 * (see GoesRDirReader for filename format)
 * (replay) date is part of the filename
 */
class GoesrNcArchiveReader(val config: Config) extends ArchiveReader with GoesrDirReader with SubConfigurable {
  val satId = config.getInt("satellite")
  val dataDir = FileUtils.ensureDir(config.getString("data-dir")).get
  val products: Seq[GoesRProduct] = config.getConfigSeq("products").map( createGoesRProduct )

  val dataSets = getGoesRData(dataDir.getPath, products, satId, DateTime.Date0, DateTime.NeverDateTime)
  val it = dataSets.iterator

  override def hasMoreArchivedData: Boolean = it.hasNext
  override def readNextEntry(): Option[ArchiveEntry] = it.nextOption().map( d=> new ArchiveEntry(d.date, d))
  override val pathName: String = dataDir.getPath
  override def close(): Unit = {} // nothing to close

  def createGoesRProduct(cfg: Config) = {
    val reader = cfg.getOptionalConfig("reader").flatMap( configurable[GoesrDataReader])
    GoesRProduct(cfg.getString("name"), cfg.getString("bucket"), reader)
  }
}

/**
 * Replay actor that replays GOES-R data files stored in an archive directory
 * "archives" are just directories with *.nc data files. Note that processing these can be expensive and
 * hence should not block the actor
 * We could speed up considerably if we process data sets in parallel but we still do have to publish them sequentially
 * and this is only a bottleneck during init of replays with a long history. Processing this in parallel could also consume
 * considerable resources by NetCDF
 */
class GoesrNcReplayActor(val config: Config) extends Replayer {
  type R = ArchiveReader

  class DataThread extends Thread {
    setDaemon(true)
    private var isDone = false

    override def run(): Unit = {
      var dLast = DateTime.Date0

      while (!isDone) {
        try {
          val data = dataQueue.take() // this blocks until we have something to process
          if (data.date >= dLast) {  // make sure we don't replay out-of-order
            processData(data)
            dLast = data.date
            ThreadUtils.yieldExecution() // make sure we don't starve everybody
          } else warning(s"ignoring out of order data set $data")
        } catch {
          case _: InterruptedException => // just a breaker
        }
      }
    }

    def processData (data: GoesRData): Unit = {
      for ( reader <- data.product.reader; result <- reader.read(data) ) {
        result match {
          case hs:GoesrHotspots => if (hs.nonEmpty) publish(hs)
          case other => publish(other)
        }
      }
    }

    def terminate(): Unit = {
      isDone = true
      this.interrupt()
    }
  }

  override def createReader = new GoesrNcArchiveReader(config) // watch out - executed before we enter this ctor

  val hotspotHistory: Time = Seconds(config.getFiniteDurationOrElse("history", 7.days).toSeconds)
  val startDate = currentSimTime - hotspotHistory
  val dataThread = new DataThread
  val dataQueue = new LinkedBlockingQueue[GoesRData]()

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    dataThread.start()
    super.onStartRaceActor(originator) && scheduleFirst(0)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    dataThread.terminate()
    super.onTerminateRaceActor(originator)
  }

  override protected def skipEntry (e: ArchiveEntry): Boolean = {
    e.date < startDate
  }

  override protected def replay (msg: Any): Unit = {
    msg match {
      case data: GoesRData => dataQueue.put(data)
    }
  }
}
