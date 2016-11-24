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
import javax.crypto.Cipher

/**
 * application to encrypt/decrypt files
 * (used to create vaults for encrypted key/value pairs such as user credentials etc.)
 */
object CryptFile extends CryptApp {

  override def encrypt (file: File, cipher: Cipher, deleteWhenDone: Boolean): Unit = {
    try {
      println(s"encrypting $file..")
      processFile2File(file, getEncryptedPath(file), cipher)
      if (deleteWhenDone) file.delete()
      println("..done")
    } catch {
      case t: Throwable => abort("exception while encrypting")
    }
  }

  override def decrypt (file: File, cipher: Cipher): Unit = {
    try {
      println(s"decrypting $file..")
      processFile2File(file, getDecryptedPath(file), cipher)
      println("..done")
    } catch {
      case t: Throwable => abort("exception while decrypting")
    }
  }
}
