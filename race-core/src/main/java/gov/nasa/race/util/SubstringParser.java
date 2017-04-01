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

/**
 * various parsers for substrings that avoid creating substring objects
 */
public class SubstringParser {

  private char[] buf;
  private int len;
  private int eIdx;

  public SubstringParser (String s) {
    this(s.toCharArray());
  }

  public SubstringParser (char[] buf) {
    initialize(buf);
  }

  public void initialize (char[] buf) {
    this.buf = buf;
    this.len = buf.length;
    this.eIdx = len-1;
  }

  public int parseInt (int startIdx) {
    return parseInt(startIdx, eIdx);
  }

  public int parseInt (int startIdx, int endIdx) {
    int i = startIdx;
    int sign = 1;
    int acc = 0;
    char c = buf[i];

    for (; c == ' ' || c == '\t' || c == '\n' || c == '\r'; c = buf[++i]);

    if (c == '+'){
      c = buf[++i];
    } else if (c == '-'){
      c = buf[++i];
      sign = -1;
    }
    for (;c >= '0' && c <= '9'; c = buf[++i]) {
      acc = acc * 10 + (c - '0');
      if (i == endIdx) break;
    }

    return acc * sign;
  }

  private int decPow (int n) {
    if (n == 0) return 1;
    else {
      int x = 1;
      for (int i=0; i<n; i++) x = x * 10;
      return x;
    }
  }

  public double parseDouble (int startIdx, int endIdx) {
    int i = startIdx;
    int sign = 1;
    int acc = 0;
    char c = buf[i];

    for (; c == ' ' || c == '\t' || c == '\n' || c == '\r'; c = buf[++i]);

    if (c == '+'){
      c = buf[++i];
    } else if (c == '-'){
      c = buf[++i];
      sign = -1;
    }

    for (;c >= '0' && c <= '9'; c = buf[++i]) {
      acc = acc * 10 + (c - '0');
      if (i == endIdx) break;
    }

    if (i < endIdx) {
      if (c == '.'){
        c = buf[++i];
        int d = 0;
        int n = 1;
        for (;c >= '0' && c <= '9'; c = buf[++i]) {
          d = d * 10 + (c - '0');
          n *= 10;
          if (i == endIdx) break;
        }

        if (c == 'e') {
          c = buf[++i];
          int esign = 1;
          int exp = 0;

          if (c == '+'){
            c = buf[++i];
          } else if (c == '-'){
            c = buf[++i];
            esign = -1;
          }

          for (;c >= '0' && c <= '9'; c = buf[++i]) {
            exp = exp * 10 + (c - '0');
            if (i == endIdx) break;
          }
          if (esign > 0) {
            return sign * (((double)acc + (double)d/n) * decPow(exp));
          } else {
            return sign * (((double)acc + (double)d/n) / decPow(exp));
          }

        } else {
          return sign * ((double)acc + (double)d / n);
        }
      }
    }

    return sign * (double)acc;
  }

  // this is a somewhat esoteric format: a double fraction with optional exponent
  // that starts with a sign instead of 'e', such as "-11606-4" = -0.11606e-4
  // (e.g. used in TLEs - reminiscent to punch cards)
  public double parseDoubleFraction (int startIdx, int endIdx) {
    int i = startIdx;
    int sign = 1;
    int d = 0;
    int n = 1;
    char c = buf[i];

    for (; c == ' ' || c == '\t' || c == '\n' || c == '\r'; c = buf[++i]);

    if (c == '+'){
      c = buf[++i];
    } else if (c == '-'){
      c = buf[++i];
      sign = -1;
    }

    for (;c >= '0' && c <= '9'; c = buf[++i]) {
      d = d * 10 + (c - '0');
      n = n*10;
      if (i == endIdx) break;
    }

    if (i < endIdx) {
      if (c == '-' || c == '+') {
        int esign = 1;
        int exp = 0;

        if (c == '+'){
          c = buf[++i];
        } else {
          c = buf[++i];
          esign = -1;
        }

        for (;c >= '0' && c <= '9'; c = buf[++i]) {
          exp = exp * 10 + (c - '0');
          if (i == endIdx) break;
        }
        if (esign > 0) {
          return sign * (((double)d/n) * decPow(exp));
        } else {
          return sign * (((double)d/n) / decPow(exp));
        }
      }
    }

    return sign * (double)d/n;
  }

  public double parseDouble (int startIdx) {
    return parseDouble(startIdx, eIdx);
  }

  public static void main (String[] args) {
    SubstringParser p = new SubstringParser(".1");
    System.out.println(p.parseDouble(0));
  }
}
