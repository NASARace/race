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
 * part of a byte[] array. This is a simple aggregate type to avoid premature String allocation
 *
 * this is in Java since it is used by XmlParser2 and is highly performance critical so we don't want
 * any Scala overhead
 */
public class Slice {

    byte[] data = null;
    int offset = 0;
    int length = 0;

    public Slice (byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    public Slice (byte[] data) {
        this.data = data;
        this.offset = 0;
        this.length = data.length;
    }

    public Slice (String s) {
        this.data = s.getBytes();
        this.offset = 0;
        this.length = data.length;
    }

    public boolean isEmpty() {
        return (length == 0);
    }

    public boolean nonEmpty() {
        return (length > 0);
    }

    public void clear() {
        data = null;
        offset = 0;
        length = 0;
    }

    public void set (byte[] newData, int newOffset, int newLength) {
        data = newData;
        offset = newOffset;
        length = newLength;
    }

    public void setFrom (Slice other) {
        data = other.data;
        offset = other.offset;
        length = other.length;
    }


    //--- some convenience conversions

    @Override
    public String toString() {
        if (length > 0) {
            return new String(data,offset,length);
        } else {
            return "";
        }
    }

    public long toLong() {
        int i = offset;
        int iMax = i + length;
        byte[] bs = this.data;
        long n = 0;

        if (i >= iMax) throw new NumberFormatException(toString());
        int sig = 1;
        if (bs[i] == '-') {
            sig = -1;
            i++;
        }

        //--- integer part

        for (;i < iMax; i++) {
            byte b = bs[i];
            if (b < '0' || b > '9') break;
            n = (n*10) + (b - '0');
        }

        if (i < iMax) throw new NumberFormatException(toString());

        return (sig * n);
    }

    public double toDouble() {
        int i = offset;
        int iMax = i + length;
        byte[] bs = this.data;
        long n = 0;
        double d = 0.0;
        long e = 1;
        byte b = 0;

        if (i >= iMax) throw new NumberFormatException(toString());
        int sig = 1;
        if (bs[i] == '-') {
            sig = -1;
            i++;
        }

        //--- integer part
        for (;i < iMax; i++) {
            b = bs[i];
            if (b < '0' || b > '9') break;
            n = (n*10) + (b - '0');
        }

        //--- fractional part
        if (b == '.') {
            i++;
            long m = 1;
            int frac = 0;

            for (; i < iMax; i++){
                b = bs[i];
                if (b < '0' || b > '9') break;
                frac = (frac * 10) + (b - '0');
                m *= 10;
            }
            d = (double)frac/m;
        }

        //--- exponent part
        if ((b|32) == 'e'){
            i++;
            if (i >= iMax) throw new NumberFormatException(toString());

            b = bs[i];
            if (b == '-'){
                i++;
                e = -1;
            } else if (b == '+') {
                i++;
            }

            int exp = 0;
            for (;i < iMax; i++) {
                b = bs[i];
                if (b < '0' || b > '9') break;
                exp = (exp*10) + (b - '0');
            }

            for (int j=0; j < exp; j++) e *= 10;
        }

        if (i < iMax) throw new NumberFormatException(toString());

        if (e < 0){
            return sig * -(n + d) / e;
        } else {
            return sig * (n + d) * e;
        }
    }

    public boolean equals (byte[] otherBs, int otherOffset, int otherLength) {
        byte[] bs = this.data;
        if (length == otherLength) {
            int iEnd = offset + length;
            for (int i = offset, j=otherOffset; i < iEnd; i++, j++) {
                if (bs[i] != otherBs[j]) return false;
            }
            return true;
        } else {
            return false;
        }
    }


    /**
     * the Scala == operator
     */
    public boolean $eq$eq (Slice other) {
        return equals(other.data, other.offset, other.length);
    }

    /**
     * the Scala != operator
     */
    public boolean $bang$eq (Slice other) {
        return !equals(other.data, other.offset, other.length);
    }

    @Override
    public boolean equals (Object o) {
        if (o instanceof Slice){
            return $eq$eq ((Slice)o);
        } else {
            return false;
        }
    }
}
