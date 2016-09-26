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
import java.nio.file.StandardOpenOption._
import java.nio.file.{Files, Path, Paths}
import java.util
import javax.crypto.Cipher

import gov.nasa.race.common._
import gov.nasa.race.common.CliArgUtils.OptionChecker
import scopt.OptionParser

/**
 * common base for en/decryption applications
 */
trait CryptApp {

  final val CRYPT_EXT = ".crypt"

  case class CliOpts (encrypt: Boolean=false/** encryption/decryption mode */,
                      deleteWhenDone: Boolean=false/** delete un-encrypted file after successful encryption */,
                      optKeystore: Option[File] = None/** optional keystore file */,
                      optKeyAlias: Option[String] = Some("vault")/** optional keystore key alias */,
                      file: Option[File] = None/** what to encrypt/decrypt */
                     )

  def getAppName: String = {
    val appName = getClass.getSimpleName.toLowerCase
    if (appName.endsWith("$")) appName.substring(0, appName.length-1) else appName
  }

  def cliParser = {
    new OptionParser[CliOpts](getAppName) with OptionChecker {
      help("help") abbr ("h") text ("print this help")
      opt[Unit]('d', "decrypt") text "decrypt file" optional() action {(_, o) => o.copy(encrypt=false)}
      opt[Unit]('e', "encrypt") text "encrypt file" optional() action {(_, o) => o.copy(encrypt=true)}
      opt[Unit]("delete") text "delete input file after encryption" optional() action {(_,o) => o.copy(deleteWhenDone=true)}
      opt[File]('k',"keystore") text "optional keystore file" optional() action{(f,o) =>
        o.copy(optKeystore = Some(f)) } validate checkFile
      opt[String]('a', "alias") text "alias in case keystore is used (default=\"mykey\")" optional() action {(v,o) => o.copy(optKeyAlias=Some(v))}
      arg[File]("<file>") text "file to encrypt/decrypt" required() minOccurs(1) maxOccurs(1) action { (f,o) =>
        o.copy(file = Some(f)) } validate checkFile
    }
  }

  //--- those are the high level abstract methods provided by subtypes
  def encrypt (f: File, cipher: Cipher, deleteAfterEncryption: Boolean): Unit
  def decrypt (f: File, cipher: Cipher): Unit

  def main (args: Array[String]): Unit = {
    val parser = cliParser
    parser.parse(args, CliOpts()) match {
      case Some(CliOpts(isEncryption,deleteWhenDone,None,_,Some(file))) =>
        // no keystore, create cipher on the fly
        if (isEncryption) {
          ifSome(CryptUtils.getEncryptionCipher){ encrypt(file,_,deleteWhenDone) }
        } else {
          ifSome(CryptUtils.getDecryptionCipher){ decrypt(file,_) }
        }
      case Some(CliOpts(isEncryption,deleteWhenDone,Some(keystore),Some(alias),Some(file))) =>
        // get key from keystore/alias (we assume password-less keys)
        for (
          pw <- ConsoleIO.promptPassword(s"enter password for $file: ");
          ks <- CryptUtils.loadKeyStore(keystore,pw);
          key <- withSubsequent(CryptUtils.getKey(ks,alias,pw))(util.Arrays.fill(pw,' '));
          cipher <- CryptUtils.getCipher(key,isEncryption)
        ) {
          if (isEncryption) {
            encrypt(file,cipher,deleteWhenDone)
          } else {
            decrypt(file,cipher)
          }
        }
      case other => parser.showUsageAsError()
    }
  }

  def abort[T<:AnyRef] (msg: String): T = {
    System.err.println(s"$msg, terminating")
    System.exit(1)
    null.asInstanceOf[T] // just to please the compiler
  }

  def processFile2File(file: File, path: Path, cipher: Cipher): Unit = {
    Files.write(path, CryptUtils.processFile2Bytes(file,cipher), WRITE, CREATE, TRUNCATE_EXISTING)
  }

  def getEncryptedPath (f: File): Path = {
    Paths.get(f.getPath + CRYPT_EXT)
  }

  def getDecryptedPath(f: File): Path = {
    val p = f.getPath
    if (p.endsWith(CRYPT_EXT)){
      Paths.get(p.substring(0, p.length - CRYPT_EXT.length))
    } else {
      Paths.get(p)
    }
  }
}
