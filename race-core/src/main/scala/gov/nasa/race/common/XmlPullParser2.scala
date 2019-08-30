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

  @inline final def isStartTag: Boolean = _isStartTag
  @inline final def isEndTag: Boolean = !_isStartTag
  @inline final def wasEmptyElementTag = _wasEmptyElementTag

  //--- element path stack (avoiding runtime allocation)
  protected val path = new HashedRangeStack(32)

  //--- content string list (avoiding runtime allocation)
  protected val contentStrings = new RangeStack(16)

  val attrName = new HashedSliceImpl(buf,0,0)
  val attrValue = new SliceImpl(buf,0,0)

  //--- state management

  // ?? do we need to separate tag start and end ??

  sealed trait State {
    def parseNextTag: Boolean       // if true result is in tag
    def parseNextAttribute: Boolean // if true result is in attrName,attrValue
    def parseContent: Boolean       // if true result can be obtained with content,contentAsLong,contentAsDouble
  }

  /**
    * position is on '<'
    */
  class TagState extends State {
    def parseNextTag = false
    def parseNextAttribute = false
    def parseContent = false
  }

  /**
    * position on ' ' after tag
    */
  class AttrState extends State {
    def parseNextTag = false
    def parseNextAttribute = false
    def parseContent = false
  }

  /**
    * position is on '>',  _wasEmptyElementTag is reset (processed)
    */
  class ContentState extends State {
    def parseNextTag = {
      contentStrings.clear
      if (idx < length) {
        idx = seekPastContent(idx)  // position is on '<'
        if (idx < length){
          false
        } else false
      } else false
    }
    def parseNextAttribute = false
    def parseContent = false
  }




  //--- auxiliary parse functions used by states

  def skipPastProlog(i0: Int): Int = {
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

  def seekTagStart (i0: Int): Int = {
    var i = i0
    val buf = this.buf
    while (i < length && buf(i) != '<') i += 1
    i
  }


  // to be used inside tag to skip to the ending '>' and set _wasEmptyElementTag accordingly
  // this has to skip over attribute value literals (..attr="..."..)
  // current position has to be outside attribute value string literal
  // note that XML comments are only allowed outside of tags
  def seekPastTag (i0: Int): Int = {
    var i = i0
    val buf = this.buf
    val length = this.length

    while (i < length) {
      val c = buf(i)
      if (c == '>') {
        _wasEmptyElementTag = false
        return i+1
      } else if (c == '/') {
        i += 1
        if (i < length && buf(i) == '>'){
          _wasEmptyElementTag = true
          return i + 1
        } else throw new XmlParseException(s"malformed tag ending around ${context(i0)}")
      } else if (c == '"'){
        i += 1
        while (i<length && buf(i) != '"') i += 1
      }
      i += 1
    }
    throw new XmlParseException(s"unterminated tag around ${context(i0)}")
  }

  // skip to end of text (beginning of next tag)
  // current position has to be outside of tag (i.e. past ending '>')
  // this has to skip over '<![CDATA[...]]>' and '<!-- ... -->' sections
  def seekPastContent (i0: Int): Int = {
    var iStart = i0
    var i = i0
    val buf = this.buf
    val length = this.length

    while (i < length) {
      val c = buf(i)
      if (c == '<') {
        val i1 = i+1
        if (i1 < length && (buf(i1) == '!')){
          val i2 = i1 + 1
          if (i2 < length) {
            if (buf(i2) == '[') {          // '<![CDATA[...]]>'
              if (i > iStart) contentStrings.push(iStart, i-iStart)
              i = i2 + 7
              while (i < length && buf(i) != '>' || buf(i-1) != ']') i += 1
              iStart = i-1
            } else if (buf(i2) == '-') {   // <!--...-->
              if (i > iStart) contentStrings.push(iStart, i-iStart)
              i = i2+3
              while (i < length && buf(i) != '>' || (buf(i-1) != '-' || buf(i-2) != '-')) i += 1
              iStart = i2 + 6
              contentStrings.push(iStart, i - iStart-2) // the CDATA[..] content
              iStart = i-1
            } else throw new XmlParseException(s"malformed comment or CDATA around ${context(i0)}")
          } else throw new XmlParseException(s"malformed comment or CDATA around ${context(i0)}")
        } else {
          if (i > iStart) contentStrings.push(iStart, i-iStart)
          return i
        }
      } else if (c == '>') {
        throw new XmlParseException(s"malformed element text around ${context(i0)}")
      }
      i += 1
    }
    throw new XmlParseException(s"unterminated text around ${context(i0)}")
  }


  def context (i: Int): String = {
    val i0 = i
    val i1 = Math.min(i0+20, buf.length)
    new String(buf, i0, i1-i0)
  }

  def printIndentOn (ps: PrintStream): Unit = {
    var level = if (_isStartTag) path.top else path.top+1  // when we get the end tag the top is already popped
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

  /*
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

   */
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
          length = bb.position
          done = true
        case CoderResult.OVERFLOW => growBuf
      }
    } while (!done)

    idx = 0
  }
}