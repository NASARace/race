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

/**
 * common build support code for RACE project
 */

import java.io.{File, FileInputStream, InputStreamReader}
import java.util.Properties


import scala.collection.JavaConverters._
import sbt._
import sbt.Keys._

object RaceBuild /*extends Build */ {

  loadProperties

  def loadProperties = {
    Seq("project/race-build.properties", "local-race-build.properties") foreach { pn =>
      val f = new File(pn)
      if (f.isFile){
        println(s"loading properties from $f")
        val raceProps = new Properties
        val isr = new InputStreamReader(new FileInputStream(f), "UTF-8")
        raceProps.load(isr)
        isr.close
        sys.props ++ raceProps.asScala
      }
    }
  }

  /**
   * until Scala has real union types this can be used to append Setting values
   * via flat filtering of varargs, ala
   * {{{
   *   val xlib1 = "org.x" % "lib1" % "1.0"
   *   val xlib2 = "org.x" % "lib2" % "2.0"
   *   val allXlibs = Seq(xlib1,xlib2)
   *   ...
   *   libraryDependencies ++= flatFilter[ModuleID](
   *     allXlibs,
   *     "org.y" % "ylib" % "1.0"
   *   )
   * }}}
   */
  def flatFilter[T: Manifest] (xs: Any*): Seq[T] = {
    def acc(x: Any, list: List[T]) : List[T] = {
      x match {
        case t: T => t :: list
        case s: Seq[_] => s.foldRight(list)(acc)
        case _ => list
      }
    }
    Seq(xs:_*).foldRight(List[T]())(acc)
  }

  def flatLibs (xs: Any*): Seq[ModuleID] = flatFilter[ModuleID](xs:_*)

  def flatLibsInConfig (config: String, xs: Any*): Seq[ModuleID] = {
    def acc(x: Any, list: List[ModuleID]) : List[ModuleID] = {
      x match {
        case moduleId: ModuleID => moduleId % config :: list
        case s: Seq[_] => s.foldRight(list)(acc)
        case _ => list
      }
    }
    Seq(xs:_*).foldRight(List[ModuleID]())(acc)
  }

  // note - as of sbt 0.13.8 we don't need to flatten Setting[_] anymore, sbt does it for us

  def rmdir (dir: File): Boolean = {
    if (dir.isDirectory) {
      for (e <- dir.listFiles) {
        if (e.isFile){
          if (!e.delete) return false
        } else {
          if (!rmdir(e)) return false
        }
      }
      if (!dir.delete) return false
    }
    true
  }

  def withReturn[T] (t: T)(f: (T)=>Any): T = {
    f(t)
    t
  }

  // some syntactic sugar to make build.sbt more readable

  import scala.collection._

  // this is where we store project states for local build configs
  val extensibleProjects = mutable.HashMap.empty[String,Project]

  def createRootProject(id: String): Project = Project(id, file("."))
  def createProject(id: String): Project = Project(id, file(id))
  def createProject(id: String, pathName: String): Project = Project(id, file(pathName))

  def createProject(id: String, initSettings: Seq[Def.Setting[_]]): Project = {
    Project(id, file(id)).settings(initSettings)
  }

  // the config that executes tests in src/node-test/..
  lazy val NodeTest = config("node-test") extend(Test)

  def createTestProject(pathName: String, initSettings: Seq[Def.Setting[_]]): Project = {
    val path = file(pathName)
    Project(pathName, path)
      .configs(NodeTest)
      //.settings( inConfig(NodeTest)(Defaults.testTasks) : _*)
      .settings(
        initSettings,
        publish := {},
        publishLocal := {},

        //fork in Test := true,

        // the NodeTest settings - note that we need to execute in fork mode
        inConfig(NodeTest)(Defaults.testSettings),
        NodeTest / parallelExecution := false,
        NodeTest / sourceDirectory := baseDirectory.value / "src" / "node-test",
        NodeTest / scalaSource := baseDirectory.value / "src" / "node-test" / "scala",
        NodeTest / fork := true // we need to run this in fork mode to pick up the classpath
    )
  }

  implicit class RaceProject (val p: Project) {

    def addLibraryDependencies (xs: Any*): Project = {
      p.settings(
        libraryDependencies ++= flatLibs(xs:_*)
      )
    }

    def addTestLibraryDependencies (xs: Any*): Project = {
      p.settings(
        libraryDependencies ++= flatLibsInConfig("test", xs:_*)
      )
    }

    def addConfigLibraryDependencies (conf: String)(xs: Any*): Project = {
      p.settings(
        libraryDependencies ++= flatLibsInConfig(conf, xs:_*)
      )
    }

    def setVersion (s: String): Project = {
      p.settings(
        version := s
      )
    }

    def makeExtensible: Project = {
      extensibleProjects(p.id) = p
      p
    }
  }
}

