import * as utils from './utils.js';

export function initWebSocket(messageHandler) {
  if ("WebSocket" in window) {
    var wsUrl = getWsUrl();
    //console.log("@@@ wsUrl = " + wsUrl);
    var ws = new WebSocket(wsUrl);

    ws.onopen = function() {
      console.log(`websocket ${ws} opened`);
    };

    ws.onmessage = function (evt) {
      //console.log("got " + evt.data.toString());
      var msg = JSON.parse(evt.data.toString());
      messageHandler(msg);
    }

    ws.onclose = function() {
      console.log(`websocket ${ws} closed`);
    };

    return ws;

  } else {
    console.log("WebSocket NOT supported by your Browser!");
    return null;
  }
}

//--- internal functions

function getWsUrl() {
  var loc = document.location;
  var prot = (loc.protocol == "https:") ? "wss://" : "ws://";

  return (prot + loc.host + utils.parentOfPath(loc.pathname) + "/ws");
}
