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

package gov.nasa.race.track

import gov.nasa.race.common.Query
import gov.nasa.race.geo.{GeoPosition, GeoPositioned, GreatCircle}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._
import gov.nasa.race.util.StringUtils
import gov.nasa.race.uom.DateTime

import scala.concurrent.duration.Duration
import scala.util.matching.Regex
import scala.util.parsing.combinator.RegexParsers

trait TrackQueryContext {
  def queryDate: DateTime
  def queryTrack(id: String): Option[Tracked3dObject]
  def queryLocation(id: String): Option[GeoPositioned]
  def reportQueryError(msg: String): Unit
}

//--- data model
trait TrackFilter {
  def pass(f: Tracked3dObject)(implicit ctx: TrackQueryContext): Boolean
}

/**
  * scriptable track queries - this is the data structure that represents an AST
  * which can be used in a Interpreter-like pattern to determine if tracks match
  * certain criteria.
  */
object TrackQuery {

  // note this is not sealed, subclasses can add additional filters

  //--- pseudo filters
  object AllFilter extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = true
    override def toString = "All"
  }
  object NoneFilter extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = false
    override def toString = "None"
  }

  //--- id filters
  class CsFilter (regex: Regex) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = {
      regex.findFirstIn(f.cs).isDefined
    }
    override def toString = s"Cs($regex)"
  }

  //--- position filters
  class WithinRadiusFilter (pos: GeoPosition, dist: Length) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = {
      GreatCircle.distance(f.position,pos) < dist
    }
    override def toString = s"WithinRadius($pos,$dist)"
  }
  class OutsideRadiusFilter (pos: GeoPosition, dist: Length) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = {
      GreatCircle.distance(f.position,pos) > dist
    }
    override def toString = s"OutsideRadius($pos,$dist)"
  }
  class ProximityFilter(cs: String, dist: Length) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = {
      ctx.queryTrack(cs) match {
        case Some(otherFlight) => GreatCircle.distance(f.position,otherFlight.position) < dist
        case None => false
      }
    }
    override def toString = s"Proximity($cs,$dist)"
  }

  //--- time filters
  class OlderDateFilter (d: DateTime) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext): Boolean = d > f.date
    override def toString = s"Older($d)"
  }
  class YoungerDateFilter (d: DateTime) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext): Boolean = d < f.date
    override def toString = s"Younger($d)"
  }
  class WithinDurationFilter (dur: Duration) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = {
      ctx.queryDate.toEpochMillis - f.date.toEpochMillis < dur.toMillis
    }
    override def toString = s"DateWithin($dur)"
  }
  class OutsideDurationFilter (dur: Duration) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext) = {
      ctx.queryDate.toEpochMillis - f.date.toEpochMillis > dur.toMillis
    }
    override def toString = s"DateOutside($dur)"
  }

  //--- composed filters
  class And (a: TrackFilter, b: TrackFilter) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext): Boolean = a.pass(f) && b.pass(f)
    override def toString = s"And($a,$b)"
  }
  class Or (a: TrackFilter, b: TrackFilter) extends TrackFilter {
    override def pass(f: Tracked3dObject)(implicit ctx:TrackQueryContext): Boolean = a.pass(f) || b.pass(f)
    override def toString = s"Or($a,$b)"
  }
}

/**
  * a combinator parser for basic TrackedObject properties supporting and/or operators and glob patterns
  */
class TrackQueryParser(val ctx: TrackQueryContext)  extends RegexParsers {
  import TrackQuery._

  type Query = TrackFilter

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
    case p1 ~ Some(op@Some(_) ~ p2) => throw new Exception(s"invalid operator $op")  // ?? 2.12 warning - check if bogus
  }

  // this is the main extension point - override to add more filters
  def spec: Parser[Query] = allSpec | noneSpec | csSpec | posSpec | timeSpec | "(" ~> expr <~ ")"

  def allSpec: Parser[Query] = ("all" | "*") ^^^ { AllFilter }

  def noneSpec: Parser[Query] = ("none" | "-") ^^^ { NoneFilter }

  def csSpec: Parser[Query] = "cs" ~ "=" ~ GLOB ^^ {
    case _ ~ _ ~ glob => new CsFilter(StringUtils.globToRegex(glob))
  }

  def posSpec: Parser[Query] = "pos" ~ "<" ~ ID ~ "+" ~ NUM ^^ {
    case _ ~ _ ~ id ~ _ ~ num =>
      val radius = NauticalMiles(num)
      ctx.queryLocation(id) match {
        case Some(loc) => new WithinRadiusFilter(loc.position, radius)
        case None => new ProximityFilter(id, radius) // TODO - sectors etc.
      }
  }

  def timeSpec: Parser[Query] = "t" ~ ("<" | ">") ~ DURATION ^^ {
    case _ ~ op ~ dur => if (op == "<") new WithinDurationFilter(dur) else new OutsideDurationFilter(dur)
  }

  //... TODO - and more to follow


  def parseQuery (input: String): ParseResult[TrackFilter] =  parseAll(expr, input)

}

/**
  * a Query that can run over Iterables of items that contain TrackObjects (e.g TrackEntries)
  */
class TrackQuery[T](val ctx: TrackQueryContext, getTrack: T=>Tracked3dObject) extends Query[T] {

  val parser = new TrackQueryParser(ctx)

  // optimization to avoid recompilation of same query
  protected var lastQuery: String = ""
  protected var lastFilter: Option[T=>Boolean] = None

  override def error (msg: String): Unit = ctx.reportQueryError(msg)

  override def getMatchingItems(query: String, items: Iterable[T]): Iterable[T] = {
    val filter: Option[T=>Boolean] =
      if (query == lastQuery) {
        lastFilter
      } else {
        lastQuery = query
        lastFilter = parser.parseQuery(query) match {
          case parser.Success(f: TrackFilter, _) => Some( (o: T) => f.pass(getTrack(o))(ctx) )
          case failure: parser.NoSuccess => error(failure.msg); None
        }
        lastFilter
      }

    filter match {
      case Some(f) => items.filter(f)
      case None => items
    }
  }
}

