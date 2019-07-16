/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.tool

import java.io._

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.gis.{GisItem, GisItemDB, GisItemDBFactory}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.uom.Length
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.DateTime

import scala.Console

/**
  * command line application to create, analyze and query RACE GistItemDBs
  */
object GisItemDBTool {

  object Op extends Enumeration {
    val CreateDB, ShowStruct, ShowItems, ShowStrings, ShowKey, ShowNearest, ShowRange = Value
  }

  //-------------------------------------

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    val posRE = """(-?\d+\.\d+),(-?\d+\.\d+)""".r
    val rangeRE = """(-?\d+\.\d+),(-?\d+\.\d+),(\d+)""".r

    var factoryClass: Option[Class[_ <:GisItemDBFactory[_ <: GisItem]]] = None
    var inFile: Option[File] = None
    var outFile: Option[File] = None
    var key: Option[String] = None
    var pos: Option[GeoPosition] = None
    var dist: Option[Length] = None
    var nItems: Option[Int] = None
    var op: Op.Value = Op.CreateDB
    var date: DateTime = DateTime.now
    var passArgs: Seq[String] = List.empty

    opt0("-i", "--show-struct")("show structure of DB") {
      op = Op.ShowStruct
    }
    opt0("--show-strings")("show string table contents of DB") {
      op = Op.ShowStrings
    }
    opt0("--show-items")("show item list of DB") {
      op = Op.ShowItems
    }
    opt1("-k","--show-key")("<key>","show item for given key"){ a=>
      op = Op.ShowKey
      key = Some(a)
    }
    opt1("-n","--show-near")("<lat,lon>","show item(s) nearest to given pos"){ a=>
      op = Op.ShowNearest
      a match {
        case posRE(slat,slon) =>
          pos = Some(GeoPosition.fromDegrees(slat.toDouble,slon.toDouble))
      }
    }
    opt1("-m", "--max-items")("<number>", "max number of items"){ a=>
      nItems = Some(a.toInt)
    }
    opt1("-r", "--show-range")("<lat,lon,meters>", "show items in given range"){ a=>
      op = Op.ShowRange
      a match {
        case rangeRE(slat,slon,smeters) =>
          pos = Some(GeoPosition.fromDegrees(slat.toDouble,slon.toDouble))
          dist = Some(Meters(smeters.toDouble))
      }
    }

    opt1("-o", "--out")("<pathname>", "pathname of file to generate"){ a=>
      outFile = parseFileOption(a)
    }
    opt1("-f", "--in")("<pathName>", "input file") { a =>
      inFile = parseExistingFileOption(a)
    }

    opt1("--date")("<dateSpec>", "date to be stored in generated DB"){ a=>
      date = DateTime.parseYMDT(a)
    }

    opt1("-x", "--xarg")("<argString>", "extra arguments to be passed to concrete DB factory"){ a=>
      passArgs = passArgs:+ a
    }

    requiredArg1("<clsName>", "concrete GisItemDBFactory class (e.g. gov.nasa.race.air.gis.LandingSite$)") { a=>
      try {
        val cls = Class.forName(a)
        if (!classOf[GisItemDBFactory[_]].isAssignableFrom(cls)) {
          Console.err.println(s"error - not a GisItemDBFactory class: $a")
        } else {
          factoryClass = Some(cls.asInstanceOf[Class[_ <: GisItemDBFactory[_ <: GisItem]]])
        }
      } catch {
        case x:Throwable => Console.err.println(s"error retrieving factory class: $x")
      }
    }
  }

  def getOutFile (fact: GisItemDBFactory[_], opts: Opts): File = {
    opts.outFile match {
      case Some(file) => file
      case None =>
        val fname = fact.schema.substring(fact.schema.lastIndexOf('.')) + ".rgis"
        new File("tmp", fname)
    }
  }

  def getFactoryObject (opts: Opts): Option[GisItemDBFactory[_]] = {
    try {
      opts.factoryClass.map { cls =>
        if (cls.getName.endsWith("$")) { // companion object
          val mf = cls.getField("MODULE$")
          mf.get(null).asInstanceOf[GisItemDBFactory[_]]
        } else {
          cls.getDeclaredConstructor().newInstance().asInstanceOf[GisItemDBFactory[_]]
        }
      }
    } catch {
      case x: Throwable =>
        x.printStackTrace()
        println(s"error getting ${opts.factoryClass} instance: $x")
        None
    }
  }

  var opts = new Opts

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      opts.op match {
        case Op.CreateDB => createDB
        case Op.ShowStruct => processDB( _.printStructure)
        case Op.ShowStrings => processDB( _.printStrings)
        case Op.ShowItems => processDB( _.printItems(opts.nItems))
        case Op.ShowKey => processDB(showKey)
        case Op.ShowNearest => processDB(showNearest)
        case Op.ShowRange => processDB(showRange)
        case other => println(s"unknown operation $other")
      }
    }
  }

  def createDB: Unit = {
    for (factory <- getFactoryObject(opts) ) {
      val outFile = getOutFile(factory,opts)

      if (opts.inFile match {
        case Some(srcFile) => factory.createDB(outFile,srcFile,opts.passArgs,opts.date)
        case None => factory.createDB(outFile,opts.passArgs,opts.date)
      }){
        println(s"file created: $outFile (${outFile.length/1024} kB)")
      } else {
        println(s"ERROR could not create ${factory.schema} DB")
      }
    }
  }

  def processDB (f: (GisItemDB[_])=>Unit): Unit = {
    opts.inFile match {
      case Some(inFile) =>
        getFactoryObject(opts) match {
          case Some(factory) =>
            try {
              factory.loadDB(inFile) match {
                case Some(db) => f(db)
                case None => println("ERROR - could not load DB")
              }
            } catch {
              case t: Throwable => println(s"ERROR - exception during DB load: ${t.getMessage}")
            }
          case None => println("ERROR - could not obtain factory instance")
        }
      case None => println("ERROR - no input file specified")
    }
  }


  def showKey (db: GisItemDB[_]): Unit = {
    opts.key match {
      case Some(key) =>
        db.getItem(key) match {
          case Some(e) => println(s"item matching key '$key' = $e")
          case None => println(s"no item matching key '$key'")
       }
      case None => println("no item key specified")
    }
  }

  def showNearest (db: GisItemDB[_]): Unit = {
    opts.pos match {
      case Some(pos) =>
        opts.nItems match {
          case Some(n) =>
            println(s"$n nearest items to ($pos):")
            for ((e,i) <- db.getNearItems(pos,n).zipWithIndex) {
              println(f"[${i+1}]\t${e._1.toMeters}%10.0fm : ${e._2}")
            }

          case None => { // show just the nearest one
            println(s"nearest item to ($pos):")
            db.getNearestItem(pos) match {
              case Some(e) => println(f"\t${e._1.toMeters}%10.0fm : ${e._2}")
              case None =>
            }
          }
        }
      case None => println("no target position specified")
    }
  }

  def showRange (db: GisItemDB[_]): Unit = {
    opts.pos match {
      case Some(pos) =>
        opts.dist match {
          case Some(dist) =>
            println(s"items in range ($pos) + $dist :")
            for (((d,e),i) <- db.getRangeItems(pos, dist).zipWithIndex) {
              println(f"[${i+1}]\t${d.toMeters}%10.0fm : $e")
            }
          case None => println("no target distance specified")
        }
      case None => println("no target position specified")
    }
  }
}
