/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.http.webauthn;

import com.yubico.webauthn.data.ByteArray;

/**
 * helper struct to store userName -> userHandle mappings with jackson
 */
public class StoredUserEntry {
  private final String uid;
  private final ByteArray uh;

  // for jackson instantiation
  private StoredUserEntry(){
    uid = null;
    uh = null;
  }

  public StoredUserEntry (String uid, ByteArray uh) {
    this.uid = uid;
    this.uh = uh;
  }

  public String getUid() { return uid; }
  public ByteArray getUh() { return uh; }

  public String toString() {
    return "StoredUserEntry(\"" + uid + "\"," + uh + ")";
  }
}
