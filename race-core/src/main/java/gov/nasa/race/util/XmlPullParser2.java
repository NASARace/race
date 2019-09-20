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

    abstract class State {
        boolean parseNextTag() {
            return false;
        }
        boolean parseNextAttr(){
            return false;
        }
        boolean parseContent() {
            return false;
        }
    }

    protected final  boolean _setTag(int i0, int i, boolean isStart, State nextState) {
        idx = i;
        tag.set(data,i0,i-i0);
        isStartTag = isStart;

        attrName.clear();
        attrValue.clear();
        rawContent.clear();
        contentStrings.clear();

        if (isStart) { // start tag
            path.push(tag.offset,tag.length);
            state = nextState;

        } else { // end tag
            if (isTopTag(tag)) {
                path.pop();
                state = (path.isEmpty()) ? finishedState : nextState;
            } else {
                throw new XmlParseException("unbalanced end tag around " + context(i0, 20));
            }
        }
        return true;
    }

    /**
     * position is on '<'
     * next state is either attrState (' '), endTagState ('/>') or contentState ('>')
     */
    class TagState extends State {
        @Override boolean parseNextTag() {
            byte[] data = XmlPullParser2.this.data;

            int i0 = idx+1;
            int i = i0;
            if (data[i] == '/') { // '</..> end tag
                i++;
                i0 = i;
                i = skipTo(i, (byte)'>');
                return _setTag(i0, i, false, contentState);

            } else {  // '<..' start tag
                while (true) {
                    switch (data[i]) {
                        case ' ':
                            return _setTag(i0, i, true, attrState);
                        case '/':
                            int i1 = i + 1;
                            if (data[i1] == '>') {
                                wasEmptyElementTag = true;
                                return _setTag(i0, i1, true, endTagState);
                            }
                            throw new XmlParseException("malformed empty element tag around " + context(i0, 20));
                        case '>':
                            wasEmptyElementTag = false;
                            return _setTag(i0, i, true, contentState);
                        default:
                            i++;
                    }
                }
            }
        }
    }

    /**
     * position on ' ' after tag
     * next state is either attrState (in case attr is parsed), endtagState ('/>') or contentState ('>')
     */
    class AttrState extends State {
        @Override boolean parseNextTag() {
            idx = skipPastTag(idx);
            state = wasEmptyElementTag ? endTagState : contentState;
            return state.parseNextTag();
        }

        @Override boolean parseNextAttr() {
            byte[] data = XmlPullParser2.this.data;

            int i = skipSpace(idx);
            int i1;
            switch(data[i]) {
                case '/':
                    i1 = i+1;
                    if (data[i1] == '>') {
                        idx = i1;
                        wasEmptyElementTag = true;
                        state = endTagState;
                        return false;
                    }
                    throw new XmlParseException("malformed tag end around " + context(idx, 20));
                case '>':
                    idx = i;
                    wasEmptyElementTag = false;
                    state = contentState;
                    return false;
                default:
                    int i0 = i;
                    i = skipTo(i,(byte)'=');
                    i1 = backtrackSpace(i-1)+1;
                    attrName.set(data,i0,i1-i0);
                    i = skipTo(i+1,(byte)'"');

                    i++;
                    i0 = i;
                    i = skipTo(i,(byte)'"');

                    attrValue.set(data,i0,i-i0);
                    idx = i+1;
                    return true;
            }
        }
    }

    /**
     * position is on '>' of a '.../>' tag
     * next is always contentState
     */
    class EndTagState extends State {
        @Override boolean parseNextTag() {
            // tag is still valid
            isStartTag = false;
            wasEmptyElementTag = true;
            path.pop();

            state = (path.isEmpty()) ? finishedState : contentState;

            return true;  // this always returns true since we already got the end tag
        }
    }

    /**
     * position is on '>',  _wasEmptyElementTag is reset (processed)
     */
    class ContentState extends State {
        @Override boolean parseNextTag() {
            if (path.nonEmpty()) {
                idx = skipPastContent(idx + 1);
                state = tagState;
                return state.parseNextTag();
            } else {
                return false;
            }
        }

        @Override boolean parseContent(){
            int i0 = idx+1;
            idx = skipPastContent(i0); // this also sets the contentStrings
            rawContent.set(data,i0,idx-i0); // this includes surrounding whitespace, comments and CDATA sections
            state = tagState;
            if (contentStrings.nonEmpty()){
                contentIdx = 0;
                return true;
            } else {
                return false;
            }
        }
    }

    class FinishedState extends State {
        // all return false
    }

    protected final State tagState = new TagState();
    protected final State attrState = new AttrState();
    protected final State endTagState = new EndTagState();
    protected final State contentState = new ContentState();
    protected final State finishedState = new FinishedState();

    protected byte[] data; // the utf-8 bytes we are parsing
    protected int idx = 0; // points to the next un-parsed position in data

    protected State state = tagState;

    protected RangeStack path = new RangeStack(32); // the element path

    public Slice tag = new Slice();
    public boolean isStartTag = false;
    protected boolean wasEmptyElementTag = false;

    public Slice attrName = new Slice();
    public Slice attrValue = new Slice();

    protected RangeStack contentStrings = new RangeStack(8);
    protected int contentIdx = 0;
    protected Slice rawContent = new Slice();

    //--- the public API (final so that it can be inlined by JIT)

    // reset everything
    public void clear() {
        // data might be a self allocated buffer
        idx = 0;
        state = tagState;
        path.clear();
        contentStrings.clear();
        isStartTag = false;
        wasEmptyElementTag = false;
        tag.clear();
        attrName.clear();
        attrValue.clear();
        rawContent.clear();
    }

    public final boolean parseNextTag() {
        return state.parseNextTag();
    }

    public final boolean parseNextAttr() {
        return state.parseNextAttr();
    }

    public final boolean parseAttr (Slice a) {
        while (state.parseNextAttr()){
            if (attrName.equals(a.data, a.offset, a.length)) return true;
        }
        return false;
    }

    public final boolean parseContent() {
        return state.parseContent();
    }

    public final boolean isTopTag (Slice t) {
        return path.topEquals(t);
    }

    //--- path query

    public boolean tagHasParent(Slice tn) {
        if (path.top > 0){
            int i = isStartTag ? path.top-1 : path.top;
            return tn.equals(data, path.offset[i], path.length[i]);
        } else {
            return false;
        }
    }

    public boolean tagHasAncestor (Slice tn) {
        int i = isStartTag ? path.top-1 : path.top;
        for (; i>= 0; i--) {
            if (tn.equals(data,path.offset[i],path.length[i])) return true;
        }
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
