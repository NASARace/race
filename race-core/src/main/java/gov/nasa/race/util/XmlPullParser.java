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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * a simplified and streamlined pull parser for pre-validated XML
 *
 * the main design points are:
 *
 *  - having a pull parser that keeps track of element nesting
 *  - save function calls and variables (help register alloc)
 *  - avoid branches by iterating to mandatory delimiters and then backing up WS
 *    (e.g. for text or attr *= *"value")
 *  - use exceptions for rare conditions (e.g. EOT)
 *  - supporting specialized text retrievers to save extra allocations
 *  - push unicode conversion into (native) data init and use a simple char[] as
 *    working buffer
 *  - iterate over local vars instead of fields (save get/setfields)
 *  - avoid allocation during parsing
 *
 *  NOTE: XmlPullParser is NOT thread safe, it is designed for speed & memory
 */
public class XmlPullParser implements XmlPullParserIfc {

  public class XmlSyntaxException extends RuntimeException { // malformed XML structure
    XmlSyntaxException (String details, int i, int len) {
      super(details + ": " + contextAt(i, len));
    }
  }
  public class XmlContentException extends RuntimeException { // unexpected XML data
    XmlContentException (String details, int i, int len) {
      super(details + ": " + contextAt(i, len));
    }
  }

  public interface XmlAttributeProcessor {
    void processAttribute (String attrName, String value);
  }

  protected boolean isBuffered = false;

  private char[] buf;
  private int idx;  // always points to the next unprocessed char
  private int textEnd; // set if getText returned something

  // our primitive type based path stack
  private String[] path = new String[64];
  private int top = -1;

  // sub-class accessibles
  protected String tag; // null means no more elements

  protected String text;
  protected double textNum;
  protected int textInt;

  protected String attr;
  protected String value;
  protected double valueNum;
  protected int valueInt;

  protected boolean isStartElement;
  protected boolean lastWasStartElement;

  /** pre-allocate buffer */
  public void setBuffer (char[] b){
    isBuffered = true;
    buf = b;
  }

  public void setBuffered (int size) {
    isBuffered = true;
    buf = new char[size];
  }

  /**
   * initialize using persistent buffer
   * this can be up to 5x faster than allocating a new char[] buffer per parse
   */
  public void initializeBuffered (String s) {
    int slen = s.length();
    if (buf == null || slen > buf.length) {
      buf = new char[slen];
    }
    s.getChars(0,slen,buf,0);
    Arrays.fill(buf,slen,buf.length,(char)0);
    initFields();
  }

  /** initialize using transient buffer */
  public void initialize (char[] b) {
    buf = b;
    initFields();
  }

  public void initialize (String s) {
    if (isBuffered) {
      initializeBuffered(s);
    } else {
      initialize(s.toCharArray());
    }
  }

  private void initFields() {
    idx = 0;
    tag = null;
    text = null;
    textNum = 0;
    textInt = 0;
    textEnd = -1;
    attr = null;
    value = null;
    valueNum = 0;
    valueInt = 0;
    isStartElement = false;
    lastWasStartElement = false;
    top = -1;
  }

  /** use this to allow GC to reclaim buffer memory after parsing is done */
  public void clearBuffer() {
    buf = null;
  }

  // accessors - preferably we extend this in Scala types, so we don't
  // need them. If we do, don't confuse scala's uniform access
  public String tag() { return tag; }
  public String attr() { return attr; }
  public String value() { return value; }
  public String text() { return text; }
  public double textNum() { return textNum; }
  public int textInt() { return textInt; }
  public boolean isStartElement() { return isStartElement; }
  public boolean lastWasStartElement() { return lastWasStartElement; }


  //----------------------------------------- path stack implementation

  private final void growPath(){
    String[] newPath = new String[path.length*2];
    System.arraycopy(path,0,newPath,0,path.length);
    path = newPath;
  }

  private final void pushPath(String t) {
    top++;
    if (top >= path.length){
      growPath();
    }
    path[top] = t;
  }

  private final void popPath() {
    if (top >= 0) top--;
  }

  private final boolean popPathIfEqual(String t) {
    if (top >= 0){
      if (t.equals(path[top])) {
        top--;
        return true;
      }
    }
    return false;
  }

  private final String peekPath() {
    return (top>=0) ? path[top] : null;
  }

  public int getDepth() { return top+1; }

  //----------------------------------------- element iteration

  private final int skipPastEndDirective (int i) {
    char[] buf = this.buf;
    char c;
    do {
      while ((c=buf[i]) != '?') {
        i++;
        if (c == '"') { // skip past string literal
          while (buf[i++] != '"');
        }
      }
    } while (buf[++i] != '>');
    return (++i);
  }

