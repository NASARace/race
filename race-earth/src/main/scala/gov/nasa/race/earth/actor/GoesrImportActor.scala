/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.earth.actor

import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race.common.{ActorDataAcquisitionThread, PollingDataAcquisitionThread}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{PublishingRaceActor, RaceContext}
import gov.nasa.race.earth.{GoesRData, GoesrDataReader, GoesRProduct}
import gov.nasa.race.uom.Time.Milliseconds
import gov.nasa.race.uom.{DateTime, Time}
import gov.nasa.race.util.FileUtils
import gov.nasa.race.ifSome
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsRequest, S3Object}

import java.io.File
import java.nio.file.{FileSystems, Path}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.{Success, Failure, Try}


/**
  * the data acquisition thread that downloads the latest S3Objects
  * note this only retrieves the data sets as files but does not parse/process the data yet
  */
class GoesrDataAcquisitionThread(val actorRef: ActorRef,
                                 val satId: Int,
                                 val pollingInterval: Time,
                                 val s3Client: S3Client,
                                 val products: Seq[GoesRProduct],
                                 val dataDir: File
                                 ) extends ActorDataAcquisitionThread(actorRef) with PollingDataAcquisitionThread {

  protected var latestObjs = Map.empty[String,S3Object]

  override protected def poll(): Unit = {
    val now = DateTime.now

    products.foreach { product =>
      ifSome(getLatestObject( now,product)) { obj=>
        ifSome(downloadObject( obj, product)){ file=>
          latestObjs = latestObjs + (product.name -> obj)
          sendToClient( GoesRData( satId, file, product, DateTime.ofInstant(obj.lastModified())))
        }
      }
    }
  }

  def getLatestObject (dt: DateTime, product: GoesRProduct): Option[S3Object] = {
    try {
      var lastObj: S3Object = latestObjs.getOrElse(product.name, null)
      var dtLast = if (lastObj != null) DateTime.ofInstant(lastObj.lastModified()) else DateTime.UndefinedDateTime

      val objPrefix = f"${product.name}/${dt.getYear}/${dt.getDayOfYear}%03d/${dt.getHour}%02d/"
      info(s"checking for new GOES-R data $objPrefix")

      val rb = ListObjectsRequest.builder().bucket(product.bucket).prefix(objPrefix)
      if (lastObj != null) rb.marker(lastObj.key())
      val response = s3Client.listObjects(rb.build())

      response.contents().asScala.toSeq.foreach { o=>
        val dt = DateTime.ofInstant(o.lastModified())
        if (dt > dtLast) {
          dtLast = dt
          lastObj = o
        }
      }

      Option(lastObj)

    } catch {
      case x: Throwable =>
        warning(s"failed to obtain object list: $x")
        None
    }
  }

  def downloadObject (obj: S3Object, product: GoesRProduct): Option[File] = {
    try {
      val path = getFilePath(dataDir.getPath, obj.key())

      if (FileUtils.ensureDir(path.getParent.toFile).isDefined) {
        val file = path.toFile
        if (file.isFile) {
          Some(file) // we already have it

        } else {
          info(s"retrieving GOES-R file: $file")

          val request = GetObjectRequest.builder.bucket(product.bucket).key(obj.key).build
          val response = s3Client.getObject(request, path)

          if (response.contentLength() > 0) {
            Some(path.toFile)
          } else {
            warning(s"empty S3 object: ${obj.key()}")
            None
          }
        }

      } else {
        warning(s"failed to create target directory ${path.getParent}")
        None
      }

    } catch {
      case x: Throwable =>
        warning(s"failed to read S3 object: $x")
        None
    }
  }

  def getFilePath (dir: String, objKey: String): Path = {
    val objPath = objKey.replace('/', '_')  // flatten - we don't want lots of subdirs
    FileSystems.getDefault.getPath( dir, objPath)
  }
}

/**
  * actor to import most recent GOES-16/17/18 data (*.nc files) from S3 buckets
  */
class GoesrImportActor(val config: Config) extends PublishingRaceActor {

  val interval = Milliseconds(config.getDuration("polling-interval").toMillis)
  val satId = config.getInt("satellite")
  val s3Client: Option[S3Client] = createS3Client
  val dataDir = FileUtils.ensureWritableDir(config.getString("data-dir")).get
  val products: Seq[GoesRProduct] = config.getConfigSeq("products").map( createGoesRProduct )

  val keepFiles = config.getBooleanOrElse("keep-files", false)
  val keepEmpty = config.getBooleanOrElse("keep-empty", false) // do we keep data sets without valid fire pixels

  var dataAcquisitionThread: Option[GoesrDataAcquisitionThread]  = None

  def createGoesRProduct(cfg: Config) = {
    val reader = cfg.getOptionalConfig("reader").flatMap( configurable[GoesrDataReader])
    GoesRProduct(cfg.getString("name"), cfg.getString("bucket"), reader)
  }

  override def onInitializeRaceActor(rc: RaceContext, actorConf: Config): Boolean = {
    if (s3Client.isDefined && products.nonEmpty) {
      val thread = new GoesrDataAcquisitionThread( self, satId, interval, s3Client.get, products, dataDir)
      thread.setLogging(this)
      dataAcquisitionThread = Some(thread)
      super.onInitializeRaceActor(rc, actorConf)
    } else false // bail if we don't have an s3Client
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    ifSome(dataAcquisitionThread){ _.start() }
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef): Boolean = {
    ifSome(dataAcquisitionThread){ _.terminate() }
    super.onTerminateRaceActor(originator)
  }

  override def handleMessage: Receive = {
    case r:GoesRData => processData(r)
  }

  def processData (data: GoesRData): Unit = {
    ifSome(data.product.reader){ reader=>
      // files might get big - read async so that we don't block the actor
      Future( Try(reader.read(data)).get ).onComplete {
        case Success(v) =>
          v match {
            case Some(msg) =>
              info(s"publishing GOES-R hotspots")
              publish(msg)
              if (!keepFiles) data.file.delete()
            case None =>
              info(s"no GOES-R hotspots found")
              if (!keepFiles || !keepEmpty) data.file.delete()
          }
        case Failure(e) =>
          warning(s"failed to read GOES-R hotspots: $e")
          if (!keepFiles) data.file.delete()
      }
    }
  }

  def createS3Client: Option[S3Client] = {
    try {
      Some( S3Client.builder()
        .region(getRegion)
        .credentialsProvider(getAwsCredentialsProvider)
        .build()
      )
    } catch {
      case x: Throwable =>
        error(s"failed to create S3 client: $x")
        None
    }
  }

  def getRegion: Region = {
    Region.of(config.getString("s3-region"))
  }

  def getAwsCredentialsProvider: AwsCredentialsProvider = {
    AnonymousCredentialsProvider.create
  }
}

