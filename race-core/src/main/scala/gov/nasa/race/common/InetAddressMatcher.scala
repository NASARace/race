/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.common

import java.net.{Inet4Address, Inet6Address, InetAddress}

/**
  * a mask matcher for InetAddress IP addresses
  *
  * TODO - this should support all valid textual representations for Inet4 and Inet6Address objects
  */
object InetAddressMatcher {

  def apply (inetAddr: InetAddress): InetAddressMatcher = {
    inetAddr match {
      case ip4: Inet4Address => Inet4AddressMatcher(ip4.getAddress)
      case ip6: Inet6Address => Inet6AddressMatcher(ip6.getAddress)
      case other => throw new RuntimeException(s"unknown InetAddress type: $other")
    }
  }

  def apply (s: String): InetAddressMatcher = {
    def readU8 (e: String): Int = {
      val m = e.toInt
      if (m > 255 || m < 0) throw new NumberFormatException(s"not a valid Inet4Address mask: $s")
      m
    }

    def readU16 (e: String): Int = {
      val m = Integer.parseInt(e,16)
      if (m > 65535 || m < 0) throw new NumberFormatException(s"not a valid Inet46ddress mask: $s")
      m
    }

    //--- known address classes with singleton matchers
    if (s == "loopback") return loopbackMatcher
    else if (s == "link") return linkLocalMatcher
    else if (s == "site") return siteLocalMatcher
    else if (s == "all") return allMatcher
    else if (s == "none") return noneMatcher

    //--- explicit mask
    // TODO not sure this makes sense for Inet6 with max /64 prefix and dynamic random host part
    val es = if (s.indexOf('.') >= 0) {
      s.split("\\.")
    } else if (s.indexOf(':') >= 0) {
      s.split("\\:")
    } else {
      throw new IllegalArgumentException(s"not a valid InetAddress mask: $s")
    }

    if (es.size == 4) {
      val b0 = readU8(es(0)).toByte
      val b1 = readU8(es(1)).toByte
      val b2 = readU8(es(2)).toByte
      val b3 = readU8(es(3)).toByte

      val bs = Array(b0,b1,b2,b3)
      Inet4AddressMatcher(bs)

    } else if (es.size == 8) {
      val s0 = readU16(es(0)).toShort
      val s1 = readU16(es(1)).toShort
      val s2 = readU16(es(2)).toShort
      val s3 = readU16(es(3)).toShort
      val s4 = readU16(es(4)).toShort
      val s5 = readU16(es(5)).toShort
      val s6 = readU16(es(6)).toShort
      val s7 = readU16(es(7)).toShort

      val bs = Array(
        ((s0 >> 8) & 0xff).toByte, (s0 & 0xff).toByte,
        ((s1 >> 8) & 0xff).toByte, (s1 & 0xff).toByte,
        ((s2 >> 8) & 0xff).toByte, (s2 & 0xff).toByte,
        ((s3 >> 8) & 0xff).toByte, (s3 & 0xff).toByte,
        ((s4 >> 8) & 0xff).toByte, (s4 & 0xff).toByte,
        ((s5 >> 8) & 0xff).toByte, (s5 & 0xff).toByte,
        ((s6 >> 8) & 0xff).toByte, (s6 & 0xff).toByte,
        ((s7 >> 8) & 0xff).toByte, (s7 & 0xff).toByte
      )
      Inet6AddressMatcher(bs)

    } else {
      throw new RuntimeException(s"not a valid Inet4Address or Inet6Address mask: $s")
    }
  }

  //--- singletons that don't need to compare anything

  object loopbackMatcher extends InetAddressMatcher {
    val maskBytes = Array(0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,1)
    override def matchesInetAddress (ipAddr: InetAddress): Boolean = ipAddr.isLoopbackAddress
  }

  object linkLocalMatcher extends InetAddressMatcher {
    val maskBytes = Array(0xfe.toByte,0x80.toByte,-1,-1,-1,-1,-1,-1, -1,-1,-1,-1,-1,-1,-1,-1)
    override def matchesInetAddress (ipAddr: InetAddress): Boolean = ipAddr.isLinkLocalAddress
  }

  object siteLocalMatcher extends InetAddressMatcher {
    val maskBytes = Array(0xfc.toByte,0x00.toByte,-1,-1,-1,-1,-1,-1, -1,-1,-1,-1,-1,-1,-1,-1)
    override def matchesInetAddress (ipAddr: InetAddress): Boolean = ipAddr.isSiteLocalAddress
  }

  object all4Matcher extends InetAddressMatcher {
    val maskBytes = Array(-1,-1,-1,-1)
    override def matchesInetAddress (ipAddr: InetAddress): Boolean = ipAddr.isInstanceOf[Inet4Address]
  }

  object all6Matcher extends InetAddressMatcher {
    val maskBytes = Array(-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1)
    override def matchesInetAddress (ipAddr: InetAddress): Boolean = ipAddr.isInstanceOf[Inet6Address]
  }

  object allMatcher extends InetAddressMatcher {
    val maskBytes = Array(-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1)
    override def matchesInetAddress (ipAddr: InetAddress): Boolean = true
  }

  // no real point discriminating for ip type, it doesn't match anyways
  object noneMatcher extends InetAddressMatcher {
    val maskBytes = Array(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)
    override def matchesInetAddress (ipAddr: InetAddress): Boolean = false
  }

  // TODO - add local and loopback matchers
}

trait InetAddressMatcher {
  val maskBytes: Array[Byte]

  def matchesInetAddress (ipAddr: InetAddress): Boolean = {
    val bs = ipAddr.getAddress
    val bsLen = bs.length
    if (bsLen == maskBytes.length) {
      var i = 0
      while (i < bsLen) {
        val b = bs(i)
        if ((b & maskBytes(i)) != b) return false
        i += 1
      }
      true
    } else false
  }
}

/**
  * a byte mask that can check InetAddresses
  */
case class Inet4AddressMatcher(maskBytes: Array[Byte]) extends InetAddressMatcher {
  override def toString: String = {
    val sb = new StringBuffer
    var i = 0
    while (i < 4) {
      sb.append( 0xff & maskBytes(i))
      i += 1
      if (i < 4) sb.append('.')
    }
    sb.toString
  }
}

case class Inet6AddressMatcher(maskBytes: Array[Byte]) extends InetAddressMatcher {
  override def toString: String = {
    val sb = new StringBuffer
    var i = 0
    while (i < 16) {
      sb.append(Integer.toHexString(maskBytes(i) & 0xff))
      sb.append(Integer.toHexString(maskBytes(i+1) & 0xff))
      i += 2
      if (i < 16) sb.append(':')
    }
    sb.toString
  }
}
