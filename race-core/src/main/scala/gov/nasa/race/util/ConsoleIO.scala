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

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.RaceActorSystem

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import gov.nasa.race.util.FileUtils._
import gov.nasa.race.util.StringUtils._
import gov.nasa.race.util.ThreadUtils._

import scala.collection.mutable
import scala.io.StdIn

/**
 * class that implements console menus using standard streams
 *
 * {{{
 *   menu ("enter command [1:exit, 2:retry]> ") {
 *     case "1" => ...
 *     case "2" => ... repeatMenu
 *   }
 * }}}
 *
 * Menu calls can be nested, and can happen concurrently from different threads.
 * Concurrent menu calls are processed in invocation order. Looped (repeated) menus
 * execute in constant space.
 *
 * Implementation note: pending menus are kept in a global stack, and only the
 * thread of the first menu entry (stack bottom) is allowed to do StdIn.readLine()
 * calls since this is a invisibly blocking, non-interruptible, native function. 
 * In case of overlayed concurrent menu calls, this reader thread becomes a dispatcher
 * for the menu on top of the stack, synchronizing via the (user code invisible) 
 * monitor for the top menu object
 *
 * <verify> no deadlocks (same locking order in all threads)
 * <verify> only one thread at a time calls StdIn.readLine
 * <verify> no missed signals (reader thread does not notify before overlay thread is blocked)
 * <verify> no menu lock held when executing user code (calling cmds()) 
 */

object ConsoleIO {
  // types
  type MenuFunc =  PartialFunction[String, Any]

  /**
    * a configured (programmatically constructed) MenuFunc that can be used as a shortcut to send messages to actors.
    * Configuration looks like this:
    *   menu = [
    *     { key = "1"                   // menu item key
    *       text = "cut connection"     // menu item text
    *       actor = "upstreamConnector" // the actor name the msg is sent to
    *       msg = "cut"                 // the command string that is sent to actor
    *       continue = true|false       // optional, default is false (i.e. menu exits after command got executed)
    *     }, ...
    *   ]
    *
    * the cmdSpecs parameter value can be obtained from the containing config by means of config.getConfigSeq(key)
    */
  class AppMenu (ras: RaceActorSystem, cmdSpecs: Seq[Config]) extends MenuFunc {
    case class Cmd (key: String, text: String, actorName: String, msg: String, cont: Boolean) {
      def execute(): Any = {
        ras.send(actorName,msg)
        if (cont) repeatMenu else exitMenu
      }

      override def toString: String = key + ':' + text
    }

    val cmds: mutable.LinkedHashMap[String,Cmd] = cmdSpecs.foldLeft(new mutable.LinkedHashMap[String,Cmd]) { (acc, c) =>
      val key = c.getString("key")
      val text = c.getString("text")
      val actor = c.getString("actor")
      val msg = c.getString("msg")
      val cont = c.getBooleanOrElse("continue", false)
      acc += key -> Cmd(key,text,actor,msg,cont)
    }

    val prompt: String = cmds.values.mkString("enter app command [", ", ", "]\n")

    //--- the PartialFunction implementation

    override def apply (v: String): Any = {
      cmds.get(v) match {
        case Some(cmd) => cmd.execute()
        case None => exitMenu
      }
    }

    override def isDefinedAt(key: String): Boolean = cmds.contains(key)
  }

  class MenuCall (val prompt: String, val cmds: MenuFunc, val thread: Thread)

  final val ClearScreen = "\u001b[2J\u001b[;H"
  final val EraseScreen = "\u001b[3J\u001b[;H"
  final val ClearLine   = "\u001b[1K\u001b[0G"

  // constants
  final val repeatMenu = "REPEAT_MENU"
  final val exitMenu = "EXIT_MENU"
  final val menuColor = scala.Console.CYAN
  final val errorColor = scala.Console.RED
  final val infoColor = scala.Console.WHITE
  final val resetColor = scala.Console.RESET
  final val reverseColor = scala.Console.REVERSED

  val jConsole = System.console()

  // various ANSI terminal commands
  @inline def clearScreen = print(ClearScreen)
  @inline def eraseScreen = print(EraseScreen)
  @inline def clearLine = print(ClearLine)

  @inline def line(msg: String) = {
    print(ClearLine)
    print(msg)
  }

  // shared data
  private var input: String = _
  private var menuStack = List.empty[MenuCall]
  private val globalLock = new ReentrantLock

  private def loopReader(currentThread: Thread): Boolean = {
    while (currentThread != menuStack.head.thread) { // stack cannot shrink below currentThread
      if (menuStack.head.thread.getState() == Thread.State.WAITING){
        // nobody else notifies but the reader, so no further sync required
        menuStack.head.synchronized {
          menuStack.head.notify  // wake up follower, keep reading from currentThread
          return true
        }
      }
      Thread.`yield`
    }
    false
  }

  def menu (m: AppMenu): Unit = menu(m.prompt)(m)

  def menu (prompt: String, action: =>Any = {})(cmds: MenuFunc): Unit = {
    var again = false
    val t = Thread.currentThread
    val m = new MenuCall(prompt,cmds,t)

    globalLock.lock                           // (1)
    menuStack = m :: menuStack                // (2)

    do {
      if (!globalLock.isHeldByCurrentThread){ // (3) condition cannot change outside currentThread
        globalLock.lock                       // (4)
      }

      action // action to be performed synchronously before each menu input prompt
      print(s"\n$menuColor$prompt$resetColor")
      scala.Console.flush()

      if (t eq menuStack.last.thread){        // (5) critical branch (5|8)
        globalLock.unlock                     // (6)
        do {
          input = StdIn.readLine()            // (7) block reader
        } while (loopReader(t))

      } else {                                // (8)
        globalLock.unlock                     // (9)
        m.synchronized {
          m.wait                              // (10) block follower
        }
      }

      try {                             
        again = (cmds(input) == repeatMenu)   // (11)
      } catch {
        case _: MatchError =>
          again = if (input == null){
            println(s"${menuColor}broken input stream, skipping menu$resetColor")
            false
          } else {
            if (input.size > 0) println(s"${menuColor}invalid input: '$input', please try again$resetColor")
            true
          }
      }
    } while (again)

    globalLock.lock                           // (12)
    menuStack = menuStack.tail                // (13)
    if (!menuStack.isEmpty && (t ne menuStack.head.thread)){
      print(s"\n$menuColor${menuStack.head.prompt}$resetColor")
      scala.Console.flush()
    }
    globalLock.unlock                         // (14)
  }

