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

import scala.annotation.switch

class XmlParseException (msg: String) extends RuntimeException(msg)


/**
  * a XML parser trait that is based on Slices, i.e. tries to avoid allocation of
  * temp objects
  */
trait XmlPullParser2  {

  protected var data: Array[Byte] // the (abstract) data (UTF-8), might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  protected final val tagState = new TagState
  protected final val attrState = new AttrState
  protected final val endTagState = new EndTagState
  protected final val contentState = new ContentState

  protected var state: State  = tagState

  val tag = new HashedSliceImpl(data,0,0)
  protected var _isStartTag: Boolean = false
  protected var _wasEmptyElementTag = false

  @inline final def isStartTag: Boolean = _isStartTag
  @inline final def isEndTag: Boolean = !_isStartTag
  @inline final def wasEmptyElementTag = _wasEmptyElementTag

  //--- element path stack (avoiding runtime allocation)
  protected val path = new HashedRangeStack(32)

  //--- content string list (avoiding runtime allocation)
  protected val contentStrings = new RangeStack(16)
  val rawContent = new SliceImpl(data,0,0)

  val attrName = new HashedSliceImpl(data,0,0)
  val attrValue = new SliceImpl(data,0,0)  // not hashed by default

  //--- state management

  sealed abstract class State {
    def parseNextTag: Boolean       // if true result is in tag
    def parseNextAttr: Boolean      // if true result is in attrName,attrValue
    def parseContent: Boolean       // if true result can be obtained with content,contentAsLong,contentAsDouble
  }

  /**
    * position is on '<'
    * next state is either attrState (' '), endTagState ('/>') or contentState ('>')
    */
  class TagState extends State {
    def parseNextTag: Boolean = {
      val data = XmlPullParser2.this.data
      val limit = XmlPullParser2.this.limit

      @inline def _setTag(i0: Int, i: Int, isStart: Boolean, nextState: State): Boolean = {
        idx = i
        tag.set(data,i0,i-i0)
        _isStartTag = isStart
        if (isStart) {
          path.push(tag.offset,tag.length,tag.hash)
          rawContent.clear
          contentStrings.clear
          attrName.clear
          attrValue.clear
        } else {
          if (path.isTop(tag.offset,tag.length,tag.hash)) path.pop
          else throw new XmlParseException(s"unbalanced end tag around ${context(i0)}")
        }
        state = nextState
        true
      }

      val i0 = idx+1
      var i = i0
      if (i < limit){
        if (data(i) == '/') { // '</..> end tag
          i += 1
          val i0 = i
          i = skipTo(i, '>')
          if (i < limit) {
            return _setTag(i, i0, false, contentState)
          }
          throw new XmlParseException(s"malformed end tag around ${context(i0)}")

        } else {  // '<..' start tag
          while (i < limit) {
            (data(i): @switch) match {
              case ' ' =>
                return _setTag(i0, i, true, attrState)
              case '/' =>
                val i1 = i + 1
                if (i1 < limit && data(i1) == '>') {
                  _wasEmptyElementTag = true
                  return _setTag(i0, i1, true, endTagState)
                }
                throw new XmlParseException(s"malformed empty element tag around ${context(i0)}")
              case '>' =>
                _wasEmptyElementTag = false
                return _setTag(i0, i, true, contentState)
              case _ =>
                i += 1
            }
          }
        }
      }
      idx = i
      false // past limit
    }
    def parseNextAttr = false
    def parseContent = false
  }

  /**
    * position on ' ' after tag
    * next state is either attrState (in case attr is parsed), endtagState ('/>') or contentState ('>')
    */
  class AttrState extends State {
    def parseNextTag = {
      idx = seekPastTag(idx)
      state = if (_wasEmptyElementTag) endTagState else contentState
      state.parseNextTag
    }

