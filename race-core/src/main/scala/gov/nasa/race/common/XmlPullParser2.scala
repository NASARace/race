package gov.nasa.race.common

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CoderResult, StandardCharsets}

class XmlParseException (msg: String) extends RuntimeException(msg)

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

  protected val tag = new HashedSliceImpl(buf,0,0)

  protected var isStartElement = false
  protected var lastWasStartElement = false

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
    pathOffset(pathTop) = tag.offset
    pathLength(pathTop) = tag.length
    pathHash(pathTop) = tag.getHash
  }

  def popPath: Unit = {
    if (pathTop >= 0) pathTop -= 1
  }

  def popPathIfEqual: Boolean = {
    if (pathTop >= 0){
      if (tag.equals(buf,pathOffset(pathTop),pathLength(pathTop),pathHash(pathTop))) {
        pathTop -= 1
        return true
      }
    }
    false
  }

  def getPathLength: Int = pathTop+1

  //... and probably more path accessors

  //--- auxiliary parse functions

  private def skipPastEndDirective(i0: Int) = {
    var i = i0
    val buf = this.buf
    do {
      var c = 0
      while ({c = buf(i); c != "?"}){
        i += 1
        if (c == '"'){
          while( buf(i) != '"') i += 1
        }
      }
    } while ({ i += 1; buf(i) != '>'})
    i + 1
  }

  private def skipPastComment(i0: Int) = {
    var i = i0
    val buf = this.buf

    do {
      while (buf(i) != '-') i += 1
    } while ({ i += 1; buf(i) != '-'} || { i += 1; buf(i) != '>'} )
    i + 1
  }

  private def skipPastCDATA(i0: Int) = {
    var i = i0
    val buf = this.buf

    do {
      while (buf(i) != ']') i += 1
    } while ({ i+= 1; buf(i) != ']'} || { i += 1; buf(i) != '>'})
    i + 1
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

    if (isStartElement) {
      if (textEnd < 0){
        while (buf(i) != '>') i += 1
        if (buf(i-2) == '/'){
          lastWasStartElement = isStartElement
          isStartElement = false
          idx = i
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
              throw new XmlParseException(s"comment or CDATA expected around .. ${context(i0)} ..")
            }
          }
        case '/' => // end element
          i += 1
          while (buf(i) != '>') i += 1
          tag.setAndRehash(i0+1, i-i0-2)
          idx = i
          if (!popPathIfEqual) throw new XmlParseException(s"unbalanced end element around ${context(i0)}")
          lastWasStartElement = isStartElement
          isStartElement = false
          return true

        case _ => // start element
          var c = buf(i)
          while (c > ' ' && c != '/' && c != '>'){
            i += 1
            c = buf(i)
          }
          tag.setAndRehash(i0,i-i0)
          idx = i
          pushPath
          lastWasStartElement = isStartElement
          isStartElement = true
          return true
      }
    }
    throw new RuntimeException("can't get here")
  }
}



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
    bb.reset
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
    isStartElement = false
    lastWasStartElement = false
  }
}