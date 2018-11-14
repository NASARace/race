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

import gov.nasa.race.geo.{GeoPosition, GisItem, GisItemDBFactory}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.FileUtils

import scala.Console


object GisItemDBTool {

  object Op extends Enumeration {
    val CreateDB, ShowStruct, ShowItems, ShowStrings, ShowKey, ShowNearest = Value
  }

  //-------------------------------------

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    val posRE = """(-?\d+\.\d+),(-?\d+\.\d+)""".r

    var factory: Option[GisItemDBFactory[_ <: GisItem]] = None
    var inFile: Option[File] = None
    var outDir: File = new File("tmp")
    var key: Option[String] = None
    var pos: Option[GeoPosition] = None
    var nItems: Option[Int] = None
    var op: Op.Value = Op.CreateDB

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
    opt1("-n","--show-nearest")("<lat,lon>","show item nearest to given pos"){ a=>
      op = Op.ShowNearest
      a match {
        case posRE(slat,slon) =>
          pos = Some(GeoPosition.fromDegrees(slat.toDouble,slon.toDouble))
      }
    }
    opt1("-m", "--max-items")("<number>", "max number of items"){ a=>
      nItems = Some(a.toInt)
    }

    requiredArg1("<pathName>", "input file") { a =>
      inFile = parseExistingFileOption(a)
    }

    requiredArg1("<clsName>", "db factory class (e.g. gov.nasa.race.air.geo.faa1801.LandingSite)") { a=>
      try {
        val factoryCls = Class.forName(a)
        factory = Some(factoryCls.getDeclaredConstructor().newInstance().asInstanceOf[GisItemDBFactory[_ <: GisItem]])

      } catch {
        case x:Throwable => Console.err.println(s"error instantiating factory class: $x")
      }
    }
  }

  def getOutFile (opts: Opts): Option[File] = {
    opts.inFile.map{ fin =>
      val fn = FileUtils.filenameWithExtension(fin, "rgis")
      new File(opts.outDir, fn)
    }
  }

  var opts = new Opts
  

  def main (args: Array[String]): Unit = {
    if (opts.parse(args)) {
      opts.op match {
        case Op.CreateDB => createDB
        case Op.ShowStruct => showStruct
        case Op.ShowStrings => showStrings
        case Op.ShowItems => showItems
        case Op.ShowKey => showKey
        case Op.ShowNearest => showNearest
        case other => println(s"unknown operation $other")
      }

    }
  }

  def createDB: Unit = {
    for (inFile <- opts.inFile; outFile <- getOutFile(opts); factory <- opts.factory) {
      factory.createDB(inFile,outFile)
    }
  }

  def showStruct: Unit = {
    for ( file <- opts.inFile; factory <- opts.factory; db <- factory.loadDB(file)) {
      db.printStructure
    }
  }

  def showStrings: Unit = {
    for ( file <- opts.inFile; factory <- opts.factory; db <- factory.loadDB(file)) {
      db.printStrings
    }
  }

  def showItems: Unit = {
    for ( file <- opts.inFile; factory <- opts.factory; db <- factory.loadDB(file)) {
      db.printItems
    }
  }

  def showKey: Unit = {
    for ( file <- opts.inFile; factory <- opts.factory; key <- opts.key; db <- factory.loadDB(file)) {
      db.getItem(key) match {
        case Some(e) => println(s"item matching key '$key' = $e")
        case None => println(s"no item matching key '$key'")
      }
    }
  }

  def showNearest: Unit = {
    for ( file <- opts.inFile; factory <- opts.factory; db <- factory.loadDB(file); pos <- opts.pos) {
      opts.nItems match {
        case Some(n) =>
          println(s"$n nearest items to ($pos):")
          for (((e,d),i) <- db.getNNearestItems(pos,n).zipWithIndex) {
            println(s"[$i] $e : $d")
          }
        case None => { // show just the nearest one
          db.getNearestItem(pos) match {
            case Some(e) => println(s"nearest item to ($pos): $e")
            case None => println(s"no item near $pos found")
          }
        }
      }
    }
  }

}
