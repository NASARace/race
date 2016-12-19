package gov.nasa.race.common

import gov.nasa.race.util.StringUtils
import java.security.MessageDigest
import scala.annotation.tailrec

/**
  * create MD5 checksums for Strings
  *
  * Note - this implementation is NOT thread safe, it tries to avoid allocation to
  * support highly repetitive use cases that involve long Strings. We also compute the hash
  * independent of charset encodings, to support hash comparison across the network
  */
class MD5Checksum (bufSize: Int=1024) {

  val md = MessageDigest.getInstance("MD5")
  val hb = new Array[Byte](16) // MD5 computes 128 bit hashes

  def getHexChecksum (s: String): String = {
    val slen = s.length
    @tailrec def updateMd(i: Int, md: MessageDigest): Unit = {
      if (i < slen){
        val c = s.charAt(i)
        if (c > 255){
          md.update(((c >> 8) & 0xff).toByte)
          md.update((c & 0xff).toByte)
        } else {
          md.update(c.toByte)
        }
        updateMd(i+1,md)
      }
    }

    md.reset
    updateMd(0,md)
    md.digest(hb,0,hb.length)
    StringUtils.toHexString(hb)
  }
}
