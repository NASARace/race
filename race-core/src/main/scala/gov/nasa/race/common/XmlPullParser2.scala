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

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import gov.nasa.race.uom.DateTime

import scala.annotation.switch

class XmlParseException (msg: String) extends RuntimeException(msg)



/**
  * a XML parser trait that is based on Slices, i.e. tries to avoid allocation of
  * temp objects
  */
abstract class XmlPullParser2  {

  type SliceParseFunc = (Array[Byte],Int,Int)=>Unit  // args are (data, offset, length)

  protected var data: Array[Byte] = Array.empty[Byte]  // the (abstract) data (UTF-8), might grow
  protected var limit: Int = 0
  protected var idx = 0 // points to the next unprocessed byte in data

  final val CDATA = ConstAsciiSlice("<![CDATA[")

  protected final val tagState = new TagState
  protected final val attrState = new AttrState
  protected final val endTagState = new EndTagState
  protected final val contentState = new ContentState
  protected final val finishedState = new FinishedState

  protected var state: State  = tagState

  val tag = MutUtf8Slice.empty
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
  val contentString = MutUtf8Slice.empty // for iteration over content strings
  val rawContent = MutUtf8Slice.empty // slice over all content (without leading/trailing blanks)

  val attrName = MutUtf8Slice.empty
  val attrValue = MutUtf8Slice.empty  // not hashed by default

  protected def setData (newData: Array[Byte], newIdx: Int, newLength: Int): Unit = {
    data = newData
    limit = newIdx + newLength
    idx = newIdx

    // those won't change during the parse so set them here once
    tag.data = newData
    attrName.data = newData
    attrValue.data = newData
    rawContent.data = newData
    contentString.data = newData
  }

  protected def setData (newData: Array[Byte]): Unit = setData(newData,0, newData.length)

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

      @inline def _setTag(i0: Int, i: Int, isStart: Boolean, nextState: State): Boolean = {
        idx = i
        val len = i - i0
        if (len == 0) throw new XmlParseException(s"empty tag around ${context(i0)}")

        tag.setRange(i0,len)
        _isStartTag = isStart

        contentString.len = 0
        rawContent.len = 0
        contentStrings.top = -1
        contentIdx = 0

        if (isStart) { // start tag
          path.push(i0,len)
          state = nextState

        } else { // end tag
          if (isTopTag(tag)) {
            path.pop()
            state = if (path.isEmpty) finishedState else nextState
          } else {
            throw new XmlParseException(s"unbalanced end tag '$tag'")
          }
        }
        true
      }

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
          val b = data(i)
          if (b == ' ') {
            return _setTag(i0, i, true, attrState)
          } else if (b == '/') {
            val i1 = i + 1
            if (data(i1) == '>') {
              _wasEmptyElementTag = true
              return _setTag(i0, i1, true, endTagState)
            }
            throw new XmlParseException(s"malformed empty element tag around ${context(i0)}")
          } else if (b == '>') {
            _wasEmptyElementTag = false
            return _setTag(i0, i, true, contentState)
          }
          i += 1
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
          attrName.setRange(i0,i1-i0)
          i = skipTo(i+1,'"')

          i += 1
          i0 = i
          i = skipTo(i,'"')

