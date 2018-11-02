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

import gov.nasa.race.geo.{GisItem, GisItemDBFactory}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.FileUtils

import scala.Console


object GisItemDBTool {

  object Op extends Enumeration {
    val CreateDB, ShowStruct, ShowItems, ShowStrings = Value
  }

  //-------------------------------------

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var factory: Option[GisItemDBFactory[_ <: GisItem]] = None
    var inFile: Option[File] = None
    var outDir: File = new File("tmp")
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
}
