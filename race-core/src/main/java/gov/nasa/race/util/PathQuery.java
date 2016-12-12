package gov.nasa.race.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * utility class for path queries supporting multi-level wildcards.
 * Used to match XML element paths in XmlPullParser
 */

class PathQuery {
  static Matcher[] fromGlobs (String[] globs) {
    Matcher[] a = new Matcher[globs.length];
    for (int i=0; i<globs.length; i++){
      String glob = globs[i];
      if (!glob.equals("**")) {
        String re = glob.replace("*",".*");
        re = re.replace("?",".");
        re = re.replace("[!","[^");
        Pattern p = Pattern.compile(re);
        a[i] = p.matcher(""); // we reset it prior to matching
      } else {
        a[i] = null; // just to show that we treat null as any number of glob path elements
      }
    }
    return a;
  }

  PathQuery (Matcher[] matchers) {
    this.matchers = matchers;
  }

  protected Matcher[] matchers;

  public boolean matches (String[] path, int maxDepth) {
    int j = 0;
    int i = 0;
    for (; i<matchers.length && j < maxDepth; i++) {
      Matcher matcher = matchers[i];
      if (matcher == null) { // any number of wildcard path elements
        for (i++; i<matchers.length; i++){
          if (matchers[i] != null) break;
        }
        if (i == matchers.length) return true;  // no more concrete pattern left, last "**" matches everything
        matcher = matchers[i];
        boolean foundMatch = false;
        while (j < maxDepth && !foundMatch){
          matcher.reset(path[j++]);
          if (matcher.matches()) foundMatch = true;
        }
        if (!foundMatch) return false;

      } else { // it's a concrete pattern, match it against the next path element
        matcher.reset(path[j]);
        if (!matcher.matches()) return false;
        j++;
      }
    }

    return (j == maxDepth && i == matchers.length);
  }

  public boolean matches (String[] path) {
    return matches(path, path.length);
  }
}