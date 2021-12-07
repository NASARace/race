/**
 * a simple (extended) glob pattern to RegExp translator
 * we handle the Java PathMatcher glob syntax
 */
export function glob2regexp(globPattern) {
    var len = globPattern.length;
    var buf = "^";
    var inAltGroup = false;

    for (var i = 0; i < len; i++) {
        var c = globPattern[i];

        switch (c) {
            // special regex chars that have no glob meaning and need to be escaped
            case "!":
            case "$":
            case "(":
            case ")":
            case "+":
            case ".":
            case "^":
            case "/":
                buf += "\\";
                buf += c;
                break;

                // simple one-to-one translations of special chars
            case "?":
                buf += ".";
                break;

                // state change
            case "{":
                buf += "(";
                inAltGroup = true;
                break;
            case "}":
                buf += ")";
                inAltGroup = false;
                break;

                // state dependent translation
            case ",":
                if (inAltGroup) buf += "|";
                else buf += c;
                break;

                // the complex case - substring wildcards (both '*' and '**')
            case "*":
                var i0 = i;
                var prev = (i > 0) ? globPattern[i - 1] : "/";
                i++;
                while (i < len && globPattern[i] === "*") i++; // consume consecutive '*'
                var next = (i < len) ? globPattern[i] : "/";
                var isMultiElement = ((i - i0 > 1) && prev === "/" && next === "/");
                i--;

                if (isMultiElement) { // a "**" pattern - match any number of path elements
                    buf += "((?:[^/]*(?:\/|$))*)";
                    i++; // consume the trailing path element separator (it's part of our regexp pattern)
                } else { // a single "*", only match within a single path element
                    buf += "([^/]*)";
                }
                break;

                // the rest is added verbatim
            default:
                buf += c;
        }
    }

    buf += "$"; // we match whole path strings
    return new RegExp(buf);
}

/**
 * CSS conversions
 */
const lengthConverters = {
    //--- absolute sizes
    'px': value => value,
    'cm': value => value * 38,
    'mm': value => value * 3.8,
    'q': value => value * 0.95,
    'in': value => value * 96,
    'pc': value => value * 16,
    'pt': value => value * 1.333333,

    //--- relative sizes
    'rem': value => value * parseFloat(getComputedStyle(document.documentElement).fontSize),
    'em': value => value * parseFloat(getComputedStyle(target).fontSize),
    'vw': value => value / 100 * window.innerWidth,
    'vh': value => value / 100 * window.innerHeight
};

const lengthPattern = new RegExp(`^ *([\-\+]?(?:\\d+(?:\\.\\d+)?))(px|cm|mm|q|in|pc|pt|rem|em|vw|vh)$`, 'i');


export function convertCSSsizeToPx(cssValue, target) {
    target = target || document.body;

    const matches = cssValue.match(lengthPattern);

    if (matches) {
        const value = Number(matches[1]); // the number part of the match
        const unit = matches[2].toLocaleLowerCase(); // the unit part of the match
        const conv = lengthConverters[unit];
        if (conv) return conv(value);
    }

    return cssValue;
}

/**
 * utf-8 encoding
 */
export function toUtf8Array(str) {
    var utf8 = [];
    for (var i = 0; i < str.length; i++) {
        var c = str.charCodeAt(i);
        if (c < 0x80) utf8.push(c);
        else if (c < 0x800) {
            utf8.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
        } else if (c < 0xd800 || c >= 0xe000) {
            utf8.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
        } else {
            i++;
            c = ((c & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff)
            utf8.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 0x3f), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
        }
    }
    return utf8;
}


/**
 * Date utilities
 */

// [h+]:mm:ss
export function toHMSTimeString(millis) {
    let s = Math.floor(millis / 1000) % 60;
    let m = Math.floor(millis / 60000) % 60;
    let h = Math.floor(millis / 3600000);

    let ts = h.toString();
    ts += ':';
    if (m < 10) ts += '0';
    ts += m;
    ts += ':';
    if (s < 10) ts += '0';
    ts += s;

    return ts;
}

export function timeZone(tz) {
    if (!tz) return 'UTC';
    else if (tz == "local") return Intl.DateTimeFormat().resolvedOptions().timeZone;
    else return tz;
}

const defaultTimeFormat = new Intl.DateTimeFormat('en-US', {
    timeZone: 'UTC',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    hour12: false,
    timeZoneName: 'short'
});

const defaultLocalTimeFormat = new Intl.DateTimeFormat('default', {
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    hour12: false
});

export function timeFormat(timeOpts) {
    let to;
    if (!timeOpts) {
        to = defaultTimeFormat;
    } else {
        to = timeOpts;
        if (!to.timeZone) to.timeZone = 'UTC';
        if (!to.timeZoneName) to.timeZoneName = 'short';
    }

    return new Intl.DateTimeFormat('en-US', to);
}

export function toTimeString(d, fmt) {
    if (!fmt) fmt = defaultTimeFormat;
    return fmt.format(d);
}

export function toLocalTimeString(d, fmt) {
    if (!fmt) fmt = defaultLocalTimeFormat;
    return fmt.format(d);
}