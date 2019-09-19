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
 * stack of offset/length int pairs (aggregates, fields are public)
 */
public class RangeStack {

    public int capacity;

    public int[] offset;
    public int[] length;
    public int top = -1;

    public RangeStack (int capacity) {
        this.capacity = capacity;
        offset = new int[capacity];
        length = new int[capacity];
    }

    public int size() { return top+1; }
    public boolean isEmpty() { return top < 0; }
    public boolean nonEmpty() { return top >= 0; }

    public void clear() { top = -1; }
    public void pop() { if (top >= 0) top--; }

    public void push (int off, int len) {
        top++;
        if (top >= capacity) grow(capacity*2);

        offset[top] = off;
        length[top] = len;
    }

    public int topOffset() { return offset[top]; }
    public int topLength() { return length[top]; }

    public boolean isTop (int off, int len) { return (top >= 0) && (offset[top] == off) && (length[top] == len); }

    //--- internal methods

    protected void grow (int newCapacity) {
        int[] newOffset = new int[newCapacity];
        int[] newLength = new int[newCapacity];

        int len = top+1
        System.arraycopy(offset,0,newOffset,0,len);
        System.arraycopy(length, 0, newLength, 0,len);

        offset = newOffset;
        length = newLength;
        capacity = newCapacity;
    }

}
