/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import java.io.PrintStream
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CoderResult, StandardCharsets}

class XmlParseException (msg: String) extends RuntimeException(msg)

/**
  * a XML parser trait that is based on Slices, i.e. tries to avoid allocation of
  * temp objects
  */
trait XmlPullParser2  {

  protected var buf: Array[Byte] // the data (UTF-8), might grow
  protected var idx = 0 // points to the next unprocessed byte in data

  protected var textEnd = 0 // set if getText returned something

  // we need to store the path elements separately to avoid allocation
  protected var pathCapacity: Int = 32
  protected var pathOffset = new Array[Int](pathCapacity)
  protected var pathLength = new Array[Int](pathCapacity)
  protected var pathHash = new Array[Long](pathCapacity)
  protected var pathTop: Int = -1

  // hashed so that clients/subclasses can efficiently filter
  protected val elementName = new HashedSliceImpl(buf,0,0)  // the current element name (without '<' '/' and '>')
  protected val attrName = new HashedSliceImpl(buf,0,0) // the current attr name

  protected val value = new SliceImpl(buf,0,0) // current attr value or element text

  protected var _isStartElement = false
  protected var lastWasStartElement = false

  //--- public accessors (those should not be modifiable)

  def isStartElement = _isStartElement

  def element: Slice = elementName
  def elementText: Slice = value

  def attr: Slice = attrName
  def attrValue: Slice = value

  //--- path management

  def growPath: Unit = {
    val newCapacity = pathCapacity * 2
    val newOffsets = new Array[Int](newCapacity)
    val newLengths = new Array[Int](newCapacity)
    val newHashes = new Array[Long](newCapacity)

    val len = pathTop+1
    System.arraycopy(pathOffset,0,newOffsets,0,len)
    System.arraycopy(pathLength,0,newLengths,0,len)
    System.arraycopy(pathHash,0,newHashes,0,len)

    pathCapacity = newCapacity
    pathOffset = newOffsets
    pathLength = newLengths
    pathHash = newHashes
  }

  def pushPath: Unit = {
    pathTop += 1
    if (pathTop >= pathCapacity) growPath

    pathOffset(pathTop) = elementName.offset
    pathLength(pathTop) = elementName.length
    pathHash(pathTop) = elementName.getHash
  }

  def popPath: Unit = {
    if (pathTop >= 0) pathTop -= 1
  }

  def popPathIfEqual: Boolean = {
    if (pathTop >= 0){
      if (elementName.equals(buf,pathOffset(pathTop),pathLength(pathTop),pathHash(pathTop))) {
        pathTop -= 1
        return true
      }
    }
    false
  }

  def dumpPath: Unit = {
    var i = 0
    while (i < pathTop){
      i += 1
    }
  }

  def pathDepth: Int = pathTop+1

  //... and probably more path accessors

  //--- auxiliary parse functions

  def skipPastEndDirective(i0: Int) = {
    var i = i0
    val buf = this.buf
    do {
      var c = 0
      while ({c = buf(i); c != '?'}){
        i += 1
        if (c == '"'){
          while( buf(i) != '"') i += 1
        }
      }
    } while ({ i += 1; buf(i) != '>'})
    i + 1
  }

  def skipPastComment(i0: Int) = {
    var i = i0
    val buf = this.buf

    do {
      while (buf(i) != '-') i += 1
    } while ({ i += 1; buf(i) != '-'} || { i += 1; buf(i) != '>'} )
    i + 1
  }

  def skipPastCDATA(i0: Int) = {
    var i = i0
    val buf = this.buf

    do {
      while (buf(i) != ']') i += 1
    } while ({ i+= 1; buf(i) != ']'} || { i += 1; buf(i) != '>'})
    i + 1
  }

  def skipToText: Boolean = {
    if (_isStartElement){
      var i = idx
      val buf = this.buf

      while (buf(i) != '>') i += 1

      if (buf(i-1) == '/') { // no text for <../> elements
        //lastWasStartElement = _isStartElement
        //_isStartElement = false
        //idx = i+1
        false

      } else {
        lastWasStartElement = true
        i += 1
        idx = i
        textEnd = i
        true
      }
    } else { // no text for </..> end elements
      false
    }
  }

  private def matchPattern (i0: Int, pattern: Array[Byte]): Boolean = {
    var i = i0
    val buf = this.buf
    var j = 0
    while (j < pattern.length) {
      if (buf(i) != pattern(j)) return false
      i += 1
      j += 1
    }
    true
  }

  final val CD_START = "[CDATA[".getBytes

  def context (i: Int): String = {
    val i0 = i
    val i1 = Math.min(i0+20, buf.length)
    new String(buf, i0, i1-i0)
  }

  def printIndentOn (ps: PrintStream): Unit = {
    var level = if (_isStartElement) pathTop else pathTop+1
    while (level > 0) {
      ps.print("  ")
      level -= 1
    }
  }

  def printOn (ps: PrintStream): Unit = {
    val lastText = new SliceImpl(buf,0,0)
    while (parseNextElement){
      if (_isStartElement) {
        lastText.clear
        if (lastWasStartElement) ps.println('>')
        printIndentOn(ps)
        ps.print('<')
        elementName.writeTo(ps)

        while (parseNextAttribute){
          ps.print(' ')
          attrName.writeTo(ps)
          ps.print("=\"")
          ps.print(value)
          ps.print('"')
        }

        if (parseTrimmedText) {
          lastText.setFrom(value)
        }

      } else {  // end element
        if (lastWasStartElement){
          if (lastText.nonEmpty) {
            ps.print('>')
            lastText.writeTo(ps)
            ps.print("</")
            elementName.writeTo(ps)
            ps.println('>')
          } else {
            ps.println("/>")
          }
        } else {
          printIndentOn(ps)
          ps.print("</")
          elementName.writeTo(ps)
          ps.println('>')
        }
      }
    }
  }

