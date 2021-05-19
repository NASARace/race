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

import java.io.{PrintWriter, FileInputStream, InputStreamReader, File}

import sbt._
import sbt.Keys._
import RaceBuild.NodeTest

import scala.collection.immutable.ListMap
import scala.io.Source
import scala.util.matching.Regex
import scala.xml.XML

object ReCov {
  //--- constants
  final val CLEAR_SCREEN = "\u001b[2J\u001b[;H"

  val testSrcPattern = """src/(test|multi-jvm)/""".r.unanchored
  val pathExtractor = """(.*?/?)src/(test|multi-jvm)/.*?([^/]+)\.(scala|java)$""".r

  val reqDefPattern = """__\[(R-[0-9a-z.]+)\]__ *(.+)""".r  // default requirements definition pattern
  val reqRefPattern = """\[(R-[0-9a-z.]+)\] *(.+)""".r  // default requirements reference pattern
  val reqLevelDelimiters = "[._:/]"

  val restOfSentence = """([^.:]*[.:]).*""".r
  val packagePattern = """package +(.+)""".r
  val baseNamePattern = """([^.]+)\.*.*""".r

  val testNamePattern = """TEST\-(.*?)\.?([^.]*?)\.xml$""".r
  val multiJvmTestNamePattern = """TEST\-(.*?)\.?([^.]*?)MultiJvm(.*?)\.xml$""".r

  val curDir = System.getProperty("user.dir") + '/'

  //--- auxiliary types
  type IndexedLine = (String,Int)

  case class Match (file: File, indexedLine: IndexedLine, var tests: List[TestReport] = Nil) {
    lazy val isTest = {
      file.getPath match {
        case testSrcPattern(t) => true
        case _ => false
      }
    }

    def ++= (trs: Seq[TestReport]) = {
      tests = tests ++ trs
    }
  }
  case class TestReport (file: File, status: String)

  case class Requirement (id: String, definition: Match, references: List[Match] = Nil) {
    def + (m: Match): Requirement = copy(references = m :: references)
  }

  //--- settings/task definitions
  val recov = taskKey[Unit]("create requirements coverage report with updated test reports")
  val recovQuick = taskKey[Unit]("create requirements coverage report with current test reports")
  val requirementSources = settingKey[Seq[File]]("list of files containing requirements")
  val requirementReport = settingKey[File]("generated report file (overwritten if existent)")

  val taskSettings = Seq(
    //--- task definition/

    // (this could also be achieved with   "addCommandAlias("recov", ";test;recovQuick")" / build.sbt

    recov := {
      // note - .value only works for non-Unit tasks, otherwise we need to use dependOn(..)
      executeTests.all(ScopeFilter(inAnyProject,inConfigurations(Test))).value

      val allSources = sources.all(ScopeFilter(inAggregates(ThisProject,includeRoot=true),
                                               inConfigurations(Compile, Test, NodeTest))).value.flatten
      val reqSrcs = requirementSources.value
      val report = requirementReport.value

      requirementsCoverage(name.value, allSources, reqSrcs, report)
    },

    recovQuick := {
      val allSources = sources.all(ScopeFilter(inAggregates(ThisProject,includeRoot=true),
                                               inConfigurations(Compile, Test, NodeTest))).value.flatten
      val reqSrcs = requirementSources.value
      val report = requirementReport.value

      requirementsCoverage(name.value, allSources, reqSrcs, report)
    },

    //--- supporting settings
    aggregate / recov := false,
    aggregate / recovQuick := false,

    requirementReport := new File("doc/reports/requirements-coverage.md"),
    requirementSources := List[File]()
  )

  //--- main functions

  def requirementsCoverage (name: String, allSources: Seq[File], reqSrcs: Seq[File], report: File) = {
    println(CLEAR_SCREEN) // clear screen and reset cursor (we can't use race.Console here)
    println(s"analyzing requirements coverage in project $name")

    if (!reqSrcs.isEmpty) {
      println(" - parsing requirement files")
      var map = parseDefinitions(reqSrcs, reqDefPattern)

      println(" - parsing source files")
      map = parseSources(map, allSources, reqRefPattern)

      println(" - analyzing test reports")
      parseTestReports(map)

      println(" - writing requirements coverage report")
      generateReport(report,map)

    } else {
      println("no requirement sources specified, check setting 'requirementsSources'")
    }
  }

  def parseDefinitions(reqFiles: Seq[File], reqDefPattern: Regex): Map[String, Requirement] = {
    reqFiles.foldLeft(Map[String, Requirement]()) {
      (map, file) => {
        val numberedLineIterator = Source.fromFile(file).getLines.zipWithIndex
        map ++ numberedLineIterator.foldLeft(List[(String, Requirement)]()) {
          (entries, indexedLine) => {
            reqDefPattern.findFirstIn(indexedLine._1) match {
              case None => entries
              case Some(reqDefPattern(id,line)) =>
                val text = getFullSentence(line, numberedLineIterator)
                (id, Requirement(id, new Match(file, (text,indexedLine._2)))) :: entries
            }
          }
        }
      }
    }
  }

  def parseSources(map: Map[String,Requirement], sources: Seq[File], reqRefPattern: Regex): Map[String,Requirement] = {
    sources.foldLeft(map) {
      (reqs, file) => {
        Source.fromFile(file).getLines.zipWithIndex.foldLeft(List[(String,Match)]()) {
          (list, indexedLine) => {
            reqRefPattern.findFirstIn(indexedLine._1) match {
              case None => list
              case Some(reqRefPattern(id,text)) => (id, Match(file, (text,indexedLine._2))) :: list
            }
          }
        }.foldRight(reqs) {
          (e, reqs) => {
            val (id,m) = e
            reqs.get(id) match {
              case None => reqs + (id -> Requirement(id, Match(m.file, ("UNDEFINED",0)),List(m)))
              case Some(r) => reqs + (id -> (r + m))
            }
          }
        }
      }
    }
  }

