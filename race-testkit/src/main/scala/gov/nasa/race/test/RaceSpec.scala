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

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest._
import org.scalatest.prop._

import scala.reflect.ClassTag


/**
  * common base for all RACE regression tests, which mostly mixes in the
  * various scalatest traits
  */
trait RaceSpec extends Suite with Matchers with OptionValues with Inside with PropertyChecks {
  val testOutputDir = new File("tmp")

  def mkTestOutputDir = testOutputDir.mkdir

  def testOutputFile(filename: String): File = new File(testOutputDir, filename)

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

  def baseResourceConfig (fileName: String): Config = resourceConfig(baseResourceFile(fileName))
  def qualifiedResourceConfig (fileName: String): Config = resourceConfig(qualifiedResourceFile(fileName))

  protected def resourceConfig (rf: File): Config = {
    try {
      ConfigFactory.load(ConfigFactory.parseFile(rf))
    } catch {
      case x: Throwable => fail(s"loading resource config $rf failed: ${x.getMessage}")
    }
  }

  def expectException[T: ClassTag] (f: => Unit): Boolean = {
    try { f } catch {
      case t: T => return true
      case other: Throwable => return false
    }
    false // if it doesn't throw the exception, it is a failure
  }
}
