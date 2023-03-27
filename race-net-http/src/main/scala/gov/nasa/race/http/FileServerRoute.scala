/*
 * Copyright (c) 2023, United States Government, as represented by the
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
package gov.nasa.race.http

import akka.http.javadsl.model.headers.ContentEncoding
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.{ContentType, HttpEntity, HttpHeader, StatusCodes}
import akka.http.scaladsl.model.headers.HttpEncoding
import akka.http.scaladsl.server.Directives.{complete, encodeResponseWith}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Source
import akka.util.ByteString
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.util.FileUtils

import java.io.{File, FileInputStream, InputStream}


/**
 * common functions for RaceRouteInfos serving files
 * this mostly factors out how to transmit file content (strict/chunked, compressed,uncompressed)
 *
 * TODO - cross-check with FsCachedProxy and CachedFileAsset
 */
trait FileServerRoute extends RaceRouteInfo {

  // selected (uncompressed) text formats exceeding this size get gzipped
  val compressFileSizeThreshold = config.getIntOrElse( "compress-threshold", 256 * 1024)

  // everything larger than 1MB is not served as Strict content
  val strictFileSizeThreshold = config.getIntOrElse( "strict-threshold", 1024*1024)

  // chunk size for non-strict transfer
  val chunkSize = config.getIntOrElse("chunk-size", 65536)

  /**
   * complete with file content that depending on file size might get transferred either strict or compressed
   * (if the file extension does not indicate that it already is compressed)
   */
  def completeWithFileContent (file: File): Route = {
    val ext = FileUtils.getExtension(file)
    val contentType = ResponseData.contentTypeForExtension(ext)

    def readChunk(is: InputStream, buf: Array[Byte]): Option[HttpEntity.ChunkStreamPart] = {
      val nRead = is.read(buf)
      if (nRead < 0) None else Some(ByteString.fromArray(buf,0,nRead))
    }

    def completeChunked (is: InputStream, hdrs: Seq[HttpHeader] = Seq.empty): Route = {
      val buf = new Array[Byte](chunkSize)
      val src = Source.unfoldResource[HttpEntity.ChunkStreamPart,InputStream](
        () => is,
        is => readChunk(is, buf),
        is => is.close()
      )
      if (hdrs.nonEmpty) {
        complete(StatusCodes.OK, hdrs, HttpEntity.Chunked(contentType, src))
      } else {
        complete(StatusCodes.OK, HttpEntity.Chunked(contentType, src))
      }
    }

    def completeStrict (file: File, hdrs: Seq[HttpHeader] = Seq.empty): Route = {
      FileUtils.fileContentsAsBytes(file) match {
        case Some(bs) => complete( StatusCodes.OK, hdrs, HttpEntity.Strict(contentType, ByteString(bs)))
        case None => complete( StatusCodes.NotFound) // no data
      }
    }

    if (file.isFile) {
      val len = file.length()

      ext match {
        case "gz" | "kmz" => // we let the browser handle the compression by adding a Content-Encoding header
          val hdrs = Seq(ContentEncoding.create(HttpEncoding("gzip")))

          if (len > strictFileSizeThreshold) {
            completeChunked(new FileInputStream(file), hdrs)
          } else {
            completeStrict( file, hdrs)
          }

        case "js" | "json" | "geojson" | "csv" | "kml" | "xml" =>  // text formats that can benefit from compression (more to follow..)
          if (len > compressFileSizeThreshold) {
            encodeResponseWith(Coders.Gzip) {
              if (len > strictFileSizeThreshold) {
                completeChunked(new FileInputStream(file))
              } else {
                completeStrict( file)
              }
            }
          } else {
            completeStrict( file)
          }

        case _ => // we don't compress, but we still might chunk
          if (len > strictFileSizeThreshold) {
            completeChunked(new FileInputStream(file))
          } else {
            completeStrict( file)
          }
      }

    } else {
      complete(StatusCodes.NotFound)
    }
  }
}
