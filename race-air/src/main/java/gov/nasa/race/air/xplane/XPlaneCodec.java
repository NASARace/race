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

package gov.nasa.race.air.xplane;

import java.net.DatagramPacket;

import static gov.nasa.race.util.CodecUtils.*;

/**
 * class to read/write packet data for XPlane
 * this assumes XPlane >= 11  running on a little-endian platform (Intel)
 *
 * This class is implemented in Java so that we can create stand-alone test
 * cases for X-Plane communication that do not require RACE
 *
 * The interface has to support minimal/no heap allocation per read
 *
 * NOTE - thread-safety of this class depends on re-use of the `packet` arguments
 */
public class XPlaneCodec {

  static final int HEADER_LEN = 5; // 4 char record type + 0

  public static final String RPOS_HDR = "RPOS";
  public static final String BECN_HDR = "BECN";

  public static class RPOS {
    double latDeg;           // deg
    double lonDeg;           // deg
    double elevationMslm;    // m
    double elevationAglm;    // m
    float  headingDeg;       // deg
    float  pitchDeg;         // deg
    float  rollDeg;          // deg
    float  vx, vy, vz;       // m/sec
    float  p,q,r;            // deg/sec

    public double speedMsec() {
      return Math.sqrt( vx*vx + vy*vy + vz*vz );
    }
  }

  public boolean isRPOSmsg (DatagramPacket packet) {
    byte[] buf = packet.getData();
    return ((buf[0] == 'R') && (buf[1] == 'P') && (buf[2] == 'O') && (buf[3] == 'S') /*&& (buf[4] == 0) */);
  }

  protected RPOS rpos = new RPOS();

