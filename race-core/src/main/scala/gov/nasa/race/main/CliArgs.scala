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
package gov.nasa.race.main

import java.io.File

import gov.nasa.race.uom.DateTime

import scala.collection.Seq
import scala.collection.mutable.ArrayBuffer
import scala.util.control.Breaks._

object CliArgs {
  def apply[T<:CliArgs] (args: Array[String])(f: =>T): Option[T] = {
    val opts = f
    if (opts.parse(args)) Some(opts) else None
  }
}


/**
  * base class for command line argument parsing.
  *
  * The basic provision is that all public fields of the concrete CliArgs instance are initialized
  * if calling parse(args) returns true.
  * A initValue of None indicates the respective argument is mandatory (has to be specified or parse will
  * report an error)
  *
  * Use as follows:
  * {{{
  * class MyOpts extends CliArgs("usage: main") {
  *   var delay = false
  *   var vault: Option[File] = None
  *   var port  = 0
  *   var files = Seq.empty[File]
  *
  *   opt0("-d","--delay")("delay start"){ delay = true }
  *   opt1("--vault")("<pathName>","vault file"){a=> vault = parseExistingFileOption(a)}
  *   requiredOpt1("-p","--port")("<portNumber>", "server port"){a=> port = parseInt(a)} // need to be specified
  *   requiredArgN("<file>..","(existing) files to process"){as => files= parseExistingFiles(as)}
  * }
  *   ...
  *   val cliOpts = new MyOpts
  *   if (!cliOpts.parse(args)) return
  *   ...
  * }}}
  *
  * The main rationale behind not using the 3rd party scopt lib is that we do need extensible option classes, which
  * does not work with scopts case classes without serious modification, which is as much code as this replacement.
  * The CliArg design also makes it much easier to add type and application specific parsers within the concrete
  * CliArg class.
  */
class CliArgs (val title: String) {

  //--- internal types
  abstract class Arg (val keyNames: Seq[String], val text: String, val required: Boolean) {
    var seen = false

    def missing: Boolean = required && !seen
    def id: String = s"option ${keyNames.head}"
    def show: Unit
    def showRequired: String = if (required) "- REQUIRED" else ""
  }

  class Opt0 (keyNames: Seq[String], text: String, req: Boolean, action: =>Unit)
    extends Arg(keyNames,text,req) {
    def executeAction = { seen = true; action }
    override def show = {
      println(f"  ${keyNames.mkString("|")}%-35s - $text $showRequired")
    }
  }

  class Opt1 (keyNames: Seq[String], text: String, val valueName: String, req: Boolean, action: String=>Unit)
    extends Arg(keyNames,text,req) {
    def executeAction (v: String) = { seen = true; action(v) }
    override def show = {
      val kv = s"${keyNames.mkString("|")} $valueName"
      println(f"  $kv%-35s - $text $showRequired")
    }
  }

  class FreeArg (text: String, val valueName: String, req: Boolean) extends Arg(Seq.empty[String],text,req) {
    override def id = s"argument $valueName"
    override def show = println(f"  $valueName%-35s - $text $showRequired")
  }

  class FreeArg1 (text: String, valueName: String, req: Boolean, action: String=>Unit) extends FreeArg(text,valueName,req) {
    def executeAction (v: String) = { seen = true; action(v) }
  }

  class FreeArgN (text: String, valueName: String, req: Boolean, action: Array[String]=>Unit) extends FreeArg(text,valueName,req) {
    def executeAction (args: Array[String]) = { seen = true; action(args) }
  }

  //--- ctor
  protected val optInfos = ArrayBuffer.empty[Arg]
  protected var optChars = Array('-','+')

  protected val argInfos = ArrayBuffer.empty[FreeArg]


  //--- optInfo/argInfo field initializers used in concrete constructors
  def opt0(keyNames:String*)(text: String)(action: =>Unit) = {
    optInfos += new Opt0(keyNames,text,false,action)
  }
  def requiredOpt0 (keyNames:String*)(text: String)(action: =>Unit) = {
    optInfos += new Opt0(keyNames,text,true,action)
  }
  def opt1(keyNames:String*)(valueName: String,text: String)(action: String=>Unit) = {
    optInfos += new Opt1(keyNames,text,valueName,false,action)
  }
  def requiredOpt1(keyNames:String*)(valueName: String,text: String)(action: String=>Unit) = {
    optInfos += new Opt1(keyNames,text,valueName,true,action)
  }
  def arg1(valueName: String,text: String)(action: String=>Unit) = {
    argInfos += new FreeArg1(text,valueName,false,action)
  }
  def requiredArg1(valueName: String,text: String)(action: String=>Unit) = {
    argInfos += new FreeArg1(text,valueName,true,action)
  }
  def argN(valuesName: String,text: String)(action: Array[String]=>Unit) = {
    argInfos += new FreeArgN(text,valuesName,false,action)
  }
  def requiredArgN(valuesName: String,text: String)(action: Array[String]=>Unit) = {
    argInfos += new FreeArgN(text,valuesName,true,action)
  }

