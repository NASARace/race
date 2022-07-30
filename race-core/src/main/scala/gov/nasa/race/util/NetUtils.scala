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

package gov.nasa.race.util

import java.io._
import java.lang.{StringBuilder => JStringBuilder}
import java.net._
import java.security.cert.X509Certificate
import java.security.{KeyStore, SecureRandom}
import javax.net.SocketFactory
import javax.net.ssl._
import gov.nasa.race._

import java.nio.charset.Charset
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.EnumerationHasAsScala

/**
 * common networking related functions
 */
object NetUtils {

  val HostPortRE = """([^:/]+)(?::(\d+))?""".r
  val PortHostPortRE = """(\d+):([^:/]+):(\d+)""".r
  val UrlRE = """(.+)://(?:(.+)@)?([^:/]+)(?::(\d+))?(?:/([^?]+))?(?:\?(.+))?""".r // scheme,user,host,port,path,query
  val PQUrlRE = """(.+)://(?:(.+)@)?([^:/]+)(?::(\d+))?(?:/(.+))?""".r // scheme,user,host,port,path+query
  val ServerPathRE = """(.+://(?:[\w]+@)?[^:/]+(?::\d+)?)(?:/)(.+)?""".r
  val SchemeRE = """^https?://""".r
  val PathFnameRE = """(?:.+)://(?:(?:.+)@)?(?:[^:/]+)(?::(?:\d+))?(?:/(?:(.*)/)?([^?]+)?)?(?:\?(?:.+))?""".r

  type UrlString = String

  case class UrlSpec(scheme: String, user: String, host: String, port: Int, path: String, query: String) {
    def isSameLocationAs(other: UrlSpec) = scheme == other.scheme && host == other.host && port == other.port
  }

  def parseUrl(url: String): Option[UrlSpec] = {
    url match {
      case UrlRE(scheme, usr, host, port, path, query) => Some(UrlSpec(scheme, usr, host, port.toInt, path, query))
      case other => None
    }
  }

  def decodeUri (encUrl: String): String = {
    URLDecoder.decode(encUrl,Charset.defaultCharset())
  }

  def userInUrl(url: String): Option[String] = {
    url match {
      case UrlRE(_, user, _, _,_,_) => Some(user)
      case other => None
    }
  }

  def filenameOfUrl (url: String): Option[String] = {
    url match {
      case PathFnameRE(_,fname) => Some(fname)
      case _ => None
    }
  }

  def hasScheme(url: String): Boolean = SchemeRE.matches(url)

  /** turn URL into a string that can be used as a filename. This is mostly to avoid Windows problems */
  def mapToFsPathString (host: String, path: String, query: String): String = {
    var p = if (host != null) host + '/' else ""
    if (path != null) p = p + path.replace(':','~') // to make Windows happy
    if (query != null) p = p + "%26" + URLEncoder.encode(query,Charset.defaultCharset())
    p
  }

  /** if rawQuery is defined it includes the leading '?'  */
  def mapToFsPathString(path: String, rawQuery: Option[String]): String = {
    // NOTE this should use the same encoding as mapToFsPathString(host,path,query)
    var p = path.replace(':','~')
    if (rawQuery.isDefined) p = p + URLEncoder.encode(rawQuery.get, Charset.defaultCharset())
    p
  }

  def isSameUrlLocation(url1: String, url2: String): Boolean = {
    liftPredicate(parseUrl(url1), parseUrl(url2)) { (u1, u2) => u1.isSameLocationAs(u2) }
  }

  def isLocalhost(hostname: String) = hostname == "localhost" || hostname == "127.0.0.1"

  def isHostInDomain (hostName: String, domainName: String): Boolean = {
    if (hostName.endsWith(domainName)) {
      if (hostName.length == domainName.length) {
        true
      } else {
        hostName.charAt(hostName.length - domainName.length -1) == '.'
      }
    } else {
      false
    }
  }

  def isPathInParent (path: String, parent: String): Boolean = {
    if (path.startsWith(parent)) {
      if (path.length == parent.length) {
        true
      } else {
        if (parent.charAt(parent.length-1) == '/' && path.charAt(parent.length-1) == '/'){
          true
        } else {
          path.charAt(parent.length) == '/'
        }
      }
    } else {
      false
    }
  }

