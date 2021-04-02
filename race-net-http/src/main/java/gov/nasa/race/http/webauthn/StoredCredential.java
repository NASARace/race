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

import com.yubico.webauthn.RegisteredCredential;

/**
 * helper struct to store/restore credentials with jackson
 *
 * this is in Java so that we can use jackson for serialization/deserialization, which is used by the Yubico
 * webauthn server library anyways. We want to avoid adding additional explicit dependencies and hence cannot rely
 * on jackson-module-scala. In general, jackson in Scala is problematic because of it's reliance on reflection
 */
public class StoredCredential {

  /** the credential object itself */
  private final RegisteredCredential credential;

  /** epoch milliseconds when this credential was stored */
  private final long date;

  // just here so that jackson can de-serialize
  private StoredCredential() {
    credential = null;
    date = 0L;
  }

  public StoredCredential (RegisteredCredential credential, long date) {
    this.credential = credential;
    this.date = date;
  }

  public RegisteredCredential getCredential() { return credential; }
  public long getDate() { return date; }

  public String toString() {
    return "StoredCredential(" + credential.getCredentialId() + "," + date + ")";
  }

}
