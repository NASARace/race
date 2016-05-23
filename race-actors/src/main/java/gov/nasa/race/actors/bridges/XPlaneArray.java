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

import gov.nasa.race.data.FlightPos;

import java.util.function.Function;

/**
 * wrapper around array of aircraft we send to X-Plane, which
 * supports only a fixed number of external planes
 * Java because it needs to iterate fast and in constant space
 *
 * aircraft 0 is own aircraft and should not be changed after the simulation
 * was started
 */
public class XPlaneArray {
  static final int MAX_PLANES = 20;

  static class Entry {
    String cs;
    String acType;

    Entry (String cs, String acType){
      this.cs = cs;
      this.acType = acType;
    }
  }

  private final Entry[] entries = new Entry[MAX_PLANES];

  public int getIndex (String cs) {
    for (int i=1; i<MAX_PLANES; i++){
      Entry e = entries[i];
      if (e != null && cs.equals(e.cs)) return i;
    }
    return -1; // nothing found
  }

  public int add (String cs, String acType) {
    for (int i=1; i<MAX_PLANES; i++) {
      if (entries[i] == null) {
        entries[i] = new Entry(cs,acType);
        return i;
      }
    }
    return -1; // no space anymore
  }

  public void remove (int i) {
    entries[i] = null;
  }

  public Entry get (int idx){
    return entries[idx];
  }

  public void foreach (Function<Entry,Void> func) {
    for (int i=1; i<MAX_PLANES; i++) {
      if (entries[i] != null) func.apply(entries[i]);
    }
  }
}
