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

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption._
import javax.crypto.Cipher

import com.typesafe.config._
import gov.nasa.race.config.ConfigUtils
import gov.nasa.race.util.CryptUtils._

import scala.jdk.CollectionConverters._


/**
  * encryption/decryption for *.conf files
  * note that we store values as encrypted base64
  */
object CryptConfig extends CryptApp {

  override def decrypt (file: File, cipher: Cipher): Unit = {
    try {
      val s = decryptConfig(file, cipher)
      //println(s"--------\n$s---------")
      val path = getDecryptedPath(file)
      Files.write(path, s.getBytes, WRITE, CREATE, TRUNCATE_EXISTING)
      println("..done")
    } catch {
      case t: Throwable => abort(s"exception while decrypting: ${t.getMessage}")
    }
  }

  override def encrypt (file: File, cipher: Cipher, deleteWhenDone: Boolean): Unit = {
    try {
      println(s"encrypting configuration file $file...")

      val userProps = ConfigUtils.createConfig(
        "user.home" -> System.getProperty("user.home"),
        "user.dir" -> System.getProperty("user.dir"),
        "user.name" -> System.getProperty("user.name")
      )

      val conf = ConfigFactory.parseFile(file).resolveWith(userProps)
      var econf = conf

      for (e <- conf.entrySet.asScala) {
        val k = e.getKey
        val v = e.getValue
        v.unwrapped match {
          case s: String => econf = econf.withValue(k, encryptedValue(s, cipher))
          case n: Number => econf = econf.withValue(k, encryptedValue(n.toString, cipher))
          case other =>
            println(s"warning: un-encoded value for key '$k' stored")
        }
      }

      val s = ConfigUtils.render(econf)
      //println(s"--------\n$s---------")
      val path = getEncryptedPath(file)
      Files.write(path, cipher.doFinal(s.getBytes), WRITE, CREATE, TRUNCATE_EXISTING)

      if (deleteWhenDone) file.delete
      println("..done")
    } catch {
      case t: Throwable => abort(s"exception while encrypting: ${t.getMessage}")
    }
  }
}