          attrValue.setRange(i0,i-i0)
          idx = i+1
          return true
      }
    }
    def parseContent = {
      idx = seekPastTag(idx)
      state = contentState
      state.parseContent
    }
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
      path.pop()

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
        idx = skipContent(idx+1)
        state = tagState
        state.parseNextTag
      } else {
        false
      }
    }

    def parseNextAttr = false

    def parseContent: Boolean = {
      val i0 = idx+1
      idx = readContent(i0)
      rawContent.setRange(i0,idx-i0) // this includes surrounding whitespace, comments and CDATA sections
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
    path.clear()
    contentStrings.clear()
    _isStartTag = false
    _wasEmptyElementTag = false
    tag.clear()
    attrName.clear()
    attrValue.clear()
    rawContent.clear()
  }

  //--- public methods

  @inline final def parseNextTag: Boolean = state.parseNextTag

  @inline final def parseNextAttr: Boolean = state.parseNextAttr

  @inline final def parseAttr (at: ByteSlice): Boolean = {
    while (parseNextAttr) {
      if (attrName == at) return true
    }
    false
  }

  @inline final def parseContent: Boolean = state.parseContent

  @inline final def parseSingleContentString = state.parseContent && getNextContentString

  /**
    * call from start tag match to parse all child-elements with provided function
    * note the provided method is called for both start and end tags
    */
  def parseElement (f: SliceParseFunc): Unit = {
    val endLevel = depth-1
    while (parseNextTag && depth > endLevel) {
      f(data, tag.off, tag.len)
    }
  }

  def parseElement (fStart: SliceParseFunc, fEnd: SliceParseFunc): Unit = {
    val endLevel = depth-1
    while (parseNextTag && depth > endLevel) {
      if (isStartTag) fStart(data, tag.off, tag.len)
      else fEnd(data, tag.off, tag.len)
    }
  }

  def parseElementStartTags (fStart: SliceParseFunc): Unit = {
    val endLevel = depth-1
    while (parseNextTag && depth > endLevel) {
      if (isStartTag) fStart(data, tag.off, tag.len)
    }
  }

  def parseAttrs (f: SliceParseFunc): Unit = {
    while (parseNextAttr) {
      f(data, attrName.off, attrName.len)
    }
  }

  @inline final def stopParsing: Unit = {
    state = finishedState
  }

  @inline final def depth: Int = path.size

  //--- high level retrieval for mandatory content (no need to check - throw exception if not there)

  @inline final def readSliceContent: Utf8Slice = {
    state.parseContent
    getNextContentString
    contentString
  }

  @inline final def readBooleanContent: Boolean = {
    state.parseContent
    getNextContentString
    contentString.toBoolean
  }

  @inline final def readIntContent: Int = {
    state.parseContent
    getNextContentString
    contentString.toInt
  }

  @inline final def readDoubleContent: Double = {
    state.parseContent
    getNextContentString
    contentString.toDouble
  }

  @inline final def readStringContent: String = {
    state.parseContent
    getNextContentString
    contentString.toString
  }

  @inline final def readInternedStringContent: String = {
    state.parseContent
    getNextContentString
    contentString.intern
  }

  @inline final def readDateTimeContent: DateTime = {
    state.parseContent
    getNextContentString
    DateTime.parseYMDTSlice(contentString)
  }

  def readCdataContent: String = {
    state.parseContent
    if (rawContent.startsWith(CDATA)){
      rawContent.subString(9, rawContent.len - 12 )
    } else {
      throw new XmlParseException("no CDATA content")
    }
  }

  //--- raw content retrieval

  /**
    * iterate over all contentStrings
    */
  @inline final def getNextContentString: Boolean = {
    val i = contentIdx
    if (i <= contentStrings.top) {
      contentString.setRange(contentStrings.offsets(i),contentStrings.lengths(i))
      contentIdx += 1
      true
    } else false
  }

  /**
    * coalesce all content strings
    */
  def getContent: String = {
    val contentStrings = this.contentStrings

    if (contentStrings.top < 0) {
      ""
    } else if (contentStrings.top == 0) { // only one range
      val off = contentStrings.offsets(0)
      val len = contentStrings.lengths(0)
      new String(data,off,len)

    } else { // multiple ranges
      @inline def contentLength: Int = {
        var length = 0
        var j = 0
        while (j < contentStrings.top) {
          length += contentStrings.lengths(j)
          j += 1
        }
        length
      }

      val len = contentLength
      val bs = new Array[Byte](len)
      var i = 0
      var j = 0

      while (j < contentStrings.top) {
        val l = contentStrings.lengths(j)
        System.arraycopy(data, contentStrings.offsets(j), bs, i, l)
        i += l
        j += 1
      }

      new String(bs, StandardCharsets.UTF_8)
    }
  }

  //--- path query (e.g. to disambiguate elements at different nesting levels)

  def tagHasParent(parent: ByteSlice): Boolean = {
    val i = if (_isStartTag) path.top-1 else path.top
    (i>=0) && parent.equalsData(data, path.offsets(i), path.lengths(i))
  }

  def tagHasAncestor(ancestor: ByteSlice): Boolean = {
    var i = if (_isStartTag) path.top-1 else path.top
    while (i >= 0) {
      if (ancestor.equalsData(data,path.offsets(i),path.lengths(i))) return true
      i -= 1
    }
    false
  }

  // make sure this executes in const space (hence a pre-alloc path element slice)
  private val _pms = MutUtf8Slice.empty
  def pathMatches (matcher: SlicePathMatcher): Boolean = matcher.matches(data,_pms,path)

  //.. and probably more path predicates

  //--- auxiliary parse functions used by states


  @inline final def isTopTag (t: ByteSlice): Boolean = {
    (path.top >= 0) && t.equalsData(data,path.topOffset,path.topLength)
  }

  def seekRootTag: Int = {
    var i = idx
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
    val data = this.data
    var c: Byte = 0

    var i = i0
    while ({c = data(i); c == ' ' || c == '\n'}) i += 1
    i
  }

  // skip backwards to first position that is not a space
  @inline protected final def backtrackSpace (i0: Int): Int = {
    val data = this.data

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

  def readContent (i0: Int): Int = {
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

  def skipContent (i0: Int): Int = {
    var i = i0
    val buf = this.data

    while (true) {
      val c = buf(i)
      if (c == '<') {
        val i1 = i+1
        if ((buf(i1) == '!')){
          val i2 = i1 + 1
          if (buf(i2) == '[') {          // '<![CDATA[...]]>'
            i = i2 + 7
            while (buf(i) != '>' || buf(i-1) != ']') i += 1
          } else if (buf(i2) == '-') {   // <!--...-->
            i = i2+3
            while (buf(i) != '>' || (buf(i-1) != '-' || buf(i-2) != '-')) i += 1
          } else throw new XmlParseException(s"malformed comment or CDATA around '${context(i0)}'")

        } else {
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

  // this is redundant to Slice.hashCode but here so that we can inline
  @inline protected final def hash(i0: Int, len: Int): Int = {
    val data = this.data
    var i = i0 + 1
    val i1 = i0 + len
    var h = data(i0) & 0xff
    while (i < i1) {
      h = (h * 31) + (data(i) & 0xff)
      i += 1
    }
    h
  }

  def context (i: Int): String = {
    val i0 = i
    val i1 = Math.min(i0+20, data.length)
    new String(data, i0, i1-i0)
  }

  def format: String = {
    val baos = new ByteArrayOutputStream
    val ps = new PrintStream(baos)
    printOn(ps)
    ps.flush()
    baos.toString
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
    var i = 0
    print("path=[")
    while (i <= path.top) {
      if (i > 0) print(',')
      print(new String(data,path.offsets(i),path.lengths(i)))
    }
    println("]")
  }
}

/**
  * a XmlPullParser2 that works on full unicode strings
  *
  * Note this does allocate a byte[] array per string, i.e. does not use a buffer and hence can cause heap pressure
  */
class StringXmlPullParser2 extends XmlPullParser2 {

  def initialize (s: String): Boolean = {
    clear
    setData(s.getBytes)

    idx = seekRootTag
    idx >= 0
  }
}

/**
  * a XmlPullParser2 that uses a pre-allocated (but growable) byte array to parse UTF-8 string data
  */
class BufferedStringXmlPullParser2(initBufSize: Int = 8192) extends XmlPullParser2 {

  protected val bb = new Utf8Buffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear
    setData(bb.data, 0, bb.len)

    idx = seekRootTag
    idx >= 0
  }
}

/**
  * a XmlPullParser2 that works on ASCII strings and uses a pre-allocated byte[] buffer
  *
  * NOTE - client is responsible for ensuring there are no non-ASCII chars in parsed strings
  */
class BufferedASCIIStringXmlPullParser2(initBufSize: Int = 8192) extends XmlPullParser2 {

  protected val bb = new AsciiBuffer(initBufSize)

  def initialize (s: String): Boolean = {
    bb.encode(s)
    clear
    setData(bb.data, 0, bb.len)

    idx = seekRootTag
    idx >= 0
  }
}

/**
  * a XmlPullParser2 that works directly on a provided utf-8 byte array
  */
class UTF8XmlPullParser2 extends XmlPullParser2 {

  def initialize (bs: Array[Byte]): Boolean = {
    clear
    setData(bs)

    idx = seekRootTag
    idx >= 0
  }

  def initialize (bs: Array[Byte], off: Int, length: Int): Boolean = {
    clear
    setData(bs, off, length)

    idx = seekRootTag
    idx >= 0
  }

  def initialize (slice: ByteSlice): Boolean = initialize(slice.data, slice.off, slice.limit)
}

/**
  * a XmlPullParser2 that only reads the first (top) element and returns its name (as Slice), if any
  */
class XmlTopElementParser extends UTF8XmlPullParser2 {

  def parseTopElement (bs: Array[Byte], off: Int, length: Int): Boolean = {
    initialize(bs,off,length) && parseNextTag
  }

  def parseTopElement (bs: Array[Byte]): Boolean = parseTopElement(bs,0,bs.length)
  def parseTopElement (slice: ByteSlice): Boolean = parseTopElement(slice.data,slice.off,slice.len)
}