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
package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.air.translator.ItwsMsgParser
import gov.nasa.race.core.AccumulatingTopicIdProvider
import gov.nasa.race.jms.{JMSImportActor, TranslatingJMSImportActor}
import javax.jms.Message

trait ItwsTopicMapper {
  def topicIdsOf(t: Any): Seq[String] = {
    t match {
      //case "9839" => Seq("9839") // tornado alert
      // lots more that are not precipitatin images

      //--- precipitation images
      case s: String => if (isKnownProduct(s)) Seq(s) else Seq.empty[String]
      case list: Seq[_] => list.map(_.toString).filter(isKnownProduct)

      case _ => Seq.empty[String]
    }
  }

  def isKnownProduct (o: String): Boolean = {
    (o == "9849"            // 5nm
      || o == "9850"        // tracon
      || o == "9905")       // long range
  }
}

/**
  * specialized JMSImportActor for SWIM ITWS messages
  *
  * TODO - this still has to route messages according to message type (range) like RoutingPrecipImageTranslator
  */
class ItwsImportActor(config: Config) extends JMSImportActor(config)
                with TranslatingJMSImportActor with AccumulatingTopicIdProvider with ItwsTopicMapper {

  class FilteringItwsMsgParser extends ItwsMsgParser {
    override def filterProduct(prodId: String): Boolean = !matchesAnyServedTopicId(prodId)
  }

  val parser = new FilteringItwsMsgParser

  override def translate (msg: Message): Any = {
    parser.parse(getContentSlice(msg)).orNull
  }
}
