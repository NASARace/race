/*
 * Copyright (c) 2018, United States Government, as represented by the
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

import java.io.*;

/**
 * little endian DataOutputStream replacement
 *
 * NOTE this implementation is not threadsafe, clients have to ensure proper synchronization
 * note also that we can't just derive from DataOutputStream since all of its writeX() methods are final
 */
public class LeDataOutputStream extends FilterOutputStream implements DataOutput {

    byte[] buf = new byte[8];

    public LeDataOutputStream(OutputStream out) {
        super(new DataOutputStream(out));
    }

    public void writeBoolean (boolean v)  throws IOException {
        out.write( v ? 1 : 0);
    }

    public void writeByte (int v)  throws IOException {
        out.write(v);
    }

    public void writeChar (int v)  throws IOException {
        buf[0] = (byte) (v & 0xff);
        buf[1] = (byte) ((v >>> 8) & 0xff);
        out.write(buf,0,2);
    }

    public void writeChars (String s)  throws IOException {
        for (int i = 0; i < s.length(); i++) {
            writeChar(s.charAt(i));
        }
    }

    public void writeBytes (String s) throws IOException {
        ((DataOutputStream) out).writeBytes(s);
    }

    public void writeShort (int v)  throws IOException {
        buf[0] = (byte) (v & 0xff);
        buf[1] = (byte) ((v >>> 8) & 0xff);
        out.write(buf,0,2);
    }

    public void writeInt (int v)  throws IOException {
        buf[0] = (byte) (v & 0xff);
        buf[1] = (byte) ((v >>> 8) & 0xff);
        buf[2] = (byte) ((v >>> 16) & 0xff);
        buf[3] = (byte) ((v >>> 24) & 0xff);
        out.write(buf,0,4);
    }

    public void writeLong (long v)  throws IOException {
        buf[0] = (byte) (v & 0xff);
        buf[1] = (byte) ((v >>> 8) & 0xff);
        buf[2] = (byte) ((v >>> 16) & 0xff);
        buf[3] = (byte) ((v >>> 24) & 0xff);
        buf[4] = (byte) ((v >>> 32) & 0xff);
        buf[5] = (byte) ((v >>> 40) & 0xff);
        buf[6] = (byte) ((v >>> 48) & 0xff);
        buf[7] = (byte) ((v >>> 56) & 0xff);
        out.write(buf,0,8);
    }

    public void writeFloat (float f)  throws IOException {
        int v = Float.floatToIntBits(f);
        buf[0] = (byte) (v & 0xff);
        buf[1] = (byte) ((v >>> 8) & 0xff);
        buf[2] = (byte) ((v >>> 16) & 0xff);
        buf[3] = (byte) ((v >>> 24) & 0xff);
        out.write(buf,0,4);
    }

    public void writeDouble (double d)  throws IOException {
        long v = Double.doubleToLongBits(d);
        buf[0] = (byte) (v & 0xff);
        buf[1] = (byte) ((v >>> 8) & 0xff);
        buf[2] = (byte) ((v >>> 16) & 0xff);
        buf[3] = (byte) ((v >>> 24) & 0xff);
        buf[4] = (byte) ((v >>> 32) & 0xff);
        buf[5] = (byte) ((v >>> 40) & 0xff);
        buf[6] = (byte) ((v >>> 48) & 0xff);
        buf[7] = (byte) ((v >>> 56) & 0xff);
        out.write(buf,0,8);
    }

    public void writeUTF (String s)  throws IOException {
        // this has to write modified-UTF8
        ((DataOutputStream) out).writeUTF(s);
    }

    public int size() {
        return ((DataOutputStream) out).size();
    }
}
