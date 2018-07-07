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

package gov.nasa.race.util;

import java.util.Arrays;

/**
 * functions to encode/decode into/from byte buffers
 */
public class CodecUtils {

  //--- readers

  public static int readU1 (byte[] buf, int off) {
    return (buf[off] & 0xff);
  }

  public static int readLeU2 (byte[] buf, int off){
    int n=(buf[off++] & 0xff);
    n |= (int)(buf[off++] & 0xff) <<8;
    return n;
  }

  /**
   * read little endian int
   */
  public static int readLeI4 (byte[] buf, int off) {
    int n=(buf[off++] & 0xff);
    n |= (int)(buf[off++] & 0xff) <<8;
    n |= (int)(buf[off++] & 0xff) <<16;
    n |= (int)(buf[off++] & 0xff) <<24;
    return n;
  }

  public static long readLeU4 (byte[] buf, int off) {
    long n=(buf[off++] & 0xff);
    n |= (int)(buf[off++] & 0xff) <<8;
    n |= (int)(buf[off++] & 0xff) <<16;
    n |= (int)(buf[off++] & 0xff) <<24;
    return n;
  }

  /**
   * read little endian float
   */
  public static float readLeF4 (byte[] buf, int off) {
    int n=(buf[off++] & 0xff);
    n |= (int)(buf[off++] & 0xff) <<8;
    n |= (int)(buf[off++] & 0xff) <<16;
    n |= (int)(buf[off++] & 0xff) <<24;
    return Float.intBitsToFloat(n);
  }

  /**
   * read little endian double
   */
  public static double readLeD8 (byte[] buf, int off) {
    long n=(buf[off++] & 0xff);
    n |= (long)(buf[off++] & 0xff) <<8;
    n |= (long)(buf[off++] & 0xff) <<16;
    n |= (long)(buf[off++] & 0xff) <<24;
    n |= (long)(buf[off++] & 0xff) <<32;
    n |= (long)(buf[off++] & 0xff) <<40;
    n |= (long)(buf[off++] & 0xff) <<48;
    n |= (long)(buf[off++] & 0xff) <<56;
    return Double.longBitsToDouble(n);
  }

  /**
   * read 0-terminated string of length len
   */
  public static String readStringN0 (byte[] buf, int off, int len) {
    return new String(buf, off, len);
  }

  public static String readString0 (byte[] buf, int off, int maxLen) {
    int n = 0;
    int i1 = off + maxLen;
    for (int i=off; i<i1 && buf[i] != 0; i++, n++);
    return new String(buf,off,n);
  }

  //--- writers

  /**
   * write little endian int
   */
  public static int writeLeI4 (byte[] buf, int off, int d) {
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff);
    return off;
  }

  /**
   * write little endian float
   */
  public static int writeLeF4 (byte[] buf, int off, float f) {
    int d = Float.floatToIntBits(f);
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff);
    return off;
  }

  /**
   * write little endian double
   */
  public static int writeLeD8 (byte[] buf, int off, double v) {
    long d = Double.doubleToLongBits(v);
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff); d >>= 8;
    buf[off++] = (byte) (d & 0xff);
    return off;
  }

  /**
   * write 0-terminated String
   */
  public static int writeString0 (byte[] buf, int off, String s) {
    byte[] b = s.getBytes();
    int len = b.length;
    System.arraycopy(b,0,buf,off,len);
    off += len;
    buf[off++] = 0;
    return off;
  }

  /**
   * write 0-filled String of of given length
   */
  public static int writeStringN0 (byte[] buf, int off, String s, int fixLen) {
    byte[] b = s.getBytes();
    int len = Math.min(b.length, fixLen);
    int off0 = off;
    System.arraycopy(b,0,buf,off,len);
    if (len < fixLen) Arrays.fill(buf,off0+len, off0+fixLen, (byte)0);
    return off0 + fixLen;
  }

  public static int writeStringN0space (byte[] buf, int off, String s, int fixLen) {
    byte[] b = s.getBytes();
    int len = Math.min(b.length, fixLen);
    int off0 = off;
    System.arraycopy(b,0,buf,off,len);
    if (len < fixLen) {
      buf[off0+len] = 0;
      if (len < fixLen-1) {
        Arrays.fill(buf, off0 + len + 1, off0 + fixLen, (byte) 32);
      }
    }
    return off0 + fixLen;
  }

  public static int writeZeros (byte[] buf, int off, int len) {
    Arrays.fill(buf,off,off+len,(byte)0);
    return off + len;
  }
}