  private final int skipPastComment (int i) {
    char[] buf = this.buf;
    do {
      while (buf[i] != '-') i++;
    } while (buf[++i] != '-' || buf[++i] != '>');
    return i+1;
  }

  private final int skipPastCDATA (int i) {
    char[] buf = this.buf;
    do {
      while (buf[i] != ']') i++;
    } while (buf[++i] != ']' || buf[++i] != '>');
    return i+1;
  }

  private final boolean match (int i, char[] pattern) {
    char[] buf = this.buf;
    for (int j=0; j<pattern.length; j++){
      if (buf[i] != pattern[j]) {
        return false;
      }
      i++;
    }
    return true;
  }

  static char[] CD_START = "[CDATA[".toCharArray();

  public boolean parseNextElement() {
    try {
      int i = idx; // iterate with local vars, not field values
      char[] buf = this.buf;

      if (isStartElement) {  // a non nested element: <x/>
        if (textEnd < 0){
          while (buf[i++] != '>');
          if (buf[i-2] == '/'){
            lastWasStartElement = isStartElement;
            isStartElement = false;
            idx = i;
            popPath();
            return true;
          }
        } else { // we've already read text, skip to the end of it
          i = textEnd;
          textEnd = -1;
        }
      }

      while (true){
        while (buf[i++] != '<') {
          if (buf[i] == 0) return false; // end marker
        }

        char c = buf[i];
        if (c == '?') { // metadata (prolog, skip over)
          i = skipPastEndDirective(i+1);

        } else if (c == '!') { // comment or CDATA
          if (buf[i+1] == '-' && buf[i+2] == '-') { // comment (skip over)
            i = skipPastComment(i+3);
          } else { // maybe CDATA ("<![CDATA[..]]>") - ignored for now
            if (match(i+1,CD_START)) {
              i = skipPastCDATA(i + CD_START.length);
            } else {
              throw new XmlSyntaxException("comment or CDATA expected", i, 4);
            }
          }

        } else if (c == '/') { // end element
          int i0 = ++i;
          while (buf[i++] != '>');
          tag = new String(buf, i0, i-i0-1);
          idx = i;
          if (!popPathIfEqual(tag)) throw new XmlSyntaxException("unbalanced end element ", i0, 10);
          lastWasStartElement = isStartElement;
          isStartElement = false;
          return true;

        } else { // start element
          int i0 = i;
          while ((c=buf[i]) > ' ' && c != '/' && c != '>') i++;
          tag = new String(buf, i0, i-i0);
          idx = i;
          pushPath(tag);
          lastWasStartElement = isStartElement;
          isStartElement = true;
          return true;
        }
      }
    } catch (ArrayIndexOutOfBoundsException aiobx) {
      return false;
    }
  }

  //----------------------------------------- text, typed text and attribute retrieval

  // we are inside a start element, skip to '>' and answer all before next '<'
  public boolean parseTrimmedText() {
    if (skipToText()){
      int i = idx;
      char[] buf = this.buf;

      while (buf[i] <= ' ') i++;
      int i0 = i;
      while (buf[i] != '<') i++;
      textEnd = i;
      i--;
      while (buf[i] <= ' ') i--;
      if (i >= i0){
        text = new String(buf, i0, i-i0+1);
        return true;
      } else {
        return false;
      }
    } else {
      return false;
    }
  }

  public final String trimmedTextOrNull() {
    return parseTrimmedText() ? text : null;
  }

  public boolean skipToText() {
    if (isStartElement){
      int i = idx;
      char[] buf = this.buf;

      while (buf[i] != '>') i++;

      if (buf[i-1] == '/') { // no text for <../> elements
        return false;
      } else {
        i++;
        idx = i;
        textEnd = i;
        return true;
      }

    } else { // no text for </..> end elements
      return false;
    }
  }

  // whitespace or ',' or '<' delimited decimal numbers
  public boolean parseNextDouble() {
    int i = idx;
    char[] buf = this.buf;
    char c;
    long n = 0, d = 0;
    boolean neg = false;
    boolean digitSeen = false;

    while ((c=buf[i]) <= ' ' || c == ',') i++;

    if (c == '<'){
      return false;
    }

    if (c == '-') {
      neg = true;
      i++;
    }

    while ((c=buf[i]) >= '0' && (c <= '9')) { // integer part
      n = (n*10) + (c-'0');
      digitSeen = true;
      i++;
    }

    if (c == '.'){
      d = 1;
      i++;
    }

    while ((c=buf[i]) >= '0' && (c <= '9')) { // decimal part
      d *= 10;
      n = (n*10) + (c-'0');
      digitSeen = true;
      i++;
    }

    if (c != ' ' && c != ',' && c != '<'){ // illegal delimiter
      return false;
    }

    if (digitSeen){
      textNum = neg ? -(double)n : (double)n;
      if (d > 0) textNum /= d;
      idx = i;
      textEnd = i;
      return true;

    } else {
      return false; // not a number
    }
  }

