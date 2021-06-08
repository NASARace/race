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
package gov.nasa.race.tool

import java.net.NetworkInterface
import scala.jdk.CollectionConverters.IteratorHasAsScala

/**
  * show NetworkInterfaces with associated capabilities and bound InetAddresses
  */
object ShowNetworkInterfaces {

  def main (args: Array[String]): Unit = {
    println("      name    up  virt  loop   p2p mcast  addrs")
    println("---------- ----- ----- ----- ----- -----  -------------------------------------------------------")
    NetworkInterface.networkInterfaces().forEach { ifc=>
      print(f"${ifc.getName}%10.10s")

      if (ifc.isUp)                print("     ✓︎") else print("      ")
      if (ifc.isVirtual)           print("     ✓︎") else print("      ")
      if (ifc.isLoopback)          print("     ✓︎") else print("      ")
      if (ifc.isPointToPoint)      print("     ✓︎") else print("      ")
      if (ifc.supportsMulticast()) print("     ✓︎") else print("      ")

      println( ifc.getInetAddresses.asIterator().asScala.mkString("  ", " , ",""))
    }
  }
}
