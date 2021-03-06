/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.Uri.Path

import scalatags.generic.TypedTag
import scalatags.text.{Builder => STBuilder}

/**
  * package gov.nasa.race.http contains actors to serve and retrieve http data
  */
package object http {
  type HtmlElement = TypedTag[STBuilder,String,String]
  type HtmlResources = Map[String,ToResponseMarshallable]

  final val RootPath = Path("/")

  case object SendHttpRequest
  case class SendNewHttpRequest(request: HttpRequest)
}