  public void readRPOSdata (RPOS rpos, byte[] buf, int off) {
    rpos.lonDeg = readLeD8(buf,off);          off += 8;
    rpos.latDeg = readLeD8(buf,off);          off += 8;
    rpos.elevationMslm = readLeD8(buf,off);   off += 8;
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
  public RPOS readRPOSpacket (DatagramPacket packet) {
    readRPOSdata(rpos, packet.getData(),5);
    return rpos;
  }

  /**
   * write RPOS request position data to buf
   * @param frequencyHz requested RPOS frequency in Hz (0 means no data)
   * @return length of written data
   */
  public int writeRPOSrequest (DatagramPacket packet, int frequencyHz) {
    byte[] buf = packet.getData();

    int off = writeString0(buf,0, "RPOS");
    //off = writeLeI4(buf, off, frequencyHz);
    off = writeString0(buf,off,Integer.toString(frequencyHz));

    packet.setLength(off);
    return off;
  }

  /**
   * BECN - beacon read support to detect running X-Plane instances within the local network
   */

  public static class BECN {
    int hostType;
    int version;
    int role;
    int port;
    String hostName;
  }
  protected BECN becn = new BECN();

  public boolean isBECNmsg (DatagramPacket packet) {
    byte[] buf = packet.getData();
    return ((buf[0] == 'B') && (buf[1] == 'E') && (buf[2] == 'C') && (buf[3] == 'N') && (buf[4] == 0));
  }

  public void readBECNdata (BECN becn, byte[] buf, int off) {
    becn.hostType = readLeI4(buf,7);
    becn.version = readLeI4(buf, 11);
    becn.role = readLeI4(buf, 15);
    becn.port = readLeU2(buf, 19);
    becn.hostName = readString0(buf, 21, 500);
  }
  public BECN readBECNpacket (DatagramPacket packet){
    readBECNdata(becn, packet.getData(), 5);
    return becn;
  }

  /**
   * position own aircraft at airport/runway/dir
   *
   * TODO we should encode runway and dir in the airportId spec
   *
   * @param airportId 4 char airport id (e.g. "KNUQ")
   * @return
   */
  public int writePAPT (DatagramPacket packet, String airportId) {
    byte[] buf = packet.getData();

    int off = writeString0(buf,0, "PREL");
    off = writeLeI4(buf,off,11);  // take off on runway
    off = writeStringN0(buf, off, airportId, 8);
    off = writeLeI4(buf, off, 0); // rwy index ??
    off = writeLeI4(buf, off, 0); // dir index ??

    // all 0 if we start on an airport
    off = writeLeD8(buf, off, 0.0); // lat
    off = writeLeD8(buf, off, 0.0); // lon
    off = writeLeD8(buf, off, 0.0); // ele
    off = writeLeD8(buf, off, 0.0); // hdg
    off = writeLeD8(buf, off, 0.0); // spd

    packet.setLength(off);
    return off;
  }

  /**
   * ACFN - load aircraft
   */
  public int writeACFN (DatagramPacket packet, int aircraft, String relPath, int liveryIndex) {
    byte[] buf = packet.getData();

    int off = writeString0(buf, 0, "ACFN");

    // acfn_struct
    off = writeLeI4(buf, off, aircraft);  // index (starting at 1 for externals)
    off = writeStringN0(buf, off, relPath, 150);
    off += 2;
    off = writeLeI4(buf, off, liveryIndex);

    packet.setLength(off);
    return off;
  }

  /**
   * load & init aircraft
   * @param aircraft [0-19]
   * @param relPath relative path to *.acf file, e.g. "Aircraft/Heavy Metal/B747-100 NASA/B747-100 NASA.acf"
   * @return length of written data
   */
  public int writeACPR(DatagramPacket packet, int aircraft, String relPath, int liveryIndex,
                       double latDeg, double lonDeg, double altMeters, double psiDeg, double speedMsec) {
    byte[] buf = packet.getData();

    int off = writeString0(buf, 0, "ACPR");

    // acfn_struct
    off = writeLeI4(buf, off, aircraft);  // index (starting at 1 for externals)
    off = writeStringN0(buf, off, relPath, 150);
    off += 2;
    off = writeLeI4(buf, off, liveryIndex);

    // PREL_struct
    //off = writeLeI4(buf, off, 9);  // loc_snap_load
    off = writeLeI4(buf, off, 6);  // loc_specify_lle
    off = writeLeI4(buf, off, aircraft);

    off = writeZeros(buf, off, 8); // airport id
    off = writeLeI4(buf, off, 0); // rwy index
    off = writeLeI4(buf, off, 0); // rwy dir

    off = writeLeD8(buf, off, latDeg);
    off = writeLeD8(buf, off, lonDeg);
    off = writeLeD8(buf, off, altMeters);
    off = writeLeD8(buf, off, psiDeg);
    off = writeLeD8(buf, off, speedMsec);

    packet.setLength(off);
    return off;
  }

  /**
   * init aircraft location
   */
  public int writePREL (DatagramPacket packet, int aircraft, double latDeg, double lonDeg, double altMeters, double psiDeg, double speedMsec) {
    byte[] buf = packet.getData();

    int off = writeString0(buf, 0, "PREL");
    //off = writeLeI4(buf, off, 9);  // loc_snap_load
    off = writeLeI4(buf, off, 6);  // loc_specify_lle
    off = writeLeI4(buf, off, aircraft);

    off = writeZeros(buf, off, 8); // airport id
    off = writeLeI4(buf, off, 0); // rwy index
    off = writeLeI4(buf, off, 0); // rwy dir

    off = writeLeD8(buf, off, latDeg);
    off = writeLeD8(buf, off, lonDeg);
    off = writeLeD8(buf, off, altMeters);
    off = writeLeD8(buf, off, psiDeg);
    off = writeLeD8(buf, off, speedMsec);

    packet.setLength(off);
    return off;
  }

  /**
   * write single aircraft position
   */
  public int writeVEHx(DatagramPacket packet, int aircraft,
                              double latDeg, double lonDeg, double elevMsl,
                              float headingDeg, float pitchDeg, float rollDeg,
                              float gear, float flaps, float thrust) {
    byte[] buf = packet.getData();

    int off = writeString0(buf, 0, "VEHX");
    off = writeLeI4(buf, off, aircraft);

    off = writeLeD8(buf, off, latDeg);  // 8
    off = writeLeD8(buf, off, lonDeg);
    off = writeLeD8(buf, off, elevMsl);

    off = writeLeF4(buf, off, headingDeg);  // 32
    off = writeLeF4(buf, off, pitchDeg);
    off = writeLeF4(buf, off, rollDeg);

    // how do we control the gear? The old veh1 gear/flaps/throttle are not supported anymore

    packet.setLength(off);
    return off;
  }

  // VEHA messages are not supported by X-Plane 11 anymore

  /**
   * setting data ref
   */
  public int writeDREF (DatagramPacket packet, String dref, float v) {
    byte[] buf = packet.getData();

    int off = writeString0(buf,0,"DREF");
    off = writeLeF4(buf,off,v);
    off = writeStringN0space(buf, off, dref, 500);

    packet.setLength(off);
    return off;
  }
}
