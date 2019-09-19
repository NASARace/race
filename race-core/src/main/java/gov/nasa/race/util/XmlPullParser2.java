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


}
