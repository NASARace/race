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

//--- string matching

const pathRegex = /^(.+)\/([^\/]+)$/;

export function matchPath(path) {
    return path.match(pathRegex);
}

//--- number formatting

export const f_0 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 0, minimumFractionDigits: 0 });
export const f_1 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 1, minimumFractionDigits: 1 });
export const f_2 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 2, minimumFractionDigits: 2 });
export const f_3 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 3, minimumFractionDigits: 3 });
export const f_4 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 4, minimumFractionDigits: 4 });
export const f_5 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 5, minimumFractionDigits: 5 });

const f_N = [f_0, f_1, f_2, f_3, f_4, f_5];

export const fmax_0 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 0 });
export const fmax_1 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 1 });
export const fmax_2 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 2 });
export const fmax_3 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 3 });
export const fmax_4 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 4 });
export const fmax_5 = new Intl.NumberFormat('en-US', { notation: 'standard', maximumFractionDigits: 5 });


//--- position formatting

export function degreesToString(arr, fmt=fmax_5) {
    let s = "";
    arr.forEach( v=> {
        if (s.length > 0) s += ",";
        s += fmt.format(v);
    });
    return s;
}

export function toLatLonString(lat, lon, decimals = 5) {
    let i = decimals > 5 ? 5 : (decimals < 0) ? 0 : decimals;
    let fmt = f_N[i];
    let sLat = fmt.format(lat);
    let sLon = fmt.format(lon);
    return sLat + ',' + sLon;
}

//--- string formatting

export function toRightAlignedString(n, minChars) {
    let s = n.toString();
    if (s.length < minChars) s = ' '.repeat(minChars - s.length) + s;
    return s;
}

//--- unit conversions

export function metersPerSecToKnots(spd) {
    return (spd * 1.94384449);
}

export function metersToFlightLevel(alt) {
    return Math.round(alt * 0.00656167979) * 5;
}

export function squareMetersToAcres(area) {
    return (area / 4046.8564224);
}

//--- date utilities

export const MILLIS_IN_DAY = 86400000;
export const MILLIS_IN_HOUR = 3600000;

export function days(n) {
    return n * MILLIS_IN_DAY;
}

export function hours(n) {
    return n * MILLIS_IN_HOUR;
}

export function hoursFromMillis (n) {
    return n / MILLIS_IN_HOUR;
}

export function minutes(n) {
    return n * 60000;
}

export function seconds(n) {
    return n * 1000;
}

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

//hour12: false does show 24:xx on Chrome

const defaultDateTimeFormat = new Intl.DateTimeFormat('en-US', {
    timeZone: 'UTC',
    month: '2-digit',
    day: '2-digit',
    year: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
    timeZoneName: 'short'
});

const defaultDateHMTimeFormat = new Intl.DateTimeFormat('en-US', {
    timeZone: 'UTC',
    month: '2-digit',
    day: '2-digit',
    year: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZoneName: 'short'
});

const defaultLocalDateTimeFormat = new Intl.DateTimeFormat('en-US', {
    month: '2-digit',
    day: '2-digit',
    year: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
});

const defaultLocalDateHMTimeFormat = new Intl.DateTimeFormat('en-US', {
    month: '2-digit',
    day: '2-digit',
    year: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
});

const defaultLocalMDDateFormat = new Intl.DateTimeFormat('en-US', {
    month: '2-digit',
    day: '2-digit'
});

const defaultTimeFormat = new Intl.DateTimeFormat('en-US', {
    timeZone: 'UTC',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    hourCycle: 'h23',
    timeZoneName: 'short'
});

const defaultLocalTimeFormat = new Intl.DateTimeFormat('default', {
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    hourCycle: 'h23'
});

const defaultLocalHMTimeFormat = new Intl.DateTimeFormat('default', {
    hour: 'numeric',
    minute: 'numeric',
    hourCycle: 'h23'
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

export function toFormattedDateTimeString (d,fmt) {
    return (d) ? fmt.format(d) : "-";
}

export function toDateTimeString(d) {
    return toFormattedDateTimeString(d, defaultDateTimeFormat);
}

export function toDateHMTimeString(d) {
    return toFormattedDateTimeString(d, defaultDateHMTimeFormat);
}

export function toLocalDateTimeString(d) {
    return toFormattedDateTimeString(d, defaultLocalDateTimeFormat);
}

export function toLocalDateHMTimeString(d) {
    return toFormattedDateTimeString(d, defaultLocalDateHMTimeFormat);
}

export function toTimeString(d) {
    return toFormattedDateTimeString(d, defaultTimeFormat);
}

export function toLocalTimeString(d) {
    return toFormattedDateTimeString(d, defaultLocalTimeFormat);
}

export function toLocalHMTimeString(d) {
    return toFormattedDateTimeString(d, defaultLocalHMTimeFormat);
}

export function toLocalMDHMString(d) {
    if (d) {
        return defaultLocalMDDateFormat.format(d) + " " + defaultLocalHMTimeFormat.format(d);
    } else return "-";
}

export function isUndefinedDateTime(d) {
    return d == Number.MIN_SAFE_INTEGER;
}

//--- string interning support

const _uiInterned = new Map();

export function intern(s) {
    let sInterned = _uiInterned.get(s);
    if (!sInterned) {
        _uiInterned.set(s, s);
        return s;
    } else {
        return sInterned;
    }
}

export function prependElement(e, array) {
    var newArray = array.slice();
    newArray.unshift(e);
    return newArray;
}

//--- type tests

export function isNumber(v) {
    return Number.isFinite(v);
}

export function isString(v) {
    return typeof v === 'string';
}

//--- geo & math

const e2_wgs84 = 0.00669437999014;
const a_wgs84 = 6378137.0;
const mrcNom_wgs84 = a_wgs84 * (1.0 - e2_wgs84);

const rad2deg = 180.0 / Math.PI;

export function toRadians(deg) {
    return deg / rad2deg;
}

export function toDegrees(rad) {
    return rad * rad2deg;
}

export function sin2(rad) {
    let x = Math.sin(rad);
    return x * x;
}

export function meanRadiusOfCurvature(latDeg) {
    return mrcNom_wgs84 / Math.pow(1.0 - e2_wgs84 * sin2(toRadians(latDeg)), 1.5);
}

export function deltaDeg(latDeg, length) {
    return length / meanRadiusOfCurvature(latDeg);
}

export function roundToNearest(x, d) {
    return Math.round(x / d) * d;
}

export function formatLatLon(latDeg, lonDeg, digits) {
    let fmt = f_N[digits];
    return fmt.format(latDeg) + " " + fmt.format(lonDeg);
}

export function countMatching(array, pred) {
    return array.reduce((acc, e) => pred(e) ? acc + 1 : acc, 0);
}