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
abstract class XmlPullParser2  {

  protected var data: Array[Byte] // the (abstract) data (UTF-8), might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  protected final val tagState = new TagState
  protected final val attrState = new AttrState
  protected final val endTagState = new EndTagState
  protected final val contentState = new ContentState
  protected final val finishedState = new FinishedState

  protected var state: State  = tagState

  val tag = new SliceImpl(data,0,0)
  protected var _isStartTag: Boolean = false
  protected var _wasEmptyElementTag = false

  @inline final def isStartTag: Boolean = _isStartTag
  @inline final def isEndTag: Boolean = !_isStartTag
  @inline final def wasEmptyElementTag = _wasEmptyElementTag

  //--- element path stack (avoiding runtime allocation)
  protected val path = new RangeStack(32)

  //--- content string list (avoiding runtime allocation)
  protected val contentStrings = new RangeStack(16)
  protected var contentIdx = 0
  val contentString = new SliceImpl(data,0,0) // for iteration over content strings
  val rawContent = new SliceImpl(data,0,0) // slice over all content (without leading/trailing blanks)

  val attrName = new SliceImpl(data,0,0)
  val attrValue = new SliceImpl(data,0,0)  // not hashed by default

  //--- state management

  sealed abstract class State {
    def parseNextTag: Boolean       // if true result is in tag
    def parseNextAttr: Boolean      // if true result is in attrName,attrValue
    def parseContent: Boolean       // if true result can be obtained with content,contentAsLong,contentAsDouble
  }

  private def _setTag(i0: Int, i: Int, isStart: Boolean, nextState: State): Boolean = {
    idx = i
    tag.set(data,i0,i-i0)
    _isStartTag = isStart

    attrName.clear
    attrValue.clear
    rawContent.clear
    contentStrings.clear

    if (isStart) { // start tag
      path.push(tag.offset,tag.length)
      state = nextState

    } else { // end tag
      if (isTopTag(tag)) {
        path.pop
        state = if (path.isEmpty) finishedState else nextState
      } else {
        throw new XmlParseException(s"unbalanced end tag around ${context(i0)}")
      }
    }
    true
  }