  def parseTestReports(map: Map[String,Requirement]) = {
    for ((id,req) <- map){
      for (ref <- req.references) {
        if (ref.isTest) {
          ref ++= getTestReports(ref.file).map(parseTestReport)
        }
      }
    }
  }

  def parseTestReport (file: File): TestReport = {
    val doc = XML.load(new InputStreamReader(new FileInputStream(file)))
    for (e <- doc \\ "testsuite") {
      val failures = e \ "@failures"
      val errors = e \ "@errors"
      val tests = e \ "@tests"

      val status = s"tests=$tests,failures=$failures,errors=$errors"
      return TestReport(file,status)
    }
    TestReport(file,"UNKNOWN")
  }

  def generateReport (report: File, result: Map[String,Requirement]) = {
    checkDir(report.getParentFile)
    val pw = new PrintWriter(report)

    pw.println("# Requirement Coverage Report\n") // report
    println("Requirement Coverage Report:") // screen

    for ((id,req) <- ListMap(result.toSeq.sortWith(compareIds(reqLevelDelimiters)):_*)){
      pw.println(f"__[$id]__ ${truncate(req.definition.indexedLine._1,90)}\n") // report
      println(f"$id%-10s ${truncate(req.definition.indexedLine._1,90)}") // screen

      if (!req.references.isEmpty) pw.println()

      for ( (Match(file, (line,idx), tests),i) <- req.references.zipWithIndex) {
        val path = file.getPath.substring(curDir.length)

        // <2do> unfortunately gitbook apparently doesn't serve local files (yet)
        pw.println(s"  ${i+1}. [$path:$idx](file://../../../$path) - $line") // report
        println(s"    $path:$idx - $line") // screen

        if (!tests.isEmpty) pw.println()

        for ((tr,i) <- tests.zipWithIndex) {
          val path = tr.file.getPath.substring(curDir.length)
          pw.println(s"    ${i+1}. [${tr.file}](file://../../../$path) **(${tr.status})**")

          val color = if (tr.status.contains("failures=0,errors=0")) scala.Console.WHITE else scala.Console.RED
          println(s"        ${tr.file} - ${colorize(color,tr.status)}")
        }
      }
      if (!req.references.isEmpty) pw.println()
    }

    pw.close()
    println(s"report written to: $report")
  }

  //--- helper functions

  def colorize(clr: String, s: String): String = s"$clr$s${scala.Console.RESET}"

  def getTestReports (file: File): Seq[File] = {
    getRelPath(file) match {
      case pathExtractor(baseDir,ttype,fname,stype) =>
        findTestReports(new File(baseDir + "target/test-reports/"), fname)
      case _ => Nil
    }
  }

  def getRelPath (f: File) = {
    val fpath = f.getPath
    if (fpath.startsWith(curDir))
      fpath.substring(curDir.length)
    else
      fpath
  }

  def findTestReports (d: File, fname: String): Seq[File] = {
    var reports: List[File] = Nil
    if (d.isDirectory) {
      for (f <- d.listFiles) {
        f.name match {
          case multiJvmTestNamePattern(pkg, bname, node) =>
            if (bname == fname) reports = reports :+ f
          case testNamePattern(pkg, bname) =>
            if (bname == fname) reports = reports :+ f
          case _ =>
        }
      }
    }
    reports
  }

  /**
   * make sure "R-10" > "R-2" and "R-1.2.3" > "R-1.2"
   */
  def compareIds (reqLevelDelimiters: String)(a: (String,Requirement), b: (String,Requirement)): Boolean = {
    val as = a._1.split(reqLevelDelimiters)
    val bs = b._1.split(reqLevelDelimiters)
    for ((n1,n2) <- as.zip(bs)) {
      if (n1.length != n2.length) return n1.length < n2.length
      if (n1 != n2) return n1 < n2
    }
    as.length < bs.length
  }

  def getFullSentence (line: String, iter: Iterator[(String,Int)]): String = {
    restOfSentence.findFirstIn(line) match {
      case None =>                              // extend
        var sentence = line
        for ((s,_) <- iter) {
          restOfSentence.findFirstIn(s) match {
            case None => sentence = sentence + ' ' + s
            case Some(rest) => return sentence + ' ' + rest
          }
        }
        sentence

      case Some(sentence) => sentence    // shrink
    }
  }

  def truncate (s: String, maxLen: Int): String = {
    if (s.length > maxLen) {
      firstIndexOfAny(s, maxLen, ' ', '.', ':') match {
        case -1 => s.substring(0, maxLen) + "..."
        case idx => s.substring(0, idx) + "..."
      }
    } else s
  }

  def firstIndexOfAny (s: String, fromIdx: Int, cs: Character*): Int = {
    var minIdx = Int.MaxValue
    for (c <- cs) {
      s.indexOf(c, fromIdx) match {
        case -1 =>
        case n => minIdx = Math.min(minIdx, n)
      }
    }
    if (minIdx == Int.MaxValue) -1 else minIdx
  }

  def checkDir (dir: File) = {
    if (!dir.isDirectory) {
      dir.mkdirs()
    }
  }

  def getBaseName (file: File) = {
    file.getName match {
      case baseNamePattern(name) => name
      case s => s
    }
  }

}
