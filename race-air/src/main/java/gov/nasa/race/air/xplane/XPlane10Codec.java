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
package gov.nasa.race.air.xplane;

import java.net.DatagramPacket;

import static gov.nasa.race.util.CodecUtils.*;
import static gov.nasa.race.util.CodecUtils.writeLeF4;

/**
 * XPlane 10 version of XPlaneCodec
 */
public class XPlane10Codec extends XPlaneCodec {

    public int writeRPOSrequest (DatagramPacket packet, int frequencyHz) {
        byte[] buf = packet.getData();
        int off = writeString0(buf,0, "RPOS");
        off = writeLeI4(buf, off, frequencyHz);

        packet.setLength(off);
        return off;
    }

    public void readRPOSdata (XPlaneCodec.RPOS rpos, byte[] buf, int off) {
        rpos.lonDeg = readLeD8(buf,off);          off += 8;
        rpos.latDeg = readLeD8(buf,off);          off += 8;
        rpos.elevationMslm = readLeF4(buf,off);   off += 4;
        rpos.elevationAglm = readLeF4(buf,off);   off += 4;
        rpos.pitchDeg = readLeF4(buf,off);        off += 4;
        rpos.headingDeg = readLeF4(buf,off);      off += 4;
        rpos.rollDeg = readLeF4(buf,off);         off += 4;
        rpos.vx = readLeF4(buf,off);              off += 4;
        rpos.vy = readLeF4(buf,off);              off += 4;
        rpos.vz = readLeF4(buf,off);              off += 4;
        rpos.p = readLeF4(buf,off);               off += 4;
        rpos.q = readLeF4(buf,off);               off += 4;
        rpos.r = readLeF4(buf,off);
    }

    public int writeVEHx(DatagramPacket packet, int aircraft,
                                double latDeg, double lonDeg, double elevMsl,
                                float headingDeg, float pitchDeg, float rollDeg,
                                float gear, float flaps, float thrust) {
        byte[] buf = packet.getData();

        int off = writeString0(buf, 0, "VEH1");
        off = writeLeI4(buf, off, aircraft);
        off += 4; // 8byte struct align

        off = writeLeD8(buf, off, latDeg);  // 8
        off = writeLeD8(buf, off, lonDeg);
        off = writeLeD8(buf, off, elevMsl);

        off = writeLeF4(buf, off, headingDeg);  // 32
        off = writeLeF4(buf, off, pitchDeg);
        off = writeLeF4(buf, off, rollDeg);

        // all [0.0 - 1.0]
        off = writeLeF4(buf, off, gear);  // 44
        off = writeLeF4(buf, off, flaps);
        off = writeLeF4(buf, off, thrust);

        packet.setLength(off);
        return off;
    }

    /**
     * position own aircraft at airport
     * @param airportId 4 char airport id (e.g. "KSJC")
     * @return
     */
    public int writePAPT (DatagramPacket packet, String airportId) {
        byte[] buf = packet.getData();

        int off = writeString0(buf,0, "PAPT");
        off = writeStringN0(buf, off, airportId, 8);
        off = writeLeI4(buf, off, 11); // 10: ramp start, 11: rwy takeoff, 12: VFR approach, 13: IFR approach
        off = writeLeI4(buf, off, 0);
        off = writeLeI4(buf, off, 0);

        packet.setLength(off);
        return off;
    }

}
