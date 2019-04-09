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
package gov.nasa.race.http

import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpEntity, _}
import akka.util.ByteString
import com.typesafe.config.Config
import gov.nasa.race.config._
import gov.nasa.race.config.ConfigUtils._

import scala.collection.immutable.{Seq => ISeq}

/**
  * support to build HttpRequest objects out of configs
  */
object HttpRequestBuilder {

  def get (config: Config): Option[HttpRequest] = {
    for (
      uri <- config.getOptionalVaultableString("uri"); // this is actually NOT optional
      method <- requestMethod(config.getStringOrElse("method", "GET"));
      headers <- headers(config.getConfigSeq("headers"));
      entity <- entity(config.getConfigOrElse("entity",NoConfig))
    ) yield HttpRequest(method,uri,headers,entity)
  }

  def headers (hcs: Seq[Config]): Option[ISeq[HttpHeader]] = {
    Some(hcs.toList.flatMap(header))
  }

  def entity (c: Config): Option[RequestEntity] = {
    c.getOptionalVaultableString("content").map { s =>
      c.getStringOrElse("type", "text/plain") match {
        case "application/x-www-form-urlencoded" =>
          HttpEntity(`application/x-www-form-urlencoded`, ByteString(s))
        case "application/json" =>
          HttpEntity(`application/json`, ByteString(s))
        case "application/xml" =>
          HttpEntity(`application/xml` withCharset `UTF-8`, ByteString(s))
        case "test/plain" =>
          HttpEntity(`text/plain` withCharset `UTF-8`, ByteString(s))
      }
    } orElse {
      Some(HttpEntity.Empty)
    }
  }

  def header (hc: Config): Option[HttpHeader] = {
    hc.getStringOrElse("header-name", "") match {
      case "Cookie" =>
        val cookiePairs = hc.getConfigSeq("pairs").map { c: Config =>
          HttpCookiePair(c.getVaultableString("name"), c.getVaultableString("value"))
        }.toList

        Some(new Cookie(cookiePairs))

      // ... and more to follow
      case _ => None
    }
  }

  def requestMethod (m: String): Option[HttpMethod] = m match {
    case "GET" => Some(HttpMethods.GET)
    case "POST" => Some(HttpMethods.POST)
    case "PUT" => Some(HttpMethods.PUT)

    // ... and more to follow
    case _ => None
  }
}