  // whitespace or ',' or '<' delimited decimal numbers
  public boolean parseNextInt() {
    int i = idx;
    char[] buf = this.buf;
    char c;
    int n = 0;
    boolean neg = false;

    while ((c=buf[i]) <= ' ' || c == ',') i++;

    if (c == '<'){
      return false;
    }

    if (c == '-') {
      neg = true;
      i++;
    }

    int i0 = i;
    while ((c=buf[i]) >= '0' && (c <= '9')) { // integer part
      n = (n*10) + (c-'0');
      i++;
    }

    if (c != ' ' && c != ',' && c != '<'){ // illegal delimiter
      return false;
    }

    if (i > i0){
      idx = i;
      textEnd = i;
      textInt = (neg) ? -n : n;
      return true;

    } else {
      return false; // nothing read
    }
  }

  public double readDouble() {
    if (skipToText() && parseNextDouble()){
      return textNum;
    } else throw new XmlContentException("non-numeric text", idx, 5);
  }
  public double readNextDouble() {
    if (parseNextDouble()){
      return textNum;
    } else throw new XmlContentException("non-numeric text", idx, 5);
  }

  public int readInt() {
    if (skipToText() && parseNextInt()){
      return textInt;
    } else throw new XmlContentException("not an int text", idx, 5);
  }
  public int readNextInt() {
    if (parseNextInt()){
      return textInt;
    } else throw new XmlContentException("not an int text", idx, 5);
  }

  public String readText() {
    if (parseTrimmedText()){
      return text;
    } else throw new XmlContentException("no non-whitespace text", idx-15, 35);
  }

  // TODO - this should not create Strings unless the client needs them
  public boolean parseNextAttribute() {
    if (isStartElement) {
      int i=idx;
      char[] buf = this.buf;
      char c;

      while (buf[i] <= ' ') i++;
      if ((c=buf[i]) == '/' || c == '>') {  // no attr
        idx = i;
        return false;

      } else {
        int i0 = i;
        while (buf[i++] != '='); // <2do> check for non '=' error
        attr = new String(buf, i0, i-i0-1);
        if (buf[i] != '"') throw new XmlSyntaxException("non-quoted attribute value", idx, 5);
        i++;
        i0 = i;
        while (buf[i++] != '"');
        value = new String(buf, i0, i-i0-1);
        idx = i;
        return true;
      }
    } else {
      return false; // no attributes for end elements
    }
  }

  /** callback version */
  public void parseAttributes (XmlAttributeProcessor client) {
    while (parseNextAttribute()) {
      client.processAttribute(attr, value);
    }
  }

  public boolean parseAttribute(String k) {
    if (isStartElement) {
      while (parseNextAttribute()){
        if (attr.equals(k)) return true;
      }
    }
    return false;
  }

  public boolean hasAttribute(String k) {
    return parseAttribute(k);
  }

  public boolean hasAttributeValue(String k, String v) {
    if (parseAttribute(k)){
      return (value.equals(v));
    }
    return false;
  }

  public String readAttribute (String k){
    if (parseAttribute(k)){
      return value;
    } else throw new XmlContentException("no attribute " + k, idx, 10);
  }
  public final String attributeOrNull (String k){
    return parseAttribute(k) ? value : null;
  }

  // <2do> these should directly parse the numbers, i.e. we need a parse{Double,Int}Attribute