    // current position on first space before attribute name
    def parseNextAttr: Boolean = {
      val data = XmlPullParser2.this.data
      val limit = XmlPullParser2.this.limit

      var i = skipSpace(idx)
      if (i < limit){
        (data(i): @switch) match {
          case '/' =>
            val i1 = i+1
            if (i1 < limit && data(i1) == '>') {
              idx = i1
              _wasEmptyElementTag = true
              state = endTagState
              return false
            }
            throw new XmlParseException(s"malformed tag end around ${context(idx)}")
          case '>' =>
            idx = i
            _wasEmptyElementTag = false
            state = contentState
            return false
          case _ =>
            val i0 = i
            i = skipTo(i,'=')
            if (i < limit) {
              val i1 = backtrackSpace(i-1)+1
              attrName.set(data,i0,i1-i0)
              i = skipTo(i+1,'"')
              if (i < limit){
                i += 1
                val i0 = i
                i = skipTo(i,'"')
                if (i < limit){
                  attrValue.set(data,i0,i)
                  idx = i+1
                  return true
                }
              }
            }
            throw new XmlParseException(s"malformed attribute around ${context(idx)}")
        }
      }
      idx = i
      false // past limit
    }
    def parseContent = false
  }

  /**
    * position is on '>' of a '.../>' tag
    * next is always contentState
    */
  class EndTagState extends State {
    def parseNextTag: Boolean = {
      // tag is still valid
      _isStartTag = false
      _wasEmptyElementTag = true
      state = contentState
      path.pop
      true  // this always returns true since we already got the end tag
    }
    def parseNextAttr = false
    def parseContent = false
  }

  /**
    * position is on '>',  _wasEmptyElementTag is reset (processed)
    */
  class ContentState extends State {
    def parseNextTag = {
      idx = seekPastContent(idx)
      state = tagState
      state.parseNextTag
    }

    def parseNextAttr = false

    def parseContent: Boolean = {
      val i0 = skipSpace(idx+1)
      idx = seekPastContent(i0)
      val i1 = backtrackSpace(idx-1)
      rawContent.set(data,i0,i1-i0+1)
      state = tagState
      contentStrings.nonEmpty
    }
  }

  //--- public methods

  def parseNextTag: Boolean = state.parseNextTag

  def parseNextAttr: Boolean = state.parseNextAttr

  def parseContent: Boolean = state.parseContent



  //--- auxiliary parse functions used by states

  def seekRootTag: Int = {
    var i = 0
    val buf = this.data
    val limit = this.limit

    while (i < limit && buf(i) != '<') i += 1
    val i1 = i+1
    if (i1 < limit && buf(i1) == '?' ){
      i = i1+1
      while (i<limit && buf(i) != '>' && buf(i-1) != '?') i += 1
      i += 1
      while (i<limit && buf(i) != '<') i += 1
    }
    i
  }

  @inline protected final def skipTo (i0: Int, c: Byte): Int = {
    val data = XmlPullParser2.this.data
    val limit = XmlPullParser2.this.limit

    var i = i0
    while (i < limit && data(i) != c) i += 1
    i
  }

  // skip forward to first position that is not a space or newline
  @inline protected final def skipSpace (i0: Int): Int = {
    val data = XmlPullParser2.this.data
    val limit = XmlPullParser2.this.limit
    var c: Byte = 0

    var i = i0
    while (i < limit && {c = data(i); c == ' ' || c == '\n'}) i += 1
    i
  }

  // skip backwards to first position that is not a space
  @inline protected final def backtrackSpace (i0: Int): Int = {
    val data = XmlPullParser2.this.data

    var i = i0
    while (i >= 0 && data(i) == ' ') i -= 1
    i
  }



  def seekTagStart (i0: Int): Int = {
    var i = i0
    val buf = this.data
    val limit = this.limit

    while (i < limit && buf(i) != '<') i += 1
    i
  }


  // to be used inside tag to skip to the ending '>' and set _wasEmptyElementTag accordingly
  // this has to skip over attribute value literals (..attr="..."..)
  // current position has to be outside attribute value string literal
  // note that XML comments are only allowed outside of tags
  def seekPastTag (i0: Int): Int = {
    var i = i0
    val buf = this.data
    val limit = this.limit

    while (i < limit) {
      val c = buf(i)
      if (c == '>') {
        _wasEmptyElementTag = false
        return i
      } else if (c == '/') {
        i += 1
        if (i < limit && buf(i) == '>'){
          _wasEmptyElementTag = true
          return i
        } else throw new XmlParseException(s"malformed tag ending around ${context(i0)}")
      } else if (c == '"'){
        i += 1
        while (i<limit && buf(i) != '"') i += 1
      }
      i += 1
    }
    throw new XmlParseException(s"unterminated tag around ${context(i0)}")
  }

