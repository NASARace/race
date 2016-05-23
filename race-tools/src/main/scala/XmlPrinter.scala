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

package gov.nasa.race.tools

import java.io.File
import gov.nasa.race.common.FileUtils._
import gov.nasa.race.common.XmlPullParser

/**
  * a XML pretty printer
  */
object XmlPrinter {

  def main (args: Array[String]): Unit = {
    if (args.length > 0){
      val file = new File(args(0))
      fileContentsAsChars(file) match {
        case Some(data) =>
          val parser = new XmlPullParser
          parser.initialize(data)
          parser.printOn(System.out)
        case None =>
          println(s"file is empty: $file")
      }
    } else {
      println("usage: xmlprinter <file>")
    }
  }
}
