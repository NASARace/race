
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