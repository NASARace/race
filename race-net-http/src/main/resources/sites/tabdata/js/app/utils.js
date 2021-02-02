/**
  * utility functions used in other tabdata modules
  *
  * this file should not import any non-3rd party code to avoid circular dependencies
  */

export function isEmpty(o) {
    return (!o || 0 === o.length);
}

export function toUtf8Array(str) {
  var utf8 = [];
  for (var i=0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c < 0x80) utf8.push(c);
    else if (c < 0x800) {
      utf8.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c < 0xd800 || c >= 0xe000) {
      utf8.push(0xe0 | (c >> 12), 0x80 | ((c>>6) & 0x3f),  0x80 | (c & 0x3f));
    } else {
        i++;
        c = ((c & 0x3ff)<<10) | (str.charCodeAt(i) & 0x3ff)
        utf8.push(0xf0 | (c >>18), 0x80 | ((c>>12) & 0x3f), 0x80 | ((c>>6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return utf8;
}

export function removeAllChildren (elem) {
  while (elem.firstChild){
    elem.removeChild(elem.firstChild);
  }
}

export function timeString(date) {
  var h = ('0' + date.getHours()).slice(-2);
  var m = ('0' + date.getMinutes()).slice(-2);
  var s = ('0' + date.getSeconds()).slice(-2);

  return h + ':' + m + ':' + s;
}


export function nameOfPath (path) {
  var i = path.lastIndexOf('/');
  if (i >= 0) {
    if (i == path.length - 1) return ''; // ends with '/' - no name
    return path.substring(i+1);
  } else {
    return path; // no path elements
  }
}

export function parentOfPath (path) {
  var i = path.lastIndexOf('/');
  if (i > 0) {
    return path.substring(0,i);
  } else if (i == 0) {
    return "/";
  } else {
    return "";
  }
}

export function levelOfPath (path) {
  var level = 1;
  var len = path.length;
  if (len > 1) {
    var i = path[0] === '/' ? 1 : 0;   // leading '/' don't count
    if (path[len-1] === '/') len--; // trailing '/' don't count
    for (; i < len; i++) {
      if (path[i] === '/') level++;
    }
  }
  return level;
}

export function isAbsolutePath (path) {
  return path.length > 0 && path[0] === "/";
}

export function top (stack) {
  if (stack.length == 0) {
    return undefined;
  } else {
    return stack[stack.length-1];
  }
}

export function language() {
  return navigator.language || navigator.languages[0];
}

export function swapClass (element, oldCls, newCls) {
  element.classList.remove(oldCls);
  element.classList.add(newCls);
}

export function setAndFitToText (textInputElement, text, minLength) {
  var len = text.length;
  textInputElement.value = text;
  if (len > minLength) textInputElement.style["width"] = `${len}rem`;
}

//--- page content but not data related - used by other app modules

export function log (msg) {
  var sc = document.getElementById("logContainer");
  var logEntry = document.createElement("div");

  var span = document.createElement("span");
  span.classList.add("log-time");
  span.textContent = timeString(new Date());
  logEntry.appendChild(span);

  span = document.createElement("span");
  span.classList.add("log-msg");
  span.textContent = msg;
  logEntry.appendChild(span);

  sc.insertBefore(logEntry, sc.firstChild);
}

export function info (msg) {
  document.getElementById("info").textContent = msg;
}