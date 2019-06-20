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

import akka.http.scaladsl.model.{HttpEntity, HttpMethods}
import gov.nasa.race.test.RaceSpec
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * unit test for HttpRequestBuilder
  */
class HttpRequestBuilderSpec extends AnyWordSpecLike with RaceSpec {

  "HttpRequestBuilder" should {
    "create minimal GET request" in {
      val uri = "http://foo.com/bar?baz=faz&shmaz=taz"
      val conf = createConfig(s"""uri = "${uri}" """)
      val req = HttpRequestBuilder.get(conf)

      assert(req.isDefined)
      val r = req.get
      println(s"request = $r")
      r.method shouldBe(HttpMethods.GET)
      r.uri.toString shouldBe(uri)
      r.entity shouldBe(HttpEntity.Empty)
    }

    "create a login POST request" in {
      val conf =
        createConfig("""
          method = "POST"
          uri = "http://foo.com/login"
          entity = {
            type = "application/x-www-form-urlencoded"
            content = "identity=me&password=supersecret"
          }
        """)

      val req = HttpRequestBuilder.get(conf)
      assert(req.isDefined)
      val r = req.get
      println(s"post request = $r")
    }

    "create request headers with cookies" in {
      val conf =
        createConfig("""
          uri = "http://foo.com/date?param=whatever"
          headers = [
            { header-name = "Cookie"
              pairs = [
                { name = "chocolatechip", value = "ABCDEFG" },
                { name = "Path", value = "/foo" }
              ]
            }
          ]
        """)
      val req = HttpRequestBuilder.get(conf)
      assert(req.isDefined)
      val r = req.get
      println(s"get request with headers = $r")

    }
  }
}