  //--- other utility functions
  private def _prompt(msg:String,emptyAction: => Option[String]): Option[String] = {
    print(s"$menuColor$msg$resetColor")
    scala.Console.flush()

    val inp = StdIn.readLine()
    if (inp.length == 0) emptyAction else Some(inp)
  }

  def prompt(msg: String): Option[String] = _prompt(msg,None)
  def promptOrElse(msg: String, defaultVal: String): String = _prompt(msg,Some(defaultVal)).get

  def promptPassword(msg: String): Option[Array[Char]] = {
    print(s"$menuColor$msg$resetColor")
    scala.Console.flush()
    val pw = jConsole.readPassword()
    if (pw.length == 0) None else Some(pw)
  }

  private def _promptInt (msg: String, emptyAction: => Option[Int]): Option[Int] = {
    print(s"$menuColor$msg$resetColor")
    scala.Console.flush()

    val inp = StdIn.readLine()
    if (inp.length == 0) {
      emptyAction
    } else {
      try {
        Some(inp.toInt)
      } catch {
        case x: NumberFormatException =>
          printlnErr("invalid number format")
          emptyAction
      }
    }
  }

  def promptInt(msg: String) = _promptInt(msg,None): Option[Int]
  def promptIntOrElse(msg: String, defaultValue: Int): Int = _promptInt(msg,Some(defaultValue)).get

  private def _promptFile (msg: String, dir: String, emptyAction: => Option[File],
                           initialPattern: Option[String] = None): Option[File] = {
    def _select (dir: String, pattern: String): Either[Option[String],File] = {
      val files = getMatchingFilesIn(dir,pattern)
      if (files.isEmpty) {
        Left(None)
      } else if (files.size == 1 && !isGlobPattern(pattern)) {
        Right(files.head)
      } else {
        println(s"${menuColor}matching paths:")
        for ( (f,i) <- files.zipWithIndex) {
          var pn = f.getPath.substring(dir.length+1)
          if (f.isDirectory) pn += '/'
          println(s"  $i: $pn")
        }
        prompt("enter choice number or new pattern: ") match {
          case Some(inp) =>
            inp match {
              case IntRE(intLiteral) => // a number
                val choiceIdx = intLiteral.toInt
                if (choiceIdx >= 0 && choiceIdx < files.length) {
                  Right(files(choiceIdx))
                } else {
                  println(s"illegal choice number: $choiceIdx")
                  Left(None)
                }
              case QuotedRE(newPattern) => Left(Some(newPattern))
              case newPattern => Left(Some(newPattern))
            }
          case None => Left(None)
        }
      }
    }

    var pattern = initialPattern match {
      case Some(pat) => promptOrElse(s"$msg: ", pat)
      case None => prompt(msg) match {
        case Some(pat) => pat
        case None => return emptyAction // bail out
      }
    }

    while (true){
      _select(dir,pattern) match {
        case Right(file) =>
          if (file.isFile) return Some(file)
          else pattern = file.getPath.substring(dir.length+1) + "/*" // next round, we need a file
        case Left(Some(newPattern)) => pattern = newPattern
        case Left(None) => return emptyAction
      }
    }
    emptyAction // can't get here, but the compiler doesn't get the match is exhaustive
  }

  def promptExistingFile(msg: String, dir: String): Option[File] = _promptFile(msg,dir,None)
  def promptExistingFile(msg: String, dir: String, pattern: String): Option[File] = _promptFile(msg,dir,None,Some(pattern))
  def promptExistingFileOrElse(msg: String, dir: String, defaultValue: File): File = _promptFile(msg,dir,Some(defaultValue)).get
  def promptExistingFileOrElse(msg: String, dir: String, defaultValue: File, pattern: String): File = _promptFile(msg,dir,Some(defaultValue),Some(pattern)).get


  def printOut (msg: String) = scala.Console.out.print(msg)
  def printlnOut (msg: String) = scala.Console.out.println(msg)
  def printErr (msg: String) = scala.Console.err.print(msg)
  def printlnErr (msg: String) = scala.Console.err.println(msg)

  def reportThrowable (throwable: Throwable): Unit = {
    System.err.println(s"${errorColor}EXCEPTION: ${throwable}${resetColor}")
    menu("enter command: [1:show details, 2:continue, 3:rethrow, 4:terminate thread, 9: terminate process]"){
      case "1" =>
        throwable.printStackTrace(System.err)
        repeatMenu
      case "2" => // do nothing
      case "3" =>
        throw throwable
      case "4" =>
        stop(Thread.currentThread()) // only way that /could/ work
      case "9" =>
        System.exit(1)
    }
  }

  def tryCatchAllWith[T] (cont: T)(block: => T): T = {
    try {
      block
    } catch {
      case t: Throwable =>
        reportThrowable(t)
        cont
    }
  }

  def tryCatchAll (block: => Any): Unit = {
    try {
      block
    } catch {
      case t: Throwable =>
        reportThrowable(t)
    }
  }
}

