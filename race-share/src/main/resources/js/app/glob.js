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

/**
  * a simple (extended) glob pattern to RegExp translator
  * we handle the Java PathMatcher glob syntax
  */
export function glob2regexp (globPattern) {

  var len = globPattern.length;
  var buf = "^";
  var inAltGroup = false;

  for (var i=0; i<len; i++) {
    var c = globPattern[i];

    switch (c) {
      // special regex chars that have no glob meaning and need to be escaped
      case "!": case "$": case "(": case ")": case "+":  case ".": case "^":  case "/":   buf += "\\"; buf += c; break;

      // simple one-to-one translations of special chars
      case "?":   buf += "."; break;

      // state change
      case "{":   buf += "(";  inAltGroup = true; break;
      case "}":   buf += ")";  inAltGroup = false; break;

      // state dependent translation
      case ",":  if (inAltGroup) buf += "|"; else buf += c; break;

      // the complex case - substring wildcards (both '*' and '**')
      case "*":
        var i0 = i;
        var prev = (i>0) ? globPattern[i-1] : "/";
        for (i++; i<len && globPattern[i] === "*"; i++);  // consume consecutive '*'
        var next = (i < len) ? globPattern[i] : "/";
        var isMultiElement = ((i-i0 > 1) && prev === "/" && next === "/");
        i--;

        if (isMultiElement) { // a "**" pattern - match any number of path elements
          buf += "((?:[^/]*(?:\/|$))*)";
          i++; // consume the trailing path element separator (it's part of our regexp pattern)
        } else {  // a single "*", only match within a single path element
          buf += "([^/]*)";
        }
        break;

      // the rest is added verbatim
      default:    buf += c;
    }
  }

  buf += "$"; // we match whole path strings
  return new RegExp(buf);
}