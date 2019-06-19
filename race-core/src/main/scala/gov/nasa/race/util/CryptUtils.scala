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

import java.io.{File, FileInputStream}
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.{ByteBuffer, CharBuffer}
import java.security.{Key, KeyStore, MessageDigest}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

import com.typesafe.config._
import FileUtils._
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

/**
  * cryptography utilities
  * note: since this is a security critical object we try to minimize external dependencies
  *
  * <2do> console specifics should be factorized
  * <2do> we should support factoring in the host machine through some HW id (e.g. en0 MAC)
  */
object CryptUtils {

  private val secretKeyAlg = "AES"

  // we don't want to use a lib function attackers could override or intercept
  @inline @tailrec final def erase[T](a: Array[T], v: T, i: Int = 0): Unit = {
    if (i < a.length) {
      a(i) = v
      erase(a, v, i + 1)
    }
  }

  def keyStoreType (pathName: String): String = {
    if (pathName.endsWith(".jks")) "JKS"
    else  "PKCS12"  // every keystore has to support PKCS12 so we assume that as default
  }

  private def printlnErr (msg:String) = ConsoleIO.printlnErr(msg)

  //--- interactive cipher construction

  def getKey: Option[SecretKeySpec] = {
    try {
      System.console match {
        case null =>
          println("ERROR: no system console")
          None
        case cons =>
          cons.readPassword("enter passphrase (terminate with <cr>): ") match {
            case null | Array() => None
            case cs: Array[Char] => Some(destructiveGetKey(cs))
          }
      }
    } catch {
      case t: Throwable =>
        println(s"exception during passphrase entry: ${t.getClass}")
        None
    }
  }

  private def getCipher(mode: Int): Option[Cipher] = {
    getKey match {
      case None => None
      case Some(key) =>
        val cipher = Cipher.getInstance(secretKeyAlg)
        cipher.init(mode, key)
        //if (!key.isDestroyed) key.destroy() // be extra paranoid - don't depend on UserInfoAdapter implementation
        Some(cipher)
    }
  }
  def getEncryptionCipher: Option[Cipher] = getCipher(Cipher.ENCRYPT_MODE)
  def getDecryptionCipher: Option[Cipher] = getCipher(Cipher.DECRYPT_MODE)


  //--- key based cipher construction

  def getCipher(key: Key, mode: Int): Option[Cipher] = trySome {
    val cipher = Cipher.getInstance(secretKeyAlg)
    cipher.init(mode,key)
    cipher
  }

  def getCipher(key: Key, isEncryption: Boolean): Option[Cipher] = getCipher(key,if (isEncryption) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE)
  def getEncryptionCipher(key: Key): Option[Cipher] = getCipher(key,Cipher.ENCRYPT_MODE)
  def getDecryptionCipher(key: Key): Option[Cipher] = getCipher(key,Cipher.DECRYPT_MODE)

  //--- KeySpec encoding based cipher construction

  def destructiveGetKey (bs: Array[Byte]): SecretKeySpec = {
    try {
      new SecretKeySpec(bs, secretKeyAlg)
    } finally {erase(bs,0.toByte)}
  }

  private def getCipher(keyEnc: Array[Byte], mode: Int): Option[Cipher] = {
    val keySpec = destructiveGetKey(keyEnc)
    val cipher = Cipher.getInstance(secretKeyAlg)
    cipher.init(mode,keySpec)
    Some(cipher)
  }

  def getDecryptionCipher(keyEnc: Array[Byte]) = getCipher(keyEnc, Cipher.DECRYPT_MODE)
  def getEncryptionCipher(keyEnc: Array[Byte]) = getCipher(keyEnc, Cipher.ENCRYPT_MODE)


  //--- password based cipher construction

  private def getCipher(cs: Array[Char], mode: Int): Option[Cipher] = {
    val key = destructiveGetKey(cs)
    val cipher = Cipher.getInstance(secretKeyAlg)
    cipher.init(mode, key)
    Some(cipher)
  }
  def getDecryptionCipher(cs: Array[Char]) = getCipher(cs, Cipher.DECRYPT_MODE)
  def getEncryptionCipher(cs: Array[Char]) = getCipher(cs, Cipher.ENCRYPT_MODE)

