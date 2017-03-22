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
import java.nio.file.StandardOpenOption._
import java.nio.file.{Files, Path, Paths}
import java.util
import javax.crypto.Cipher

import gov.nasa.race._
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.{ConsoleIO, CryptUtils}

/**
 * common base for en/decryption applications
 */
trait CryptApp {

  final val CRYPT_EXT = ".crypt"

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}"){
    var encrypt = true
    var deleteWhenDone = true
    var optKeyStore: Option[File] = None
    var optKeyAlias: Option[String] = None
    var file: Option[File] = None

    opt0("-e","--encrypt")("encrypt file (default = true)") {encrypt = true}
    opt0("-d","--decrypt")("decrypt file") {encrypt = false}
    opt0("--keep")("keep input file after encryption (default = false)") {deleteWhenDone = false}
    opt1("-k","--keystore")("<pathname>","optional keystore file") {a=> optKeyStore = parseExistingFileOption(a)}
    opt1("-a","--alias")("<aliasName>","keystore alias") {a=> optKeyAlias = Some(a)}
    requiredArg1("<pathName>","file to encrypt/decrypt") {a=> file = parseExistingFileOption(a)}
  }

  //--- those are the high level abstract methods provided by subtypes
  def encrypt (f: File, cipher: Cipher, deleteAfterEncryption: Boolean): Unit
  def decrypt (f: File, cipher: Cipher): Unit

  def main (args: Array[String]): Unit = {
    val opts = CliArgs(args){new Opts}.getOrElse{return}

    ifSome(opts.optKeyStore){ ks =>
      for (
        alias <- opts.optKeyAlias;
        file <- opts.file;
        pw <- ConsoleIO.promptPassword(s"enter password for $file: ");
        ks <- CryptUtils.loadKeyStore(ks,pw);
        key <- withSubsequent(CryptUtils.getKey(ks,alias,pw))(util.Arrays.fill(pw,' '));
        cipher <- CryptUtils.getCipher(key,opts.encrypt)
      ) {
        if (opts.encrypt) encrypt(file,cipher,opts.deleteWhenDone)
        else decrypt(file,cipher)
      }
    } orElse {
      none {
        for (file <- opts.file) {
          if (opts.encrypt) {
            ifSome(CryptUtils.getEncryptionCipher) {encrypt(file, _, opts.deleteWhenDone)}
          } else {
            ifSome(CryptUtils.getDecryptionCipher) {decrypt(file, _)}
          }
        }
      }
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
