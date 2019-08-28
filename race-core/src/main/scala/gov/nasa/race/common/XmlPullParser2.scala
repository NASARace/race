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
  protected var length: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  val tag = new HashedSliceImpl(buf,0,0)
  protected var _isStartTag: Boolean = false
  protected var _wasEmptyElementTag = false


  //--- allocation free element path stack
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

  def skipPastProlog(i0: Int) = {
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

  // idx after return on first tag char (following '<" or '</')
  def skipToTag (i0: Int): Boolean = {
    var i = i0

    while (i < length && buf(i) != '<') i += 1
    if (i >= length){
      idx = i
      return false
    }

    i += 1
    if (i < length) {
      val c = buf(i)
      if (c == '!') {
        if ((i+2 < length) && (buf(i+1) == '-' && buf(i+2) == '-')) { // comment
          i = skipPastComment(i + 3)
          skipToTag(i)
        } else {
          throw new XmlParseException(s"malformed comment around ${context(i)}")
        }
      } else if (c == '?'){  // prolog (should be only before the top element)
        i = skipPastProlog(i)
        skipToTag(i)
      } else { // start or end tag
        idx = i
        true
      }
    } else {
      throw new XmlParseException("truncated element at end")
    }
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

  /*
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
   */

  //--- element parsing

  def parseNextTag: Boolean = {
    if (_wasEmptyElementTag) { // no need to parse anything, we already have the tag
      _wasEmptyElementTag = false
      popPathIfEqual
      true

    } else {
      if (skipToTag(idx)) {
        var i = idx
        var c = buf(i)
        if (c == '/') {
          _isStartTag = false
          i += 1
          if (i >= length) throw new XmlParseException(s"malformed end tag at ${context(idx)}")
          c = buf(i)
        } else {
          _isStartTag = true
        }
        val i0 = i

        while (c != ' ' && c != '/' && c != '>') {
          i += 1
          if (i >= length) throw new XmlParseException(s"malformed tag at ${context(i0)}")
          c = buf(i)
        }
        tag.set(buf, i0, i - i0)
        if (_isStartTag) pushPath else popPathIfEqual

        if (c == '/') { // empty element tag
          i += 1
          _wasEmptyElementTag = true
        }
        true
      } else {  // no more tags found
        false
      }
    }
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

  // todo - has to handle "<![CDATA[...]]>"
  def parseText: Boolean = {
    value.clear
    if (skipToText) {
      var i = idx
      val buf = this.buf
      val i0 = i
      while (buf(i) != '<') i += 1
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
    pathTop = -1
    _isStartElement = false
    lastWasStartElement = false
  }
}