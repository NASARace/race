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

import java.util
import com.jcraft.jsch.{UIKeyboardInteractive, UserInfo}

import java.io.{BufferedInputStream, BufferedReader, InputStreamReader}
import scala.annotation.tailrec
import scala.io.StdIn

/**
  * singleton for getting UserInfo objects
  * this is intentionally a global, to make sure this can only be set once (by the RACE driver)
  */
object UserInfoFactory {

  private var _factory: Option[UserInfoFactory] = None

  def factory_= (uif: Option[UserInfoFactory]): Unit = {
    if (_factory.isEmpty) _factory = uif
    else throw new SecurityException("attempt to reset UserInfoFactory")
  }
  def factory = _factory
}

trait UserInfoFactory {
  def getUserInfo: UserInfoAdapter
}

object UserInfoAdapter {
  private var pendingRequests = 0

  // this is redundant since the same is used in CryptUtils, but since this is a highly
  // security relevant class we want to limit the dependencies
  @inline @tailrec def erase (a: Array[Char], i: Int=0): Unit = {
    if (i < a.length) {
      a(i) = 0
      erase(a,i+1)
    }
  }

  def requestUserInput[T] (f: => T) = {
    pendingRequests += 1
    val res = f
    pendingRequests -= 1
    res
  }

  def hasPendingRequests = pendingRequests > 0
}

trait UserInfoAdapter extends UserInfo with UIKeyboardInteractive {

  // <2do> this should not be stored in the clear. It's bad enough the RFC forces us
  // to store it at all
  protected[this] var pwc: Array[Char] = _
  protected[this] var ppc: Array[Char] = _

  private def getEntry(storeAction: (Array[Char]) => Unit, getAction: => Array[Char]) = {
    val c = getAction
    if (c != null){
      val e = new String(c)
      util.Arrays.fill(c, ' ')
      storeAction(null)
      e
    } else null
  }

  // to be provided by concrete type
  protected[this] def entryPrompt(prompt: String, storeAction: (Array[Char]) => Unit, getAction: => Array[Char]): Boolean

  // the UIKeyboardInteractive method to be provided by concrete type
  override def promptKeyboardInteractive (destination: String, name: String, instruction: String,
                                          prompts: Array[String], echos: Array[Boolean]): Array[String]

  override def promptYesNo (prompt: String): Boolean
  override def showMessage (msg: String): Unit

  // generic interface method implementation
  override def getPassphrase: String = getEntry(ppc_=, ppc)
  override def getPassword: String = getEntry(pwc_=, pwc)

  override def promptPassphrase (prompt: String): Boolean = entryPrompt(prompt,ppc_=,ppc)
  override def promptPassword (prompt: String): Boolean = entryPrompt(prompt,pwc_=,pwc)
}

object ConsoleUserInfoAdapter extends UserInfoFactory {
  override def getUserInfo: UserInfoAdapter = new ConsoleUserInfoAdapter
}

/**
  * console based implementation of jcraft.jsch.UserInfo, which in turn
  * implements RFC 4256
  *
  * NOTE - this class is highly sensitive, we accept code duplication in favor of
  * avoiding external dependencies since we should do escape analysis
  */
class ConsoleUserInfoAdapter extends UserInfoAdapter {

  final val msgColor = scala.Console.WHITE
  final val resetColor = scala.Console.RESET

  private var readLine: ()=>String = {null}
  private var readPw: ()=>Array[Char] = {null}

  val console = System.console
  if (console == null) {
    System.err.println("\\u001b[31mWARNING - no system console, cannot read passwords masked.\\u001b[0m")

    readLine = () => {
      (new BufferedReader(new InputStreamReader(System.in))).readLine()
    }
    readPw = () => {
      val cs = readLine().toCharArray
      if (cs != null && cs.nonEmpty) System.out.print("\\u001b[2K\r") // at least erase current line
      cs
    }

  } else {
    readLine = ()=>{console.readLine()}
    readPw = ()=>{console.readPassword()}
  }

  override def promptYesNo(message: String): Boolean = {
    print(s"$msgColor$message [y/n]$resetColor ")
    Console.flush()
    StdIn.readLine() match {
      case "y" | "Y" => true
      case _ => false
    }
  }

  override def showMessage(message: String): Unit = {
    print(s"$msgColor$message$resetColor ")
    Console.flush()
  }

  override def promptKeyboardInteractive (destination: String, name: String, instruction: String,
                                          prompts: Array[String], echos: Array[Boolean]): Array[String] = {
    if (prompts.length != echos.length) throw new IllegalArgumentException("differing prompts/echos sizes")
    val res = new Array[String](prompts.length)

    showMessage(s"user authentication for $destination")
    if (instruction != null) showMessage(instruction)

    for (idx <- 0 to prompts.length) {
      showMessage(prompts(idx))
      UserInfoAdapter.requestUserInput {
        res(idx) = if (echos(idx)) {
          readLine()
        } else {
          if (console == null) {
            System.err.print("\\u001b[31mWARNING - no console, password will not be masked\\u001b[0m")
            System.err.flush()
          }
          val cs = readPw()
          val s = new String(cs)
          s
        }
      }
      if (res(idx).isEmpty) return null
    }
    res
  }

  // to be provided by concrete type
  override protected[this] def entryPrompt(prompt: String, storeAction: (Array[Char]) => Unit, getAction: => Array[Char]): Boolean = {
    storeAction(null)
    showMessage(prompt)
    UserInfoAdapter.requestUserInput {
      if (console == null) System.err.println("WARNING - no console, password will not be masked")
      val cs = readPw()
      if (cs.length > 0) {
        storeAction(cs)
        getAction != null
      } else false
    }
  }
}