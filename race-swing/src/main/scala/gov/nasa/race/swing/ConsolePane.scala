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

package gov.nasa.race.swing

import java.awt.{Color, Font}
import java.io.{OutputStream, PrintStream}
import javax.swing.JTextPane
import javax.swing.text.{DefaultStyledDocument, Style => JStyle, StyleConstants, StyleContext}

import gov.nasa.race.core.LogAppender
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._

import scala.swing._
import scala.swing.event.{ButtonClicked, EditDone, SelectionChanged}

/**
  * a text area to display formatted console messages, supporting
  * line limits and message-type specific styles
  */
class ConsolePane extends TextPane {
  class WrappableJTextPane extends JTextPane {
    override def getScrollableTracksViewportWidth: Boolean = {
      if (wrapLines) false
      else {
        val parent = getParent
        if (parent != null) getUI.getPreferredSize(this).width <= parent.getSize.width
        else true
      }
    }
  }
  override lazy val peer = new WrappableJTextPane

  // save the current ones
  val sysOut0 = System.out
  val sysErr0 = System.err

  val sc = new StyleContext
  // add our own defaultStyle so that we can modify per pane instance
  val defaultStyle = sc.addStyle("default", sc.getStyle(StyleContext.DEFAULT_STYLE))
  val commentStyle = sc.addStyle("comment", defaultStyle)
  var styles = Map[String,JStyle] (defaultStyle.getName -> defaultStyle, commentStyle.getName -> commentStyle)
  val doc = new DefaultStyledDocument(sc)
  val rootElement = doc.getDefaultRootElement
  var maxLines = Int.MaxValue
  var wrapLines = false
  var echo = false // do we also echo to previously active std streams

  peer.setDocument(doc)
  editable = false

  StyleConstants.setFontFamily(defaultStyle, "monospaced")
  StyleConstants.setFontSize(defaultStyle, 12)

  //--- end ctor

  def append (s: String, style: JStyle=defaultStyle) = {
    if (echo) sysOut0.print(s)
    executeInEDT {
      doc.insertString(doc.getLength, s, style)
      //ensureLineLimit
    }
  }

  def appendLn = {
    if (echo) sysOut0.println()
    executeInEDT {
      doc.insertString(doc.getLength, "\n", null)
      ensureLineLimit
    }
  }

  def ensureLineLimit = {
    if (maxLines > 0) {
      val nRemove = rootElement.getElementCount-1 - maxLines
      if (nRemove > 0) {
        val newStart = rootElement.getElement(nRemove).getStartOffset
        doc.remove(0, newStart)
      }
    }
  }

  //--- style management (subclasses might keep direct references)
  def addStyle (styleName: String, baseStyle: JStyle = defaultStyle): JStyle = {
    val style = sc.addStyle(styleName, baseStyle)
    styles = styles + (style.getName -> style)
    style
  }

  def setStyleFont (style: JStyle, font: Font) = {
    StyleConstants.setFontFamily(style, font.getFamily)
    StyleConstants.setFontSize(style, font.getSize)
    font.getStyle match {
      case Font.BOLD => StyleConstants.setBold(style, true)
      case Font.ITALIC => StyleConstants.setItalic(style, true)
      case other => // ignore for now
    }
  }

  def setLineSpacing (lineSpacing: Float) = StyleConstants.setLineSpacing(defaultStyle, lineSpacing)

  def createHeader (title: String, maxLines: Int=0) = new ConsoleHeader(title,this,maxLines)
}

/**
  * a standard header panel to control a ConsolePane
  * this includes controls to set a max lines count and to select a logfile
  */
class ConsoleHeader (title: String, consolePane: ConsolePane, maxLines: Int = 0) extends GBPanel {

  val titleLabel = new Label(title).styled("title")
  titleLabel.preferredSize = (80, 20)

  val filePanel = new FileSelectionPanel("log to:", Some("tmp"), Some(".log"))( f =>
    println(s"@@ log to $f")
  ).styled()
  filePanel.maximumSize = (200, 20)

  val maxLinesCb = new CheckBox("max lines:").styled()
  maxLinesCb.selected = maxLines > 0
  listenTo(maxLinesCb)

  val maxLinesInput = new IntInput(6).styled("numField")
  maxLinesInput.value = maxLines
  listenTo(maxLinesInput)

  val commentInput = new TextField(10).styled("stringField")
  listenTo(commentInput)

  reactions += {
    case ButtonClicked(`maxLinesCb`) => consolePane.maxLines = maxLinesInput.value
    case EditDone(`maxLinesInput`) =>
      if (maxLinesCb.selected) consolePane.maxLines = maxLinesInput.value
    case EditDone(`commentInput`) =>
      consolePane.append(commentInput.text, consolePane.commentStyle); consolePane.appendLn
      commentInput.text = ""
  }

  // this has to be called explicitly after construction
  // override if there are additional components
  def layoutChildren: ConsoleHeader = {
    var c = new Constraints(gridy = 0, fill = Fill.Horizontal, anchor = Anchor.West, insets = (2,2,2,2))
    layout(titleLabel) = c
    layout(new Label().styled()) = c.weightx(0.5) // a filler
    layout(new Label("cmt:").styled("labelFor")) = c.anchor(Anchor.East).weightx(0)
    layout(commentInput) = c
    layout(maxLinesCb) = c
    layout(maxLinesInput) = c
    layout(filePanel) = c
    this
  }
}

