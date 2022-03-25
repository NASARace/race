/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.air.filter

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.air._
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.track._
import gov.nasa.race.uom.DateTime

object StaticFlightQueryContext extends TrackQueryContext {
  def queryDate: DateTime = DateTime.now // we don't model time
  def queryTrack(cs: String) = None // we don't have a source for flights
  def queryLocation(id: String) = Airport.allAirports.get(id)
  def reportQueryError(msg: String) = scala.sys.error(msg)
}

/**
  * a filter for scriptable queries of track objects representing flights
  *
  * TODO - this should handle sim time
  */
class FlightQueryFilter(val queryString: String, val ctx: TrackQueryContext, val config: Config=null) extends ConfigurableFilter {

  def this (conf: Config) = this(conf.getStringOrElse("query", ""), StaticFlightQueryContext, conf)
  def this (ctx: TrackQueryContext, conf: Config) = this(conf.getStringOrElse("query", "all"), ctx, conf)

  val parser = new TrackQueryParser(ctx)
  val query: Option[TrackFilter] = parser.parseQuery(queryString) match {
    case parser.Success(result:TrackFilter,_) => Some(result)
    case failure: parser.NoSuccess => ctx.reportQueryError(failure.msg); None
  }

  def passAircraft (ac: Tracked3dObject): Boolean = {
    query match {
      case Some(filter) => filter.pass(ac)(ctx)
      case None => false
    }
  }

  override def pass (o: Any): Boolean = {
    if (o != null) {
      o match {
        case obj: Tracked3dObject => passAircraft(obj)
        case other => false
      }
    } else false
  }
}