  // solve the "..//.." problem
  def concatenatePaths (prefix: String, postfix: String): String = {
    if (prefix.last == '/') {
      if (postfix.head == '/') prefix + postfix.substring(1)
      else prefix + postfix
    } else {
      if (postfix.head == '/') prefix + postfix
      else prefix + '/' + postfix
    }
  }

  //--- socket functions

  def createSocket (sf: SocketFactory, host: String, port: Int): Option[Socket] = trySome(sf.createSocket(host,port))
  def createWriter (socket: Socket): Option[Writer] = trySome(new OutputStreamWriter(socket.getOutputStream))
  def createReader (socket: Socket): Option[Reader] = trySome(new InputStreamReader(socket.getInputStream))

  @tailrec private def _readUntilClosed (reader: Reader, sb: JStringBuilder, buf: Array[Char]): String = {
    val n = reader.read(buf)
    if (n < 0) {
      sb.toString
    } else {
      sb.append(buf,0,n)
      _readUntilClosed(reader,sb,buf)
    }
  }

  @tailrec private def _readNext (reader: Reader, sb: JStringBuilder, buf: Array[Char]): String = {
    val n = reader.read(buf)
    if (n < 0) {
      sb.toString
    } else {
      sb.append(buf,0,n)
      if (n == buf.length){
        Thread.sleep(300)
        if (reader.ready) _readNext(reader,sb,buf) else sb.toString
      } else sb.toString
    }
  }


  def readNext(reader:Reader): Option[String] =  trySome(_readNext(reader,new JStringBuilder,new Array[Char](1024)))

  def readAll(reader:Reader): Option[String] = trySome(_readUntilClosed(reader,new JStringBuilder,new Array[Char](1024)))


  //--- SSL/TLS support