  public double readDoubleAttribute (String k){
    if (parseAttribute(k)){
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException nfx) {
        throw new XmlContentException("not a double attribute " + k, idx, 10);
      }
    } else throw new XmlContentException("no double attribute " + k, idx, 10);
  }
  public double readIntAttribute (String k){
    if (parseAttribute(k)){
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException nfx) {
        throw new XmlContentException("not a int attribute " + k, idx, 10);
      }
    } else throw new XmlContentException("no int attribute " + k, idx, 10);
  }

  public double doubleValue(){
    return Double.parseDouble(value);
  }

  //--------------------------------------------------------------- path query

  //--- parent queries (ascending order)
  // all hasParent.. queries work bottom-up, i.e. parents are specified in
  // ascending order starting with the immediate parent of the current element

  public boolean hasParent (String p) {
    int i = isStartElement ? top-1 : top;
    try {
      return (p.equals(path[i]));
    } catch (ArrayIndexOutOfBoundsException aiobx){
      return false;
    }
  }

  public boolean hasParents (String p1, String p2) {
    int i = isStartElement ? top-1 : top;
    try {
      return (p1.equals(path[i]) && p2.equals(path[i-1]));
    } catch (ArrayIndexOutOfBoundsException aiobx){
      return false;
    }
  }

  public boolean hasParents (String p1, String p2, String p3) {
    int i = isStartElement ? top-1 : top;
    try {
      return (p1.equals(path[i]) && p2.equals(path[i-1]) && p3.equals(path[i-2]));
    } catch (ArrayIndexOutOfBoundsException aiobx){
      return false;
    }
  }

  public boolean hasParents (String p1, String p2, String p3, String p4) {
    int i = isStartElement ? top-1 : top;
    try {
      return (p1.equals(path[i]) && p2.equals(path[i-1]) && p3.equals(path[i-2]) && p4.equals(path[i-3]));
    } catch (ArrayIndexOutOfBoundsException aiobx){
      return false;
    }
  }

  public boolean hasParents (String[] ps) {
    int i = isStartElement ? top-1 : top;
    try {
      for (int j=0; j<ps.length; j++) {
        if (!ps[j].equals(path[i])) return false;
        i--;
      }
      return true;
    } catch (ArrayIndexOutOfBoundsException aiobx){
      return false;
    }
  }

  public boolean hasSomeParent (String p) {
    for (int i = isStartElement ? top-1 : top; i >= 0; i-- ){
      if (p.equals(path[i])) return true;
    }
    return false;
  }

  //--- path queries (descending order)
  // hasPath.. queries work top down, i.e. matching path patterns are specified in
  // descending order


  protected ArrayList<PathQuery> queries = new ArrayList<PathQuery>();

  // path specs are given in a glob-like notation, with "*" denoting a single level
  // wildcard and "**" any number of levels
  public int compileGlobPathQuery (String[] globs) {
    int idx = queries.size();
    queries.add(new PathQuery( PathQuery.fromGlobs(globs)));
    return idx;
  }

  public boolean isMatchingPath (int queryId) {
    PathQuery pq = queries.get(queryId);
    return pq.matches(path,top+1);
  }

  // avoid this in inner loops because of allocation
  public String getPath() {
    StringBuilder sb = new StringBuilder();
    for (int i=0; i<=top; i++){
      if (i > 0)sb.append('/');
      sb.append(path[i]);
    }
    return sb.toString();
  }

  //... and more to follow

  //--------------------------------------------------------------- debugging aids

  String indent () {
    StringBuffer sb = new StringBuffer();
    int level = isStartElement ? top : top+1;
    for (;level >0; level--){
      sb.append("  ");
    }
    return sb.toString();
  }


  public void printOn (PrintStream ps) {
    String lastText = "";
    while (parseNextElement()){
      if (isStartElement){
        if (lastWasStartElement) ps.println(">");
        ps.print(indent() + '<' + tag);
        while (parseNextAttribute()) {
          ps.print(" " + attr + "=\"" + value + '"');
        }
        lastText = parseTrimmedText() ? text : "";

      } else {
        if (lastWasStartElement){
          if (!lastText.isEmpty()) ps.println(">" + lastText + "</" + tag + '>');
          else ps.println("/>");
        } else {
          ps.println(indent() + "</" + tag + '>');
        }
      }
    }
  }

  public String format () {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(baos);
    printOn(ps);
    ps.flush();
    return baos.toString();
  }

  public void printFileOn (File file, PrintStream ps) {
    if (file.isFile()) {
      try {
        CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder();
        ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(file.toPath()));
        char[] data = decoder.decode(in).array();
        initialize(data);
        printOn(ps);
      } catch (Throwable t) {
        t.printStackTrace();
      }
    }
  }

  final String contentAt (int i, int length) {
    return new String(buf, i, length);
  }

  final String contextAt (int i, int len) {
    StringBuilder sb = new StringBuilder();
    sb.append('"');
    sb.append(new String(buf, i, len));
    sb.append("..\" (");
    sb.append(i);
    sb.append(") element <");
    if (!isStartElement) {
      sb.append('/');
    }
    sb.append(tag);
    sb.append('>');
    return sb.toString();
  }
}