  /**
    * get a SecretKeySpec for the provided password and erase the latter
    */
  def destructiveGetKey(cs: Array[Char]): SecretKeySpec = {
    try {
      val md = MessageDigest.getInstance("MD5").digest(Charset.forName("UTF-16").encode(CharBuffer.wrap(cs)).array)
      new SecretKeySpec(md, secretKeyAlg)
    } finally {erase(cs,' ')}
  }


  @inline private def long2Chars(v: Long) = {
    Array(
      ((v >> 48) & 0xffff).toChar,
      ((v >> 32) & 0xffff).toChar,
      ((v >> 16) & 0xffff).toChar,
      (v & 0xffff).toChar
    )
  }
  def getEncryptionCipher(v: Long) = getCipher(long2Chars(v), Cipher.ENCRYPT_MODE)
  def getDecryptionCipher(v: Long) = getCipher(long2Chars(v), Cipher.DECRYPT_MODE)

  def processFile2Bytes(file: File, cipher: Cipher): Array[Byte] = {
    // <2do> cipher should not be visible if read or decryption causes exception
    if (file.isFile) cipher.doFinal(Files.readAllBytes(file.toPath)) else Array.empty
  }

  def processFile2Config(file: File, cipher: Cipher): Option[Config] = {
    fileContentsAsBytes(file).map(bs => ConfigFactory.parseString(new String(cipher.doFinal(bs))))
  }

  // note the next two might throw exceptions that have to be handled in the caller

  def decryptFile(file: File, pp: Array[Char]): Option[String] = {
    if (file.isFile && pp.length > 0) {
      getDecryptionCipher(pp).map(c => new String(processFile2Bytes(file, c)))
    } else None
  }

  def decryptConfig(file: File, pp: Array[Char]): Option[String] = {
    if (file.isFile && pp.length > 0) {
      getDecryptionCipher(pp).map(decryptConfig(file,_))
    } else None
  }

  def decryptConfig(file: File, cipher: Cipher): String = {
    val ds = new String(processFile2Bytes(file, cipher))
    val conf = ConfigFactory.parseString(ds)
    var dconf = conf

    for (e <- conf.entrySet.asScala) {
      val k = e.getKey
      val v = e.getValue
      v.unwrapped match {
        case s: String => dconf = dconf.withValue(k, decryptedValue(s, cipher))
        case other => // keep as-is
      }
    }

    ConfigUtils.render(dconf)
  }

  //--- keystore utilities

  // unfortunately we cannot use PKCS12 for storing AES keys since there is no SecretKeyFactory
  // (see https://bugs.openjdk.java.net/browse/JDK-8149411)
  // todo - check if this is a problem with external clients (e.g. Python)
  private final val DefaultKeyStoreType = "jceks"

  /**
    * load a keystore from file
    * NOTE this does not erase the password since the caller might still need it for initializing the KeyManagerFactory
    * or accessing keys within the keystore
    */
  def loadKeyStore(file: File, cs: Array[Char], ksType: String=DefaultKeyStoreType): Option[KeyStore] = {
    var is: FileInputStream = null
    if (file.isFile) {
      try {
        is = new FileInputStream(file)
        val ks = KeyStore.getInstance(ksType)
        ks.load(is, cs)
        Some(ks)
      } catch {
        case t: Throwable =>
          printlnErr(s"could not load keystore $file")
          None
      } finally {
        if (is != null) is.close
      }
    } else {
      printlnErr(s"keystore file not found: $file")
      None
    }
  }

  def getKeyStore(file: File, ksType: String = DefaultKeyStoreType): Option[KeyStore] = {
    for (
      pw <- ConsoleIO.promptPassword(s"enter password for keystore $file: ");
      ks <- tryFinally(loadKeyStore(file, pw, ksType))(erase(pw,0.toChar))
    ) yield ks
  }