  /**
    * the all trusting TrustManager that does not validate certificate chains
    * Use only within trusted networks
    */
  def trustAllCerts: Array[TrustManager] = {
    Array[TrustManager]( new X509TrustManager {
      override def getAcceptedIssuers: Array[X509Certificate] = null
      override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: UrlString): Unit = {}
      override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: UrlString): Unit = {}
    })
  }

  def keyManagerFactory(ks: KeyStore, pw: Array[Char]): Option[KeyManagerFactory] = {
    try {
      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(ks, pw)
      Some(kmf)
    } catch {
      case t: Throwable =>
        ConsoleIO.printlnErr(s"failed to initialize KeyManagerFactory")
        None
    }
  }

  def sslContext (kmf: KeyManagerFactory, trustManagers: Array[TrustManager]): SSLContext = {
    val ctx = SSLContext.getInstance("TLSv1.2")
    ctx.init(kmf.getKeyManagers,trustManagers,new SecureRandom)
    ctx
  }

  def sslServerSocket (ctx: SSLContext, port: Int): SSLServerSocket = {
    ctx.getServerSocketFactory.createServerSocket(port).asInstanceOf[SSLServerSocket]
  }

  def sslSocket (ctx: SSLContext, host: String, port: Int): SSLSocket = {
    ctx.getSocketFactory.createSocket(host,port).asInstanceOf[SSLSocket]
  }

  def localInetAddress (hostName: String): Option[InetAddress] = {
    try {
      Some(InetAddress.getByName(hostName))
    } catch {
      case x: UnknownHostException =>
        try {
          if (hostName.charAt(0).isDigit) None else Some(InetAddress.getByName(hostName + ".local"))
        } catch {
          case _: Throwable => None
        }
      case _: Throwable => None
    }
  }

  def isSameHost (addr: InetAddress, host: String): Boolean = {
    if (host.charAt(0).isDigit) { // host specified as IP address string
      // some OSes (Debian, Ubuntu) have /etc/hostname localhost entries of 127.0.1.1
      (addr.getHostAddress == host) || (host.startsWith("127.0.") && addr.isLoopbackAddress)
    } else {
      (addr.getHostName == host) || (addr.getCanonicalHostName == host) || ((host == "localhost") && addr.isLoopbackAddress)
    }
  }

  def isSameHost (addr1: InetAddress, addr2: InetAddress): Boolean = {
    (addr1.getHostAddress == addr2.getHostAddress) || (addr1.isLoopbackAddress && addr2.isLoopbackAddress)
  }

  def receive (socket: DatagramSocket, packet: DatagramPacket): Boolean = {
    try {
      socket.receive(packet)
      true
    } catch {
      case _:Exception => false
    }
  }

  def blockingHttpsPost (urlString: String, paramString: String): Either[String,String] = {
    try {
      val url = new URL(urlString)
      val con = url.openConnection.asInstanceOf[HttpsURLConnection]
      con.setRequestMethod("POST")
      con.setRequestProperty("User-Agent", "Mozilla/5.0")
      con.setRequestProperty("Accept-Language", "en-US,en;q=0.5")

      con.setDoOutput(true)
      val dos = new DataOutputStream(con.getOutputStream)
      dos.writeBytes(paramString)
      dos.flush

      val responseCode = con.getResponseCode // this blocks

      if (responseCode != HttpURLConnection.HTTP_OK) {
        Left(s"response: $responseCode")

      } else {
        val buf = new StringBuffer
        val is = new InputStreamReader(con.getInputStream)
        val cbuf = new Array[Char](4096)
        var n = is.read(cbuf, 0, cbuf.length)
        while (n >= 0) {
          buf.append(cbuf, 0, n)
          n = is.read(cbuf, 0, cbuf.length)
        }
        Right(buf.toString)
      }
    } catch {
      case x: Throwable => Left(s"https post failed: $x")
    }
  }

  def blockingHttpsPost (urlString: String, params: Map[String,String]): Either[String,String] = {
    val buf = new StringBuffer
    params.foreach { e =>
      if (buf.length > 0) buf.append('&')
      buf.append(e._1)
      buf.append('=')
      buf.append(e._2)
    }

    blockingHttpsPost(urlString,buf.toString)
  }

  def getMulticastNetworkInterface (ifcSpec: Option[String], addrSpec: Option[String], ipSpec: Byte = '*'): NetworkInterface = {
    def check (ifc: NetworkInterface): NetworkInterface = {
      if (!ifc.isUp) throw new RuntimeException(s"interface not up: ${ifc.getName()}")
      if (!ifc.supportsMulticast()) throw new RuntimeException(s"interface does not support multicast: ${ifc.getName()}")
      ifc
    }

    if (ifcSpec.isDefined) {
      // use the interface that is specified but check if it is up, supports multicast and bound to the optional address
      val ifc = check( NetworkInterface.getByName(ifcSpec.get))

      if (addrSpec.isDefined) {
        val addr = InetAddress.getByName(addrSpec.get)
        for (ia <- ifc.getInetAddresses().asScala) {
          if (ia == addr) return ifc
        }
        throw new RuntimeException(s"interface $ifcSpec not bound to address $addr")

      } else { // no address spec
        ifc
      }

    } else { // no interface spec, look up the first match
      if (addrSpec.isDefined) { // group address specified - look for an interface that is bound to it
        val addr = InetAddress.getByName(addrSpec.get)
        NetworkInterface.networkInterfaces().forEach { ifc=>
          if (ifc.supportsMulticast()) {
            for (ia <- ifc.getInetAddresses().asScala) {
              if (ia == addr) return check(ifc)
            }
          }
        }
        throw new RuntimeException(s"no multicast interface found for address: $addr")

      } else { // no interface and no address spec - use first multicast interface that is up, not p2p and not loopback
        NetworkInterface.networkInterfaces().forEach { ifc=>
          if (ifc.isUp && !ifc.isPointToPoint && !ifc.isLoopback) return ifc
        }
        throw new RuntimeException("no suitable multicast interface found")
      }
    }
  }

  def getMulticastInetAddress (addrSpec: Option[String], ifc: NetworkInterface, ipSpec: Byte = '*'): InetAddress = {
    if (ifc.supportsMulticast()) {
      if (addrSpec.isDefined) {
        val addr = InetAddress.getByName(addrSpec.get)
        for (ia <- ifc.getInetAddresses().asScala) {
          if (ia == addr) return addr
        }
        throw new RuntimeException(s"address $addrSpec not bound to interface ${ifc.getName()}")

      } else { // no address specified - use first one of provided interface
        for (ia <- ifc.getInetAddresses().asScala) {
          if (ipSpec == '*' ||
            (ipSpec == 4 && ia.isInstanceOf[Inet4Address]) ||
            (ipSpec == 6 && ia.isInstanceOf[Inet6Address])) return ia
        }
        throw new RuntimeException(s"no suitable address bound to interface ${ifc.getName()}")
      }

    } else {
      throw new RuntimeException(s"interface ${ifc.getName()} does not support multicast")
    }
  }
}