//--- PrintStream based console for standard output streams

object NullOutputStream extends OutputStream {
  override def write(b: Int) = {}
  override def write(b: Array[Byte]) = {}
  override def write(b: Array[Byte], off: Int, len: Int) = {}
}

class ConsolePrintStream (pane: ConsolePane, style: JStyle, os: OutputStream=NullOutputStream) extends PrintStream(os) {
  def write (s:String) = {
    if (os != NullOutputStream) os.write(s.getBytes())
    pane.append(s, style)
  }
  def writeLn = {
    if (os != NullOutputStream) os.write('\n')
    pane.appendLn
  }

  override def println(s:String)   = { write(s); writeLn }
  override def println(s:Object)   = { write(s.toString); writeLn }
  override def println(s:Boolean)  = { write(java.lang.Boolean.toString(s)); writeLn }
  override def println(s:Char)     = { write(java.lang.Character.toString(s)); writeLn }
  override def println(s:Int)      = { write(java.lang.Integer.toString(s)); writeLn }
  override def println(s:Long)     = { write(java.lang.Long.toString(s)); writeLn }
  override def println(s:Float)    = { write(java.lang.Float.toString(s)); writeLn }
  override def println(s:Double)   = { write(java.lang.Double.toString(s)); writeLn }

  override def print(s:String)     = write(s)
  override def print(s:Object)     = write(s.toString)
  override def print(s:Boolean)    = write(java.lang.Boolean.toString(s))
  override def print(s:Char)       = write(Character.toString(s))
  override def print(s:Int)        = write(Integer.toString(s))
  override def print(s:Long)       = write(java.lang.Long.toString(s))
  override def print(s:Float)      = write(java.lang.Float.toString(s))
  override def print(s:Double)     = write(java.lang.Double.toString(s))
}

class StdConsole extends ConsolePane {
  val errStyle = addStyle("err", defaultStyle)
  StyleConstants.setForeground(errStyle, Color.red)
  val outStyle = addStyle("out", defaultStyle)

  val out = new ConsolePrintStream(this, outStyle)
  val err = new ConsolePrintStream(this, errStyle)

  def appendErr (s: String) = { append(s,errStyle); appendLn }
  def appendOut (s: String) = { append(s,outStyle); appendLn }
}

//--- logging support

object LogConsole {
  val ERROR = "error"
  val WARNING = "warning"
  val INFO = "info"
  val DEBUG = "debug"
}
import gov.nasa.race.swing.LogConsole._

class LogHeader (t: String, lp: LogConsole, max: Int, actions: PartialFunction[String,Any]) extends ConsoleHeader(t,lp,max) {
  val logLevelSelector = new ComboBox[String](Array(ERROR,WARNING,INFO,DEBUG)).styled()
  listenTo( logLevelSelector)
  reactions += {
    case SelectionChanged(`logLevelSelector`) => actions(logLevelSelector.selection.item)
  }

  override def layoutChildren = {
    var c = new Constraints(gridy = 0, fill = Fill.Horizontal, anchor = Anchor.West, insets = (2,2,2,2))
    layout(titleLabel) = c
    layout(logLevelSelector) = c
    layout(new Label().styled()) = c.weightx(0.5) // a filler
    layout(new Label("cmt:").styled("labelFor")) = c.anchor(Anchor.East).weightx(0)
    layout(commentInput) = c
    layout(maxLinesCb) = c
    layout(maxLinesInput) = c
    layout(filePanel) = c
    this
  }

  def selectedLevel: String = logLevelSelector.selection.item
  def selectedLevel_= (level: String) = logLevelSelector.selection.item = level
}


class LogConsole extends ConsolePane with LogAppender {
  //--- these are the document styles we can configure with our Race Style
  val errorStyle = addStyle("error", defaultStyle)
  val warningStyle = addStyle("warning", defaultStyle)
  val infoStyle = addStyle("info", defaultStyle)
  val debugStyle = addStyle("debug", defaultStyle)

  override def appendError (s: String)   = { append(s,errorStyle); appendLn }
  override def appendWarning (s: String) = { append(s,warningStyle); appendLn }
  override def appendInfo (s: String)    = { append(s,infoStyle); appendLn }
  override def appendDebug (s: String)   = { append(s,debugStyle); appendLn }

  def createLogHeader (title: String, maxLines: Int=0)(actions: PartialFunction[String,Any]): LogHeader = {
    new LogHeader(title, this, maxLines, actions)
  }
}


/**** example that shows how to use the StdConsole

  * object StdConsoleApp extends  SimpleSwingApplication {
  * val top = new MainFrame {
  * title = "FSP Test"
  * val console = new StdConsole()
  * contents = new ScrollPane(console)
  * size = (300,300)

  * console.maxLines = 30
  * System.setOut(console.out)
  * System.setErr(console.err)
  * }
  * top.open()

  * for (i <- 1 until 40) {
  * invokeLater{ println(s"this is line $i") }
  * Thread.sleep(300)
  * }
  * invokeLater {
  * System.err.println("this is an error")
  * System.out.println("no, it is not")
  * }
  * }

***/
