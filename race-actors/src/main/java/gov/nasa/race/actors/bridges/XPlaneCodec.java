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

package gov.nasa.race.actors.bridges;

import java.net.DatagramPacket;

import static gov.nasa.race.common.CodecUtils.*;

/**
 * class to read/write packet data for XPlane
 * this assumes XPlane is running on a little-endian platform (Intel)
 *
 * This class is implemented in Java so that we can create stand-alone test
 * cases for X-Plane communication that do not require RACE
 *
 * The interface has to support minimal/no heap allocation per read
 *
 * NOTE - this class is not thread-safe for concurrent read/read or write/write operations
 * since we only have one (per request) read and write buffer
 */
public class XPlaneCodec {

  static final int HEADER_LEN = 5; // 4 char record type + 0

  public static final String RPOS_HDR = "RPOS";

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
      return Math.sqrt( vx*vx + vy*vy + vz*vz);
    }
  }

  private byte[] readBuf = new byte[1024];
  private byte[] writeBuf = new byte[1024];
  public DatagramPacket readPacket = new DatagramPacket(readBuf, readBuf.length);
  public long readFrame = 0;

  static final int RPOS_LEN = 60;

  public String getHeader (DatagramPacket packet) {
    byte[] buf = packet.getData();
    if (buf[0] == 'R'){
      if (buf[1] == 'P') {
        if (buf[2] == 'O') {
          if (buf[3] == 'S') {
            //if (buf[4] == 0){
              return RPOS_HDR;
            //}
          }
        }
      }
    }
    //return new String(buf, 0, 4); // unknown header
    return null;
  }

  private RPOS rpos = new RPOS();
  public static void readRPOSdata (RPOS rpos, byte[] buf, int off) {
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
  public RPOS readRPOSpacket (DatagramPacket packet) {
    readRPOSdata(rpos, packet.getData(),5);
    readFrame++;
    return rpos;
  }

  /**
   * write RPOS request position data to buf
   * @param frequencyHz requested RPOS frequency in Hz (0 means no data)
   * @return length of written data
   */
  public static int writeRPOSrequest (byte[] buf, int frequencyHz) {
    int off = writeString0(buf,0, "RPOS");
    off = writeLeI4(buf, off, frequencyHz);
    return off;
  }
  public DatagramPacket getRPOSrequestPacket (int frequencyHz) {
    return new DatagramPacket(writeBuf, writeRPOSrequest(writeBuf,frequencyHz));
  }

  /**
   * position own aircraft at airport
   * @param airportId 4 char airport id (e.g. "KSJC")
   * @return
   */
  public static int writePAPT (byte[] buf, String airportId) {
    int off = writeString0(buf,0, "PAPT");
    off = writeStringN0(buf, off, airportId, 8);
    off = writeLeI4(buf, off, 11); // 10: ramp start, 11: rwy takeoff, 12: VFR approach, 13: IFR approach
    off = writeLeI4(buf, off, 0);
    off = writeLeI4(buf, off, 0);
    return off;
  }
  public DatagramPacket getPAPTpacket (String airportId) {
    return new DatagramPacket(writeBuf, writePAPT(writeBuf, airportId));
  }

  /**
   * load aircraft
   * @param aircraft [0-19]
   * @param relPath relative path to *.acf file, e.g. "Aircraft/Heavy Metal/B747-100 NASA/B747-100 NASA.acf"
   * @return length of written data
   */
  public static int writeACFN (byte[] buf, int aircraft, String relPath) {
    int off = writeString0(buf, 0, "ACFN");
    off = writeLeI4(buf, off, aircraft);
    off = writeStringN0(buf, off, relPath, 150);
    off += 2;
    off = writeLeI4(buf, off, 0);
    return off;
  }
  public DatagramPacket getACFNpacket (int aircraft, String relPath) {
    return new DatagramPacket(writeBuf, writeACFN(writeBuf, aircraft, relPath));
  }

  /**
   * write single aircraft position
   */
  public static int writeVEH1 (byte[] buf, int aircraft,
                        double latDeg, double lonDeg, double elevMsl,
                        float headingDeg, float pitchDeg, float rollDeg,
                        float gear, float flaps, float thrust) {
    int off = writeString0(buf, 0, "VEH1");
    off = writeLeI4(buf, off, aircraft);
    off += 4; // 8byte struct align

    off = writeLeD8(buf, off, latDeg);
    off = writeLeD8(buf, off, lonDeg);
    off = writeLeD8(buf, off, elevMsl);

    off = writeLeF4(buf, off, headingDeg);
    off = writeLeF4(buf, off, pitchDeg);
    off = writeLeF4(buf, off, rollDeg);

    // all [0.0 - 1.0]
    off = writeLeF4(buf, off, gear);
    off = writeLeF4(buf, off, flaps);
    off = writeLeF4(buf, off, thrust);
    return off;
  }
  public DatagramPacket getVEH1packet (int aircraft, double latDeg, double lonDeg, double elevMsl,
                        float headingDeg, float pitchDeg, float rollDeg, float gear, float flaps, float thrust) {
    return new DatagramPacket(writeBuf, writeVEH1(writeBuf, aircraft,
            latDeg,lonDeg,elevMsl,headingDeg,pitchDeg,rollDeg,gear,flaps,thrust));
  }

}