  def checkConsistency: Boolean = true // override if you have to check consistency of opts

  //--- the parsers

  def invalid(msg: String) = throw new IllegalArgumentException(msg)

  def parseFile (a: String): File = new File(a) // we could check for valid file names here
  def parseFileOption (a: String): Option[File] = Some(new File(a))

  def parseExistingFile (a: String): File = {
    val f = new File(a)
    if (f.isFile) f else invalid(s"file not found: $a")
  }

  def parseExistingFileOption (a: String): Option[File] = {
    val f = new File(a)
    if (f.isFile) Some(f) else None
  }

  def parseDir (a: String): File = new File(a) // we could check for valid dir names here
  def parseDirOption (a: String): Option[File] = Some(new File(a))

  def parseExistingDir (a: String): File = {
    val f = new File(a)
    if (f.isDirectory) f else invalid(s"directory not found: $a")
  }

  def parseExistingDirOption (a: String): Option[File] = {
    val f = new File(a)
    if (f.isDirectory) Some(f) else None
  }

  def parseFiles (as: Array[String]): Seq[File] = as.map(new File(_))

  def parseExistingFiles (as: Array[String]): Seq[File] = {
    as.map{ a=>
      val f = new File(a)
      if (!f.isFile) invalid(s"file not found: $f") else f
    }
  }

  def parseString (a: String) = a

  def parseInt (a: String) =  Integer.parseInt(a)

  def parseDouble (a: String) = java.lang.Double.parseDouble(a)

  def parseTimeMillis (a: String): Long = DateTime.parseYMDT(a).toEpochMillis
  def parseDateTime (a: String): DateTime = DateTime.parseYMDT(a)

  //--- internals

  def parse (args: Array[String]): Boolean = {
    def _fail(msg: String) = { println(s"ERROR: $msg"); false }
    def _showUsage(key: String) = { if (key == "-h" || key == "--help"){ usage; true } else false }
    def _hasMissing(as: Seq[Arg]) = {
      as.find(a => a.missing) match {
        case Some(a) => _fail(s"missing required arg ${a.id}"); true
        case None => false
      }
    }

    val it = args.iterator
    for (key <- it) {
      if (_showUsage(key)) return false

      try {
        optInfos.find(a => a.keyNames.contains(key)) match {
          case Some(a:Opt0) =>
            a.executeAction

          case Some(a:Opt1) =>
            if (!it.hasNext) {
              return _fail(s"no value for command line argument $key")
            } else {
              a.executeAction(it.next())
            }

          case other =>
            if (optChars.contains(key(0))){
              return _fail(s"unknown option $key")

            } else { // free args
            var arg = key

              breakable {
                argInfos.foreach { ai =>
                  ai match {
                    case ai:FreeArg1 =>
                      ai.executeAction(arg)
                      if (it.hasNext) arg = it.next() else break()
                    case ai: FreeArgN =>
                      val args = ArrayBuffer(arg)
                      args.appendAll(it)
                      ai.executeAction(args.toArray)
                      break()
                  }
                }
              }
            }
        }
      } catch {
        case iax: IllegalArgumentException => return _fail(iax.getMessage)
        case t: Throwable => return _fail(s"$t while parsing command line argument $key")
      }
    }

    !_hasMissing(optInfos) && !_hasMissing(argInfos) && checkConsistency
  }

  def usage = {
    print(s"$title")
    if (optInfos.length > 1) print(" <option>..")
    else if (optInfos.length == 1) print(" <option>")
    if (argInfos.length > 1) print(" <arg>..")
    else if (argInfos.length == 1) print( " <arg>")
    println()
    if (optInfos.length > 0){
      println("options:")
      optInfos.foreach(_.show)
    }
    if (argInfos.length > 0){
      println("args:")
      argInfos.foreach(_.show)
    }
  }
}
