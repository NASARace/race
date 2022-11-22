/*
 * Copyright (c) 2020, United States Government, as represented by the
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

import akka.NotUsed
import akka.http.scaladsl.model.ws.Message
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race.config.ConfigUtils._

/**
  * abstract akka stream type that hold a queue source to which we want to explicitly push
  *
  * TODO - should we move this to Source.queue(bufSize) which will cause offer() to synchronously
  * return a QueueOfferResult so that we can handle in the sender?
  */
trait SourceQueueOwner[T] {

  val config: Config
  implicit val materializer: Materializer

  val srcBufSize = config.getIntOrElse("source-queue", 4096) // we can get quite a lot of initial messages during connects

  val srcPolicy = config.getStringOrElse( "source-policy", "fail") match {
    case "dropHead" => OverflowStrategy.dropHead
    //case "dropNew" => OverflowStrategy.dropNew // dropNew is deprecated -> use Source.queue(srcBufSize) to materialize
    case "dropTail" => OverflowStrategy.dropTail
    case "dropBuffer" => OverflowStrategy.dropBuffer
    case "fail" => OverflowStrategy.fail
    case other => throw new ConfigException.Generic(s"unsupported source buffer overflow strategy: $other")
  }

  def createSourceQueue: Source[T, SourceQueueWithComplete[T]] = Source.queue[T](srcBufSize, srcPolicy)

  def createPreMaterializedSourceQueue: (SourceQueueWithComplete[T], Source[T, NotUsed]) = createSourceQueue.preMaterialize()

}
