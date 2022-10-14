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

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.MediaType.Compressible
import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaType, MediaTypes}
import gov.nasa.race.util.FileUtils

/**
  * convenience helper to create http response content, to be used in complete(..) calls from routes
  */
object ResponseData {

  val map: Map[String,Array[Byte]=>HttpEntity.Strict] = Map(
    "html" -> html,
    "xml" -> xml,
    "txt" -> txt,
    "json" -> json,
    "geojson" -> json,
    "csv" -> csv,
    "js" -> js,
    "css" -> css,
    "svg" -> svg,
    "png" -> png,
    "jpeg" -> jpeg,
    "glb" -> glb,
    "kml" -> kml,
    "kmz" -> kmz,
    "frag" -> glsl,
    "vert" -> glsl,
    "nc" -> bytes,
    "webp" -> webp

    //... and many more
  )

  def forExtension (ext: String, content: Array[Byte]): HttpEntity.Strict = {
    map.get(ext) match {
      case Some(f) => f(content)
      case None => HttpEntity(ContentType(MediaTypes.`application/octet-stream`), content)
    }
  }

  def forPathName (pathName: String, content: Array[Byte]): HttpEntity.Strict = {
    forExtension( FileUtils.getExtension(pathName), content)
  }

  def html (content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`text/html`, HttpCharsets.`UTF-8`), content)
  }

  def xml (content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`text/xml`, HttpCharsets.`UTF-8`), content)
  }

  def txt (content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`text/plain`, HttpCharsets.`UTF-8`), content)
  }

  def csv (content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`text/csv`, HttpCharsets.`UTF-8`), content)
  }

  def json (content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( MediaTypes.`application/json`, content)
  }

  def js(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), content)
  }

  def css(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), content)
  }

  def svg(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( MediaTypes.`image/svg+xml`,content)
  }

  def png(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType( MediaType.customBinary("image", "png", Compressible)),content)
  }

  def jpeg(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType( MediaType.customBinary("image", "jpeg", Compressible)),content)
  }

  def glb(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaType.customBinary("model","gltf-binary",Compressible)), content)
  }

  def kml(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`application/vnd.google-earth.kml+xml`, HttpCharsets.`UTF-8`), content)
  }

  def kmz(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`application/vnd.google-earth.kmz`), content)
  }

  def glsl(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`text/plain`, HttpCharsets.`UTF-8`), content) // ?? not sure there is one for frag
  }

  def webp(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity( ContentType(MediaTypes.`image/webp`), content)
  }

  def bytes(content: Array[Byte]): HttpEntity.Strict = {
    HttpEntity(ContentType(MediaTypes.`application/octet-stream`), content)
  }

  //... and (many) more to follow
}
