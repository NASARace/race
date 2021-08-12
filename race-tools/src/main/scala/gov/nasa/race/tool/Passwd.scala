/*
 * Copyright (c) 2017, United States Government, as represented by the
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
import java.util

import gov.nasa.race._
import gov.nasa.race.http.{PwUserStore, User}
import gov.nasa.race.main.CliArgs
import gov.nasa.race.util.{ConsoleIO, CryptUtils, StringUtils}

/**
  * tool to update a argon2 based password file
  */
object Passwd {

  object Op extends Enumeration {
    val Add, Remove, List = Value
  }

  class Opts extends CliArgs(s"usage ${getClass.getSimpleName}") {
    var op: Op.Value = Op.List
    var uid: String = ""
    var roles: String = User.UserRole
    var file: File = new File("passwd")

    opt0("-l","--list")("show matching entries (default)") { op = Op.List }
    opt0("-a","--add")("add/replace user entry") { op = Op.Add }
    opt0("-r","--remove")("remove user entry") { op = Op.Remove }
    opt1("-f","--file")("<pathname>",s"password file (default: $file)") { a=> file = parseFile(a)}
    opt1("--roles")("<role>,..", s"comma separated list of roles of user (default: $roles") { a=> roles = a}
    requiredArg1("<uid>","user to add/remove") { a=> uid = a }
  }

  def main (args: Array[String]): Unit = {
    val opts = CliArgs(args){new Opts}.getOrElse{return}
    val store = new PwUserStore(opts.file)

    opts.op match {
      case Op.List =>
        val results = store.matching(StringUtils.globToRegex(opts.uid))
        if (results.nonEmpty) {
          ConsoleIO.printlnOut("matching user entries:")
          for ((e, i) <- results.zipWithIndex) {
            val hash = new String(e._2.hash)
            val uid = e._2.user.uid
            val roles = e._2.user.roles.mkString(",")
            ConsoleIO.printlnOut(f"[$i] $uid:$roles:$hash")
          }
        } else {
          ConsoleIO.printlnOut("no matching entries")
        }

      case Op.Add =>
        ifSome(promptPassword) { pw =>
          store.addUser(opts.uid, pw, StringUtils.splitToSeq(opts.roles,","))
          saveStore(store)
        }

      case Op.Remove =>
        if (store removeUser opts.uid) saveStore(store)
    }
  }

  def saveStore(store: PwUserStore): Unit = {
    if (!store.saveFile) ConsoleIO.printlnErr(s"error saving ${store.file}")
    else ConsoleIO.printlnOut(s"${store.file} saved")
  }

  def promptPassword: Option[Array[Byte]] = {
    for (
      pw1 <- ConsoleIO.promptPassword("enter password: ");
      pw2 <- ConsoleIO.promptPassword("reenter password: ");
      _ <- if (!util.Arrays.equals(pw1,pw2)) {
        ConsoleIO.printlnErr("passwords did not match, abort")
        None
      } else Some(true)
    ) yield new String(pw1).getBytes()
  }
}