  /**
    * position is on '<'
    * next state is either attrState (' '), endTagState ('/>') or contentState ('>')
    */
  class TagState extends State {
    def parseNextTag: Boolean = {
      val data = XmlPullParser2.this.data

      val i0 = idx+1
      var i = i0
      if (data(i) == '/') { // '</..> end tag
        i += 1
        val i0 = i
        i = skipTo(i, '>')
        _setTag(i0, i, false, contentState)

      } else {  // '<..' start tag
        while (true) {
          (data(i): @switch) match {
            case ' ' =>
              return _setTag(i0, i, true, attrState)
            case '/' =>
              val i1 = i + 1
              if (data(i1) == '>') {
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
        false
      }
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

      var i = skipSpace(idx)
      (data(i): @switch) match {
        case '/' =>
          val i1 = i+1
          if (data(i1) == '>') {
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
          var i0 = i
          i = skipTo(i,'=')
          val i1 = backtrackSpace(i-1)+1
          attrName.set(data,i0,i1-i0)
          i = skipTo(i+1,'"')

          i += 1
          i0 = i
          i = skipTo(i,'"')

          attrValue.set(data,i0,i-i0)
          idx = i+1
          return true
      }
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
      path.pop

      state = if (path.isEmpty) finishedState else contentState

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
      if (path.nonEmpty) {
        idx = seekPastContent(idx+1)
        state = tagState
        state.parseNextTag
      } else {
        false
      }
    }

    def parseNextAttr = false

    def parseContent: Boolean = {
      val i0 = idx+1
      idx = seekPastContent(i0) // this also sets the contentStrings
      rawContent.set(data,i0,idx-i0) // this includes surrounding whitespace, comments and CDATA sections
      state = tagState
      if (contentStrings.nonEmpty){
        contentIdx = 0
        true
      } else false
    }
  }

  /**
    * end state to ensure nothing else is parsed
    */
  class FinishedState extends State {
    def parseNextTag: Boolean = false
    def parseNextAttr: Boolean = false
    def parseContent: Boolean = false
  }


  // reset all vars
  protected def clear: Unit = {
    idx = 0
    state = tagState
    path.clear
    contentStrings.clear
    _isStartTag = false
    _wasEmptyElementTag = false
    tag.clear
    attrName.clear
    attrValue.clear
    rawContent.clear
  }

  //--- public methods

  @inline final def parseNextTag: Boolean = state.parseNextTag

  @inline final def parseNextAttr: Boolean = state.parseNextAttr

  def parseAttr (at: SliceImpl): Boolean = {
    while (parseNextAttr) {
      if (attrName =:= at) return true
    }
    false
  }

  @inline final def parseContent: Boolean = state.parseContent

  //--- content retrieval

  /**
    * iterate over all contentStrings
    */
  def getNextContentString: Boolean = {
    val i = contentIdx
    if (i <= contentStrings.top) {
      contentString.set(data,contentStrings.offset(i),contentStrings.length(i))
      contentIdx += 1
      true
    } else false
  }

  /**
    * coalesce all content strings
    */
  def getContent: String = {
    if (contentStrings.nonEmpty) {
      val len = contentLength
      val bs = new Array[Byte](len)
      var i = 0
      contentStrings.foreach { (_,o,l)=>
        System.arraycopy(data,o,bs,i,l)
        i += l
      }
      new String(bs,StandardCharsets.UTF_8 )
    } else ""
  }

  //--- path query (e.g. to disambiguate elements at different nesting levels)

  def tagHasParent(parent: SliceImpl): Boolean = {
    if (path.top > 0){
      val i = if (_isStartTag) path.top-1 else path.top
      parent.equals(data, path.offset(i), path.length(i))
    } else false
  }

  def tagHasAncestor(ancestor: SliceImpl): Boolean = {
    path.exists( (o,l) =>ancestor.equals(data,o,l))
  }

  //.. and probably more path predicates

  //--- auxiliary parse functions used by states

  def contentLength: Int = {
    var len = 0
    contentStrings.foreach { (_,_,l) => len += l }
    len
  }

  @inline final def isTopTag (t: SliceImpl): Boolean = {
    t.equals(data,path.topOffset,path.topLength)
  }

  def seekRootTag: Int = {
    var i = 0
    val buf = this.data

    while (buf(i) != '<') i += 1
    val i1 = i+1
    if (buf(i1) == '?' ){
      i = i1+1
      while (buf(i) != '>' && buf(i-1) != '?') i += 1
      i += 1
      while (buf(i) != '<') i += 1
    }
    i
  }

  @inline protected final def skipTo (i0: Int, c: Byte): Int = {
    val data = XmlPullParser2.this.data

    var i = i0
    while (data(i) != c) i += 1
    i
  }

  // skip forward to first position that is not a space or newline
  @inline protected final def skipSpace (i0: Int): Int = {
    val data = XmlPullParser2.this.data
    var c: Byte = 0

    var i = i0
    while ({c = data(i); c == ' ' || c == '\n'}) i += 1
    i
  }

  // skip backwards to first position that is not a space
  @inline protected final def backtrackSpace (i0: Int): Int = {
    val data = XmlPullParser2.this.data

    var i = i0
    while (data(i) == ' ') i -= 1
    i
  }

  @inline protected final def seekTagStart (i0: Int): Int = {
    var i = i0
    val buf = this.data

    while (buf(i) != '<') i += 1
    i
  }


  // to be used inside tag to skip to the ending '>' and set _wasEmptyElementTag accordingly
  // this has to skip over attribute value literals (..attr="..."..)
  // current position has to be outside attribute value string literal
  // note that XML comments are only allowed outside of tags
  def seekPastTag (i0: Int): Int = {
    var i = i0
    val buf = this.data

    while (true) {
      val c = buf(i)
      if (c == '>') {
        _wasEmptyElementTag = false
        return i
      } else if (c == '/') {
        i += 1
        if (buf(i) == '>'){
          _wasEmptyElementTag = true
          return i
        } else throw new XmlParseException(s"malformed tag ending around ${context(i0)}")
      } else if (c == '"'){
        i += 1
        while (buf(i) != '"') i += 1
      }
      i += 1
    }
    throw new XmlParseException(s"unterminated tag around ${context(i0)}")
  }

  // skip to end of text (beginning of next tag)
  // current position has to be outside of tag (i.e. past ending '>')
  // this has to skip over '<![CDATA[...]]>' and '<!-- ... -->' sections

  def seekPastContent (i0: Int): Int = {
    var iStart = skipSpace(i0)
    var i = i0
    val buf = this.data

    while (true) {
      val c = buf(i)
      if (c == '<') {
        val i1 = i+1
        if ((buf(i1) == '!')){
          val i2 = i1 + 1
          if (buf(i2) == '[') {          // '<![CDATA[...]]>'
            val iEnd = backtrackSpace(i-1)
            if (iEnd > iStart) contentStrings.push(iStart, iEnd-iStart+1)
            i = i2 + 7
            while (buf(i) != '>' || buf(i-1) != ']') i += 1
            iStart = i-1
          } else if (buf(i2) == '-') {   // <!--...-->
            val iEnd = backtrackSpace(i-1)
            if (iEnd > iStart) contentStrings.push(iStart, iEnd-iStart+1)
            i = i2+3
            while (buf(i) != '>' || (buf(i-1) != '-' || buf(i-2) != '-')) i += 1
            iStart = i2 + 6
            contentStrings.push(iStart, i - iStart-2) // the CDATA[..] content
            iStart = i-1
          } else throw new XmlParseException(s"malformed comment or CDATA around '${context(i0)}'")

        } else {
          val iEnd = backtrackSpace(i-1) + 1
          if (iEnd > iStart) contentStrings.push(iStart, iEnd-iStart)
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


  def printOn (ps: PrintStream): Unit = {
    def printIndent: Unit = {
      var level = if (_isStartTag) path.top else path.top+1  // when we get the end tag the top is already popped
      while (level > 0) {
        ps.print("  ")
        level -= 1
      }
    }

    var hadContent = false

    while (parseNextTag){
      if (isStartTag){
        printIndent
        ps.print('<')
        ps.print(tag)

        while (parseNextAttr) {
          ps.print(' ')
          ps.print(attrName)
          ps.print("=\"")
          ps.print(attrValue)
          ps.print('"')
        }

        if (!_wasEmptyElementTag) {
          ps.print('>')
          if (parseContent) {
            ps.print(rawContent)
            hadContent = true
          } else {
            ps.println
            hadContent = false
          }
        }

      } else {
        if (_wasEmptyElementTag) {
          ps.println("/>")
        } else {
          if (!hadContent) printIndent
          ps.print("</")
          ps.print(tag)
          ps.println('>')
        }
        hadContent = false // no mixed content yet
      }
    }
  }

  def dumpPath: Unit = {
    path.foreach { (idx,off,len)=>
      if (idx > 0)print(',')
      print(Slice(data,off,len).toString)
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

    clear
    idx = seekRootTag
    idx >= 0
  }
}

/**
  * a XmlPullParser2 that works directly on a provided utf-8 byte array
  */
class UTF8XmlPullParser2 extends XmlPullParser2 {
  protected var data: Array[Byte] = Array.empty

  def initialize (bs: Array[Byte]): Boolean = {
    clear
    data = bs
    limit = bs.length
    idx = seekRootTag
    idx >= 0
  }
}