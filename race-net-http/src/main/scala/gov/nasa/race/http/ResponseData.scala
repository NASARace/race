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
package gov.nasa.race.http

import akka.http.scaladsl.model.MediaType.Compressible
import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaType, MediaTypes}
import gov.nasa.race.util.FileUtils

/**
  * convenience helper to create http response content, to be used in complete(..) calls from routes
  */
object ResponseData {

  val byteContentType: ContentType = ContentType(MediaTypes.`application/octet-stream`)
  val jsContentType: ContentType = ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`) 
  val glslContentType: ContentType = ContentType(MediaTypes.`text/plain`, HttpCharsets.`UTF-8`)

  val map: Map[String,ContentType] = Map(
    "html" -> ContentType(MediaTypes.`text/html`, HttpCharsets.`UTF-8`),
    "xml" -> ContentType(MediaTypes.`text/xml`, HttpCharsets.`UTF-8`),
    "txt" -> ContentType(MediaTypes.`text/plain`, HttpCharsets.`UTF-8`),
    "json" -> MediaTypes.`application/json`,
    "geojson" -> MediaTypes.`application/json`,  // ?? why is there no application/geo+json
    "csv" -> ContentType(MediaTypes.`text/csv`, HttpCharsets.`UTF-8`),
    "js" -> jsContentType,
    "css" -> ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`),
    "svg" -> MediaTypes.`image/svg+xml`,
    "png" -> ContentType( MediaType.customBinary("image", "png", Compressible)),
    "jpeg" -> ContentType( MediaType.customBinary("image", "jpeg", Compressible)),
    "glb" -> ContentType(MediaType.customBinary("model","gltf-binary",Compressible)),
    "kml" -> ContentType(MediaTypes.`application/vnd.google-earth.kml+xml`, HttpCharsets.`UTF-8`),
    "kmz" -> ContentType(MediaTypes.`application/vnd.google-earth.kmz`),
    "frag" -> glslContentType,
    "vert" -> glslContentType,
    "nc" -> byteContentType,
    "webp" -> ContentType(MediaTypes.`image/webp`)

    //... and many more to follow
  )

  def contentTypeForExtension (ext: String): ContentType = {
    map.get(ext) match {
      case Some(contentType) => contentType
      case None => ContentType(MediaTypes.`application/octet-stream`)
    }
  }

  def forExtension (ext: String, content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( contentTypeForExtension(ext), content)
  }

  def forPathName (pathName: String, content: Array[Byte]): HttpEntity.Strict = {
    forExtension( FileUtils.getExtension(pathName), content)
  }

  def bytes (content: Array[Byte]): HttpEntity.Strict = HttpEntity( byteContentType, content)

  def js (content: Array[Byte]): HttpEntity.Strict = HttpEntity( jsContentType, content)

  def glsl (content: Array[Byte]): HttpEntity.Strict = HttpEntity( glslContentType, content)

  //... and the others to follow
}
