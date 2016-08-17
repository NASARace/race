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

package gov.nasa.race.common

import java.io._
import java.net.Socket
import java.security.{KeyStore, SecureRandom}
import java.security.cert.X509Certificate
import java.lang.{StringBuilder => JStringBuilder}
import javax.net.SocketFactory
import javax.net.ssl._

import scala.annotation.tailrec

/**
 * common networking related functions
 */
object NetUtils {

  val HostPortRE = """([^:/]+)(?::(\d+))?""".r
  val PortHostPortRE = """(\d+):([^:/]+):(\d+)""".r
  val UrlRE = """(.+)://(?:([\w]+)@)?([^:/]+)(?::(\d+))?(?:/(.+))?""".r // scheme,user,host,port,path

  type UrlString = String

  case class UrlSpec(scheme: String, user: String, host: String, port: Int, path: String) {
    def isSameLocationAs(other: UrlSpec) = scheme == other.scheme && host == other.host && port == other.port
  }

  def parseUrl(url: String): Option[UrlSpec] = {
    url match {
      case UrlRE(scheme, usr, host, port, path) => Some(UrlSpec(scheme, usr, host, port.toInt, path))
      case other => None
    }
  }

  def userInUrl(url: String): Option[String] = {
    url match {
      case UrlRE(_, user, _, _, _) => Some(user)
      case other => None
    }
  }

  def isSameUrlLocation(url1: String, url2: String): Boolean = {
    liftPredicate(parseUrl(url1), parseUrl(url2)) { (u1, u2) => u1.isSameLocationAs(u2) }
  }

  def isLocalhost(hostname: String) = hostname == "localhost" || hostname == "127.0.0.1"


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
}
