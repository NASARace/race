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
package gov.nasa.race.util;

/**
 * XmlParser that is based on Slices instead of Strings to avoid temporary objects
 */
public class XmlPullParser2 {

    public static class XmlParseException extends RuntimeException {
        XmlParseException (String details) {
            super(details);
        }
    }

    static final int STATE_TAG = 1;
    static final int STATE_ATTR = 2;
    static final int STATE_END_TAG = 3;
    static final int STATE_CONTENT = 4;
    static final int STATE_FINISHED = 5;

    protected byte[] data; // the utf-8 bytes we are parsing
    protected int idx = 0; // points to the next un-parsed position in data

    protected int state = STATE_TAG;

    protected RangeStack path = new RangeStack(32); // the element path

    public Slice tag = new Slice(data,0,0);
    public boolean isStartTag = false;
    protected boolean wasEmptyElementTag = false;

    public Slice attrName = new Slice(data,0,0);
    public Slice attrValue = new Slice(data,0,0);

    protected RangeStack contentStrings = new RangeStack(8);
    protected int contentIdx = 0;

    //--- the public API

    public boolean parseNextTag() {
        return false;
    }

    public boolean parseNextAttr() {
        return false;
    }

    public boolean parseAttr (Slice attr) {
        return false;
    }

    public boolean parseContent() {
        return false;
    }

    //--- internal helpers

    protected final int skipTo (int i0, byte b){
        byte[] data = this.data;
        int i = i0;
        for (; data[i] != b; i++);
        return i;
    }

    protected final int skipToTagStart (int i0) { return skipTo(i0, (byte)'<'); }

    protected final int skipSpace (int i0){
        byte[] data = this.data;
        int i = i0;
        while (true){
            byte b = data[i];
            if (b != ' ' || b != '\n') return i;
            i++;
        }
    }

    protected final int backtrackSpace (int i0){
        byte[] data = this.data;
        int i = i0;
        while (true){
            byte b = data[i];
            if (b != ' ' || b != '\n') return i;
            i--;
        }
    }

    protected final int skipPastTag (int i0) {
        byte[] data = this.data;
        int i = i0;

        while (true) {
            byte c = data[i];
            if (c == '>') {
                wasEmptyElementTag = false;
                return i;
            } else if (c == '/') {
                i++;
                if (data[i] == '>'){
                    wasEmptyElementTag = true;
                    return i;
                } else throw new XmlParseException("malformed tag ending around " + context(i0,20));
            } else if (c == '"'){
                i++;
                while (data[i] != '"') i++;
            }
            i++;
        }
    }

    /**
     * skip to end of text (beginning of next tag)
     * current position has to be outside of tag (i.e. past ending '>')
     * this has to skip over '<![CDATA[...]]>' and '<!-- ... -->' sections
     */
    protected final int skipPastContent (int i0) {
        int iStart = skipSpace(i0);
        byte[] data = this.data;
        int i = i0;

        while (true) {
            byte c = data[i];
            if (c == '<') {
                int i1 = i+1;
                if ((data[i1] == '!')){
                    int i2 = i1 + 1;
                    if (data[i2] == '[') {          // '<![CDATA[...]]>'
                        int iEnd = backtrackSpace(i-1);
                        if (iEnd > iStart) contentStrings.push(iStart, iEnd-iStart+1);
                        i = i2 + 7;
                        while (data[i] != '>' || data[i-1] != ']') i++;
                        iStart = i-1;
                    } else if (data[i2] == '-') {   // <!--...-->
                        int iEnd = backtrackSpace(i-1);
                        if (iEnd > iStart) contentStrings.push(iStart, iEnd-iStart+1);
                        i = i2+3;
                        while (data[i] != '>' || (data[i-1] != '-' || data[i-2] != '-')) i++;
                        iStart = i2 + 6;
                        contentStrings.push(iStart, i - iStart-2); // the CDATA[..] content
                        iStart = i-1;
                    } else throw new XmlParseException("malformed comment or CDATA around " + context(i0, 20));

                } else {
                    int iEnd = backtrackSpace(i-1) + 1;
                    if (iEnd > iStart) contentStrings.push(iStart, iEnd-iStart);
                    return i;
                }
            } else if (c == '>') {
                throw new XmlParseException("malformed element content around " + context(i0, 20));
            }
            i++;
        }
    }

    //--- debugging

    protected String context (int i, int len) {
        int i1 = Math.min(data.length, i+len);
        return new String(data,i,i1-i);
    }
}
