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

/**
  * convenience helper to create http response content, to be used in complete(..) calls from routes
  */
object ResponseData {

  def js(content: Array[Byte]): ToResponseMarshallable = {
    HttpEntity( ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`), content)
  }

  def css(content: Array[Byte]): ToResponseMarshallable = {
    HttpEntity( ContentType(MediaTypes.`text/css`, HttpCharsets.`UTF-8`), content)
  }

  def svg(content: Array[Byte]): ToResponseMarshallable = {
    HttpEntity( MediaTypes.`image/svg+xml`,content)
  }

  def png(content: Array[Byte]): ToResponseMarshallable = {
    HttpEntity( ContentType( MediaType.customBinary("image", "png", Compressible)),content)
  }

  def glb(content: Array[Byte]): ToResponseMarshallable = {
    HttpEntity( ContentType(MediaType.customBinary("model","gltf-binary",Compressible)), content)
  }

  //... and more to follow
}
