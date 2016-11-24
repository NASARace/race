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

package gov.nasa.race.util;

/**
 * the XmlPullParser interface so that we can mix in specific getters through
 * interfaces with default methods
 */
public interface XmlPullParserIfc {

  // field accessors
  String tag();
  String attr();
  String value();
  String text();
  double textNum();
  int textInt();

  boolean isStartElement();
  boolean lastWasStartElement();

  boolean parseNextElement();

  // text parsers
  boolean parseTrimmedText();
  boolean skipToText();
  boolean parseNextDouble();
  boolean parseNextInt();

  // attribute parsers
  boolean parseNextAttribute();

  //... we should probably add the readers here, too
  int readNextInt();
  double readNextDouble();
}