  // skip to end of text (beginning of next tag)
  // current position has to be outside of tag (i.e. past ending '>')
  // this has to skip over '<![CDATA[...]]>' and '<!-- ... -->' sections

  // ?? raw content ??

  def seekPastContent (i0: Int): Int = {
    var iStart = i0
    var i = i0
    val buf = this.data
    val limit = this.limit

    while (i < limit) {
      val c = buf(i)
      if (c == '<') {
        val i1 = i+1
        if (i1 < limit && (buf(i1) == '!')){
          val i2 = i1 + 1
          if (i2 < limit) {
            if (buf(i2) == '[') {          // '<![CDATA[...]]>'
              if (i > iStart) contentStrings.push(iStart, i-iStart)
              i = i2 + 7
              while (i < limit && buf(i) != '>' || buf(i-1) != ']') i += 1
              iStart = i-1
            } else if (buf(i2) == '-') {   // <!--...-->
              if (i > iStart) contentStrings.push(iStart, i-iStart)
              i = i2+3
              while (i < limit && buf(i) != '>' || (buf(i-1) != '-' || buf(i-2) != '-')) i += 1
              iStart = i2 + 6
              contentStrings.push(iStart, i - iStart-2) // the CDATA[..] content
              iStart = i-1
            } else throw new XmlParseException(s"malformed comment or CDATA around '${context(i0)}'")
          } else throw new XmlParseException(s"malformed comment or CDATA around '${context(i0)}'")
        } else {
          if (i > iStart) contentStrings.push(iStart, i-iStart)
          return i
        }
      } else if (c == '>') {
        throw new XmlParseException(s"malformed element content around '${context(i0)}'")
      }
      i += 1
    }
    throw new XmlParseException(s"unterminated content around ${context(i0)}")
  }

  //--- aux and debug functions

  def context (i: Int): String = {
    val i0 = i
    val i1 = Math.min(i0+20, data.length)
    new String(data, i0, i1-i0)
  }

  def printIndentOn (ps: PrintStream): Unit = {
    var level = if (_isStartTag) path.top else path.top+1  // when we get the end tag the top is already popped
    while (level > 0) {
      ps.print("  ")
      level -= 1
    }
  }

  def printOn (ps: PrintStream): Unit = {
    while (parseNextTag){
      if (isStartTag){
        printIndentOn(ps)
        ps.print('<')
        ps.print(tag)

        while (parseNextAttr) {
          ps.print(' ')
          ps.print(attrName)
          ps.print("=\"")
          //ps.print(attrValue)
          ps.print('"')
        }

        if (!_wasEmptyElementTag) {
          ps.print('>')
          if (parseContent) {
            ps.print(rawContent)
          } else {
            ps.println
          }
        }

      } else {
        if (_wasEmptyElementTag) {
          ps.println("/>")
        } else {
          printIndentOn(ps)
          ps.print("</")
          ps.print(tag)
          ps.println('>')
        }
      }
    }
  }

}


/**
  * a XmlPullParser2 that uses a pre-allocated (but growable) byte array to parse UTF-8 string data
  */
class StringXmlPullParser2(initBufSize: Int = 8192) extends XmlPullParser2 {
  protected var data: Array[Byte] = new Array(initBufSize)
  protected var bb: ByteBuffer = ByteBuffer.wrap(data)
  protected val enc = StandardCharsets.UTF_8.newEncoder

  protected def growBuf: Unit = {
    val newBuf = new Array[Byte](data.length*2)
    val newBB = ByteBuffer.wrap(newBuf)
    newBB.put(bb)
    data = newBuf
    bb = newBB
  }

  def initialize (s: String): Boolean = {
    val cb = CharBuffer.wrap(s)
    bb.rewind
    var done = false

    do {
      enc.encode(cb, bb, true) match {
        case CoderResult.UNDERFLOW => // all chars encoded
          limit = bb.position
          done = true
        case CoderResult.OVERFLOW => growBuf
      }
    } while (!done)

    idx = seekRootTag
    idx >= 0
  }
}