  //--- element parsing

  def parseNextElement: Boolean = {
    try {
      _parseNext(idx)
    } catch {
      case x: ArrayIndexOutOfBoundsException => false
    }
  }

  protected def _parseNext (i0: Int): Boolean = {
    var i = i0
    val buf = this.buf

    elementName.clear
    attrName.clear
    value.clear

    if (_isStartElement) {
      if (textEnd < 0){
        while (buf(i) != '>') i += 1
        if (buf(i-1) == '/'){
          lastWasStartElement = _isStartElement
          _isStartElement = false
          idx = i+1
          popPath
          return true
        }
      } else {
        i = textEnd
        textEnd = -1
      }
    }

    while (true) {
      while (buf(i) != '<') {
        i += 1
        if (buf(i) == 0) return false // end marker reached
      }
      i +=1
      val i0 = i
      buf(i) match {
        case '?' =>  // metadata (prolog) -> skip
          i = skipPastEndDirective(i+1)
        case '!' =>  // comment or CDATA
          if (buf(i+1) == '-' && buf(i+2) == '-') {
            i = skipPastComment(i+3)
          } else {  // maybe CDATA ("<![CDATA[..]]>") - ignored for now
            if (matchPattern(i+1, CD_START)) {
              i = skipPastCDATA(i+CD_START.length)
            } else {
              throw new XmlParseException(s"comment or CDATA expected around ..${context(i0)}..")
            }
          }
        case '/' => // end element
          i += 1
          while (buf(i) != '>') i += 1
          elementName.setAndRehash(buf,i0+1, i-i0-1)
          idx = i+1
          if (!popPathIfEqual) throw new XmlParseException(s"unbalanced end element around ..${context(i0-1)}..")
          lastWasStartElement = _isStartElement
          _isStartElement = false
          return true

        case _ => // start element
          var c = buf(i)
          while (c > ' ' && c != '/' && c != '>'){
            i += 1
            c = buf(i)
          }
          elementName.setAndRehash(buf,i0,i-i0)
          idx = i
          pushPath
          lastWasStartElement = _isStartElement
          _isStartElement = true
          return true
      }
    }
    throw new RuntimeException("can't get here")
  }

  def parseNextAttribute: Boolean = {
    attrName.clear
    value.clear

    if (_isStartElement){
      var i = idx
      val buf = this.buf
      var c = 0

      while ({ c = buf(i); c <= ' '}) i += 1
      if (c == '/' || c == '>') { // no attr
        idx = i
        false

      } else {  // attr
        var i0 = i
        i += 1
        while (buf(i) != '=') i += 1
        attrName.setAndRehash(buf,i0, i-i0)
        i += 1
        if (buf(i) != '"') throw new XmlParseException("non-quoted attribute value around ..${context(i0)}.. ")
        i += 1
        i0 = i
        while (buf(i) != '"') i += 1
        value.set(buf,i0,i - i0)
        idx = i + 1
        true
      }
    } else {
      false  // no attributes for end elements
    }
  }

  def skipAttributes: Unit = skipToText

  def parseTrimmedText: Boolean = {
    value.clear
    if (skipToText) {
      var i = idx
      val buf = this.buf

      while (buf(i) <= ' ') i += 1
      val i0 = i
      while (buf(i) != '<') i += 1
      textEnd = i

      // backtrack over trailing spaces
      i -= 1
      while (buf(i) <= ' ') i -= 1
      if (i >= i0){
        value.set(buf,i0,i-i0+1)
        true
      } else {  // no text, just spaces
        false
      }
    } else {
      false
    }
  }

  def parseText: Boolean = {
    value.clear
    if (skipToText) {
      var i = idx
      val buf = this.buf
      val i0 = i
      while (buf(i) != '<') i += 1
      textEnd = i
      i -= 1
      if (i >= i0){
        value.set(buf,i0,i-i0+1)
        true
      } else {  // no text, just spaces
        false
      }

    } else {
      false
    }
  }
}


/**
  * a XmlPullParser2 that uses a pre-allocated (but growable) buffer
  * 
  * Use this for String parsing to avoid per-parse allocation of the complete
  * string data
  */
class BufferedXmlPullParser2 (initBufSize: Int = 8192) extends XmlPullParser2 {
  protected var buf: Array[Byte] = new Array(initBufSize)
  protected var bb: ByteBuffer = ByteBuffer.wrap(buf)
  protected val enc = StandardCharsets.UTF_8.newEncoder

  protected def growBuf: Unit = {
    val newBuf = new Array[Byte](buf.length*2)
    val newBB = ByteBuffer.wrap(newBuf)
    newBB.put(bb)
    buf = newBuf
    bb = newBB
  }

  def initialize (s: String): Unit = {
    val cb = CharBuffer.wrap(s)
    bb.rewind
    var done = false

    do {
      enc.encode(cb, bb, true) match {
        case CoderResult.UNDERFLOW => // all chars encoded
          buf(bb.position) = 0 // end marker
          done = true
        case CoderResult.OVERFLOW => growBuf
      }
    } while (!done)

    idx = 0
    textEnd = -1
    pathTop = -1
    _isStartElement = false
    lastWasStartElement = false
  }
}