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

package gov.nasa.race.test

import java.io.{File, FileInputStream, InputStream}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.util.zip.GZIPInputStream
import scala.reflect.ClassTag


/**
  * common base for all RACE regression tests, which mostly mixes in the
  * various scalatest traits
  */
trait RaceSpec extends Suite with Matchers with OptionValues with Inside with ScalaCheckPropertyChecks {
  val testOutputDir = new File("tmp")

  def mkTestOutputDir = testOutputDir.mkdir

  def testOutputFile(filename: String): File = {
    if (!testOutputDir.isFile) mkTestOutputDir
    new File(testOutputDir, filename)
  }

  def emptyTestOutputFile(filename: String): File = {
    val f = testOutputFile(filename)
    if (f.isFile) f.delete
    f
  }

  def executeConditional (cond: => Boolean, msg: String)(f: =>Unit): Unit = {
    if (cond) {
      f
    } else {
      Console.err.println(s"${scala.Console.RED}$msg ${scala.Console.RESET}")
    }
  }

  /**
    * NOTE - do not use the same filename in concurrent tests, which would result in race conditions that make them fail
    */
  def withEmptyTestOutputFile(filename: String)(test: File=>Unit) = {
    val file = testOutputFile(filename)
    try {
      if (file.isFile) file.delete // reset
      test(file)
    } finally {
      if (file.isFile) file.delete
    }
  }

  def qualifiedResourceFile(filename: String): File = {
    val url = getClass.getResource(filename)
    if (url != null) {
      val rf = new File(url.getPath)
      if (rf.exists) rf else fail(s"resource file not found: $rf")
    } else fail(s"no such resource: $filename")
  }

  def codeSource: File = {
    val codeSrc = getClass.getProtectionDomain.getCodeSource
    if (codeSrc != null){
      new File(codeSrc.getLocation.getPath)
    } else {
      fail(s"no code source for ${getClass.getName}")
    }
  }

  def baseResourceFile(fileName: String): File = {
    val rf = new File(codeSource, fileName)
    if (rf.exists) rf else fail(s"resource file not found: $rf")
  }

  def baseResourceStream(fileName: String): InputStream = {
    val is = new FileInputStream( baseResourceFile(fileName))
    if (fileName.endsWith(".gz")) new GZIPInputStream(is) else is
  }

  def createConfig(s: String) = ConfigFactory.parseString(s)

  def baseResourceConfig (fileName: String): Config = resourceConfig(baseResourceFile(fileName))
  def qualifiedResourceConfig (fileName: String): Config = resourceConfig(qualifiedResourceFile(fileName))

  protected def resourceConfig (rf: File): Config = {
    try {
      ConfigFactory.load(ConfigFactory.parseFile(rf))
    } catch {
      case x: Throwable => fail(s"loading resource config $rf failed: ${x.getMessage}")
    }
  }

  // NOTE - these are just predicates, they don't throw exceptions

  def expectException[T: ClassTag] (f: => Unit): Boolean = {
    try { f } catch {
      case t: T => return true
      case other: Throwable => return false
    }
    false // if it doesn't throw the exception, it is a failure
  }

  def expectWithin (v: Double, vExpected: Double, eps: Double): Boolean = {
    Math.abs(v - vExpected) <= eps
  }

  def expectToFail (f: => Unit): Unit = {
    try {
      f
    } catch {
      case _:org.scalatest.exceptions.TestFailedException => return // we expected that..
      case other:Throwable => fail(s"not a TestFailedException: $other") // no failure is a fail
    }
    fail("test was expected to fail")
  }
}
