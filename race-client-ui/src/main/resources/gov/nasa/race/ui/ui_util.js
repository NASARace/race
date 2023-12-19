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
    return (area * 0.000247105381);
}

export function squareKilometersToAcres(area) {
    return (area * 247.105381);
}

export function metersToUsMiles (len) {
    return len / 1609.344;
}

export function metersToNauticalMiles (len) {
    return len / 1852;
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

function toZeroPaddedString(num,len=2) {
    return num.toString().padStart(len,'0');
}

// YYYY-MM-DD-HHmm (datetime in filesystem compatible encoding)
export function toYYYYMMDDhhmmZString(timestamp,withSeconds=false) {
    let date = new Date(timestamp);

    let ts = date.getUTCFullYear().toString();
    ts += '-';
    ts += toZeroPaddedString(date.getUTCMonth()+1);
    ts += '-';
    ts += toZeroPaddedString(date.getUTCDate());
    ts += '-';
    ts += toZeroPaddedString(date.getUTCHours());
    ts += toZeroPaddedString(date.getUTCMinutes());

    if (withSeconds) ts += toZeroPaddedString(date.getUTCSeconds());

    return ts;
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

const defaultLocalDateFormat = new Intl.DateTimeFormat('en-US', {
    month: '2-digit',
    day: '2-digit',
    year: '2-digit'
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

export function toLocalDateString(d) {
    return toFormattedDateTimeString(d, defaultLocalDateFormat);
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

export function toLocalMDHMSString(d) {
    if (d) {
        return defaultLocalMDDateFormat.format(d) + " " + defaultLocalTimeFormat.format(d);
    } else return "-";
}

export function isUndefinedDateTime(d) {
    return d == Number.MIN_SAFE_INTEGER;
}

export function dayOfYear (d) {
    let date = (typeof d === "object") ? d : new Date(d);
    return (Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) - Date.UTC(date.getFullYear(), 0, 0)) / 24 / 60 / 60 / 1000;
}

export function hoursBetween (d1,d2) {
    return (d2 - d1) / 3600000;
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

export function isDefined(v) {
    return !(typeof v === 'undefined');
}

export function isNumber(v) {
    return Number.isFinite(v);
}

export function isString(v) {
    return typeof v === 'string';
}

//--- geo & math

const meanEarthRadius = 6371000.0; // in meters
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

const sin = Math.sin;
const cos = Math.cos;
const tan = Math.tan;
const sqrt = Math.sqrt;
const atan2 = Math.atan2;

export function sin2(rad) {
    let x = Math.sin(rad);
    return x * x;
}
export function cos2(rad) {
    let x = Math.cos(rad);
    return x * x;
}
export function tan2(rad) {
    let x = Math.tan(rad);
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

export function formatFloat(v, digits) {
    let fmt = f_N[digits];
    return fmt.format(v);
}

// along great circle, in meters
export function distanceBetweenGeoPos(lat1Deg,lon1Deg, lat2Deg,lon2Deg) {
    let lat1 = toRadians(lat1Deg);
    let lon1 = toRadians(lon1Deg);
    let lat2 = toRadians(lat2Deg);
    let lon2 = toRadians(lon2Deg);

    let dLat = lat2 - lat1;
    let dLon = lon2 - lon1;
    let a = sin2(dLat/2.0) + cos(lat1) * cos(lat2) * sin2(dLon/2.0);
    let c = 2.0 * atan2( sqrt(a), sqrt(1.0 - a));
    return meanEarthRadius * c;
}

//--- expandable tree



//--- misc

export function countMatching(array, pred) {
    return array.reduce((acc, e) => pred(e) ? acc + 1 : acc, 0);
}

export function firstDefined(...theArgs) {
    for (const arg of theArgs) {
        if (arg) return arg;
    }
    return undefined;
}

export function checkDefined(...theArgs) {
    var arg = undefined;
    for (arg of theArgs) {
        if (!arg) return undefined;
    }
    return arg;
}

export function haveEqualElements (array1, array2) {
    for (var i=0; i<array1.length; i++) {
        for (var j=0; j<array2.length; j++) {
            if (array1[i] == array2[j]) return true;
        }
    }
    return false;
}

export function mkString(array, sep) {
    return array.reduce( (acc,e) =>  (acc.length == 0) ? e.toString() : acc + sep + e.toString(), "");
}

export function filterIterator(it,f) {
    let matching = [];
    it.forEach( e=> {
        if (f(e)) matching.push(e);
    });
    return matching;
}

export function isWithin(x,lower,upper) {
    return (x >= lower) && (x <=upper);
}

export function getLatLonArrayBoundingRect(pts) {
    let w = Number.MAX_SAFE_INTEGER;
    let s = Number.MAX_SAFE_INTEGER;
    let e = Number.MIN_SAFE_INTEGER;
    let n = Number.MIN_SAFE_INTEGER;

    pts.forEach( p=> {
        let lat = p[0];
        let lon = p[1];
        if (lon < w) w = lon;
        if (lat < s) s = lat;
        if (lon > e) e = lon;
        if (lat > n) n = lat;
    });
    return { west: w, south: s, east: e, north: n };
}

export const EPSG_4326 = "epsg:4326";  // WGS84 geographic (lat/lon)
export const EPSG_4978 = "epsg:4978";  // WGS84 ECEF (x,y,z)

export const SRS = {
    _4326: EPSG_4326,
    GEO: EPSG_4326,
    _4978: EPSG_4978,
    ECEF:  EPSG_4978,
    //... more to follow
}

// length of longitude degree at given latitude in meters
export function lonDegMeters(lat) {
    let latitude = lat * Math.PI / 180;
    let term5 = 111412.84 * Math.cos(latitude);
    let term6 = 93.5 * Math.cos(3.0 * latitude);
    let term7 = 0.118 * Math.cos(5.0 * latitude);
    return term5 - term6 + term7;
}

// length of latitude degree at given latitude in meters
export function latDegMeters(lat) {
    let latitude = lat * Math.PI / 180;
    let term1 = 111132.92;
    let term2 = 559.82 * Math.cos(2.0 * latitude);
    let term3 = 1.175 * Math.cos(4.0 * latitude);
    let term4 = 0.0023 * Math.cos(6.0 * latitude);
    return term1 - term2 + term3 - term4;
}

export function getRectCenter (rect) {
    let x = (rect.west + rect.east)/2;
    let y = (rect.north + rect.south)/2;
    return { lat: y, lon: x};
}


//--- UTM coordinate transformation


function getUtmTransform () {
    const sin = Math.sin;
    const cos = Math.cos;
    const sinh = Math.sinh;
    const cosh = Math.cosh;
    const atan = Math.atan;
    const atanh = Math.atanh;
    const round = Math.round;
    const floor = Math.floor;
    const sqrt = Math.sqrt;

    const a = 6378.137;
    const f = 1/298.257223563;
    const n = f / (2.0 - f);
    const n2 = n * n;
    const n3 = n2 * n;
    const n4 = n2 * n2; 
    const A = (a / (1 + n)) * (1 + n2/4 + n4/64);
    const α1 = n/2 - (2/3)*n2 + (5/16)*n3;
    const α2 = (13/48)*n2 - (3/5)*n3;
    const α3 = (61/240)*n3;
    const C = (2*sqrt(n)) / (1 + n);
    const k0 = 0.9996;
    const D = k0 * A;
    const E0 = 500.0;

    // no 'I' or 'O' band
    const latBands = ["A","B","C","D","E","F","G","H","J","K","L","M","N","P","Q","R","S","T","U","V","W","X"];

    return function (latDeg,lonDeg) {
        if (latDeg < -80.0 || latDeg > 84.0) return undefined;
        let band = latBands[floor((latDeg+80)/8)];

        let φ = toRadians(latDeg);
        let λ = toRadians(lonDeg);
        let utmZone = round((lonDeg + 180) / 6);
        let λ0 = toRadians((utmZone-1)*6 - 180 + 3);
        let dλ = λ - λ0;
        let N0 = φ < 0 ? 10000 : 0;

        let sin_φ = sin(φ);
        let t = sinh( atanh(sin_φ) - C * atanh( C*sin_φ));
        let ξ = atan( t/cos(dλ));
        let η = atanh( sin(dλ) / sqrt(1 + t*t));

        let E = E0 + D*(η + (α1 * cos(2*ξ)*sinh(2*η)) + (α2 * cos(4*ξ)*sinh(4*η)) + (α3 * cos(6*ξ)*sinh(6*η)));
        let N = N0 + D*(ξ + (α1 * sin(2*ξ)*cosh(2*η)) + (α2 * sin(4*ξ)*cosh(4*η)) + (α3 * sin(6*ξ)*cosh(6*η)));

        return { utmZone: utmZone, band: band, easting: round(E*1000), northing: round(N*1000)};
    }
} 

export const latLon2Utm = getUtmTransform();


export function downSampleWithFirstAndLast (a, newLen) {
    let len = a.length;
    if (newLen > len) return a; // nothing to downsample
    let step = Math.floor(len / newLen);

    let b = Array(newLen);
    let j = 0;
    for (var i=0; i<len; i+= step) b[j++] = a[i];
    if (i > len) b[j] = a[len-1]; 

    return b;
}

export function evalProperty(p) {
    if (p) {
        return (p instanceof Function) ? p() : p;
    } else {
        return undefined;
    }
}

export function copyArrayIfSame (oldArr,newArr) {
    return (oldArr === newArr) ? [...oldArr] : newArr;
}


async function* textLineIterator (url) {
    const decoder = new TextDecoder("utf-8");
    const response = await fetch(url);
    const reader = response.body.getReader();

    let { value: chunk, done: readerDone } = await reader.read();
    chunk = chunk ? decoder.decode(chunk) : "";

    const newline = /\r?\n/gm;
    let i0 = 0;

    while (true) {
        const result = newline.exec(chunk);
        if (!result) {
            if (readerDone) break;

            const leftOver = chunk.substring(i0);
            ({ value: chunk, done: readerDone } = await reader.read());
            chunk = leftOver + (chunk ? decoder.decode(chunk) : "");
            i0 = newline.lastIndex = 0;
            continue;
        }

        yield chunk.substring(i0, result.index);
        i0 = newline.lastIndex;
    }

    if (i0 < chunk.length) { // last line had no newline
        yield chunk.substring(i0);
    }
}

export async function forEachTextLine (url, processLine, skip=0) {
    let i = 0;
    for await (const line of textLineIterator(url)) {
        if (++i > skip) processLine(line);
    }
}

export function parseCsvValue(s) {
    if (!s) {  // undefined
        return null;

    } else if (s[0] == '"') { // string
      s = s.substring(1,s.length-1);
      s = s.replaceAll('""', '"');
      return s;

    } else { // number
      return Number(s);
    }
}

const csvRegEx = /(?:,|\n|^)("(?:(?:"")*[^"]*)*"|[^",\n]*|(?:\n|$))/g;

export function parseCsvValues(line) {
    const regex = new RegExp(csvRegEx);
    let values = []
    var matches = null;
    while (matches = regex.exec(line)) {
        if (matches.length > 1) {
            values.push( parseCsvValue( matches[1]));
        }
    }
    return values;
}

//--- comparators

export function dateCompare (a,b) {
    let va = a.valueOf();
    let vb = b.valueOf();
    return (va < vb) ? -1 : (va == vb) ? 0 : 1;
}

export function compare (a,b) {
    return (a < b) ? -1 : (a == b) ? 0 : 1;
}

//--- filters

export function filterMapValues (map,func) {
    let list = [];
    for (const e of map.values()) {
        if (func(e)) list.push(e);
    }
    return list;
}