  // note that KeyStore.getKey forces us to provide the keystore pw even if the key was added without own pw
  def getKey (keyStore: KeyStore, alias: String, pw: Array[Char]): Option[Key] = {
    try {
      keyStore.getKey(alias,pw) match {
        case null => printlnErr(s"no such key: $alias"); None
        case key => Some(key)
      }
    } catch {
      case t: Throwable => printlnErr(s"error retrieving key: '$alias': $t"); None
    }
  }

  def getKeyMap (keyStore: KeyStore,pw: Array[Char]): Option[Map[String,Key]] = {
    trySome(keyStore.aliases.asScala.foldLeft(Map.empty[String, Key]) { (map, alias) => map + (alias -> keyStore.getKey(alias, pw)) })
  }

  //--- byte/char conversions

  // note these need to be used in pairs, both because of endian-ness and resulting byte array size

  def chars2Bytes(a: Array[Char]): Array[Byte] = {
    @tailrec def c2bb(i: Int, j: Int, cs: Array[Char], bs: Array[Byte]): Array[Byte] = {
      if (i < cs.length) {
        val c = cs(i)
        bs(j) = ((c >> 8) & 0xff).toByte
        bs(j + 1) = (c & 0xff).toByte
        c2bb(i + 1, j + 2, cs, bs)
      } else bs
    }
    c2bb(0, 0, a, new Array[Byte](a.length * 2))
  }

  def bytes2Chars(a: Array[Byte]): Array[Char] = {
    @tailrec def bb2c(i: Int, j: Int, bs: Array[Byte], cs: Array[Char]): Array[Char] = {
      if (j < cs.length) {
        cs(j) = ((bs(i) << 8) | bs(i + 1)).toChar
        bb2c(i + 2, j + 1, bs, cs)
      } else cs
    }
    bb2c(0, 0, a, new Array[Char](a.length / 2))
  }

  def destructiveChars2Bytes(cs: Array[Char]): Array[Byte] = {
    val bs = chars2Bytes(cs)
    erase(cs,' ')
    bs
  }

  def base64Encode(b: Array[Byte]): Array[Byte] = Base64.getEncoder().encode(b)
  def base64Decode(b: Array[Byte]): Array[Byte] = Base64.getDecoder().decode(b)
  def int2Bytes(n: Int): Array[Byte] = ByteBuffer.allocate(4).putInt(n).array
  def bytes2Int(bs: Array[Byte]): Int = ByteBuffer.wrap(bs).getInt

  // make sure we erase temporary translation buffer contents during encryption

  def encrypt64(a: Array[Byte], cipher: Cipher): Array[Byte] = base64Encode(cipher.doFinal(a))
  def decrypt64(a: Array[Byte], cipher: Cipher): Array[Byte] = cipher.doFinal(base64Decode(a))

  def encrypt64FromChars(a: Array[Char], cipher: Cipher): Array[Byte] = {
    val bs = chars2Bytes(a)
    val res = encrypt64(bs, cipher)
    erase(bs,0.toByte)
    res
  }
  def decrypt64ToChars(a: Array[Byte], cipher: Cipher): Array[Char] = {
    val bs = decrypt64(a, cipher)
    val res = bytes2Chars(bs)
    erase(bs,0.toByte)
    res
  }

  def encrypt64(s: String, cipher: Cipher): String = {
    val bs = s.getBytes
    val res = new String(base64Encode(cipher.doFinal(bs)))
    erase(bs,0.toByte)
    res
  }
  def decrypt64(s: String, cipher: Cipher): String = new String(cipher.doFinal(base64Decode(s.getBytes)))

  def encryptedValue(s: String, cipher: Cipher): ConfigValue = {
    ConfigValueFactory.fromAnyRef(encrypt64(s, cipher))
  }
  def decryptedValue(s: String, cipher: Cipher): ConfigValue = {
    ConfigValueFactory.fromAnyRef(decrypt64(s, cipher))
  }
}
