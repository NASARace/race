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

package gov.nasa.race.data

import gov.nasa.race.common.StringUtils
import org.joda.time.DateTime
import squants._
import squants.space.NauticalMiles

import scala.concurrent.duration.Duration
import scala.util.matching.Regex
import scala.util.parsing.combinator.RegexParsers

trait FlightQueryContext {
  def now: DateTime
  def flight (cs: String): Option[InFlightAircraft]
  def airport (id: String): Option[Airport]
  def error (msg: String): Unit
}

object StaticFlightQueryContext extends FlightQueryContext {
  def now = DateTime.now() // we don't model time
  def flight (cs: String) = None // we don't have a source for flights
  def airport (id: String) = Airport.allAirports.get(id)
  def error (msg: String) = scala.sys.error(msg)
}


//--- data model
trait FlightFilter {
  def pass (f: InFlightAircraft)(implicit ctx: FlightQueryContext): Boolean
}

/**
  * scriptable flight queries - this is the data structure that represents an AST
  * which can be used in a Interpreter-like pattern to determine if flights match
  * certain criteria.
  */
object FlightQuery {

  // note that this is not sealed, subclasses can add additional filters

  //--- pseudo filters
  object AllFilter extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = true
    override def toString = "All"
  }
  object NoneFilter extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = false
    override def toString = "None"
  }

  //--- id filters
  class CsFilter (regex: Regex) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = {
      regex.findFirstIn(f.cs).isDefined
    }
    override def toString = s"Cs($regex)"
  }

  //--- position filters
  class WithinRadiusFilter (pos: LatLonPos, dist: Length) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = {
      GreatCircle.distance(f.position,pos) < dist
    }
    override def toString = s"WithinRadius($pos,$dist)"
  }
  class OutsideRadiusFilter (pos: LatLonPos, dist: Length) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = {
      GreatCircle.distance(f.position,pos) > dist
    }
    override def toString = s"OutsideRadius($pos,$dist)"
  }
  class FlightProximityFilter (cs: String, dist: Length) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = {
      ctx.flight(cs) match {
        case Some(otherFlight) => GreatCircle.distance(f.position,otherFlight.position) < dist
        case None => false
      }
    }
    override def toString = s"FlightProximity($cs,$dist)"
  }

  //--- time filters
  class OlderDateFilter (d: DateTime) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = d.isAfter(f.date)
    override def toString = s"Older($d)"
  }
  class YoungerDateFilter (d: DateTime) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = d.isBefore(f.date)
    override def toString = s"Younger($d)"
  }
  class WithinDurationFilter (dur: Duration) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = {
      ctx.now.getMillis - f.date.getMillis < dur.toMillis
    }
    override def toString = s"DateWithin($dur)"
  }
  class OutsideDurationFilter (dur: Duration) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = {
      ctx.now.getMillis - f.date.getMillis > dur.toMillis
    }
    override def toString = s"DateOutside($dur)"
  }

  //--- composed filters
  class And (a: FlightFilter, b: FlightFilter) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = a.pass(f) && b.pass(f)
    override def toString = s"And($a,$b)"
  }
  class Or (a: FlightFilter, b: FlightFilter) extends FlightFilter {
    override def pass (f: InFlightAircraft)(implicit ctx:FlightQueryContext) = a.pass(f) || b.pass(f)
    override def toString = s"Or($a,$b)"
  }
}

class FlightQueryParser (val ctx: FlightQueryContext)  extends RegexParsers {
  import FlightQuery._

  type Query = FlightFilter

  //--- terminal symbols
  def GLOB: Parser[String] ="""[a-zA-Z0-9\*]+""".r ^^ { _.toString }
  def NUM: Parser[Double] = """\d+(\.\d*)?""".r ^^ { _.toDouble }
  def LONG: Parser[Long] = """\d+""".r ^^ { _.toLong }
  def ID: Parser[String] = """[a-zA-Z0-9]+""".r ^^ { _.toString }
  def DURATION: Parser[Duration] = """\d+(?:s|min\h)""".r ^^ { Duration.create(_) }

  //--- non-terminals

  def expr: Parser[Query] = spec ~ opt(opt("&" | "|") ~ spec) ^^ {
    case p1 ~ None => p1
    case p1 ~ Some(None ~ p2) => new And(p1, p2)
    case p1 ~ Some(Some("&") ~ p2) => new And(p1, p2)
    case p1 ~ Some(Some("|") ~ p2) => new Or(p1, p2)
  }

  // this is the main extension point - override to add more filters
  def spec: Parser[Query] = allSpec | noneSpec | csSpec | posSpec | timeSpec | "(" ~> expr <~ ")"

  def allSpec: Parser[Query] = ("all" | "*") ^^^ { AllFilter }

  def noneSpec: Parser[Query] = "none" ^^^ { NoneFilter }

  def csSpec: Parser[Query] = "cs" ~ "=" ~ GLOB ^^ {
    case _ ~ _ ~ glob => new CsFilter(StringUtils.globToRegex(glob))
  }

  def posSpec: Parser[Query] = "pos" ~ "<" ~ ID ~ "+" ~ NUM ^^ {
    case _ ~ _ ~ id ~ _ ~ num =>
      val radius = NauticalMiles(num)
      ctx.airport(id) match {
        case Some(airport) => new WithinRadiusFilter(airport.pos, radius)
        case None => new FlightProximityFilter(id, radius) // TODO - sectors etc.
      }
  }

  def timeSpec: Parser[Query] = "t" ~ ("<" | ">") ~ DURATION ^^ {
    case _ ~ "<" ~ dur => new WithinDurationFilter(dur)
    case _ ~ ">" ~ dur => new OutsideDurationFilter(dur)
  }

  //... TODO - and more to follow

  def parseQuery (input: String): ParseResult[FlightFilter] = parseAll(expr, input)

  def apply(input: String): Query = parseAll(expr, input) match {
    case Success(result, _) => result
    case failure: NoSuccess => scala.sys.error(failure.msg)
  }
}

