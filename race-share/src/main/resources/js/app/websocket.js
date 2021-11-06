import * as utils from './utils.js';

export function initWebSocket(openHandler,messageHandler,closeHandler) {
  if ("WebSocket" in window) {
    var wsUrl = getWsUrl();
    //console.log("@@@ wsUrl = " + wsUrl);
    var ws = new WebSocket(wsUrl);

    ws.onopen = function (evt) {
      console.log(`websocket ${ws} opened`);
      openHandler(evt);
    };

    ws.onmessage = function (evt) {
      //console.log("got " + evt.data.toString());
      var msg = JSON.parse(evt.data.toString());
      messageHandler(msg);
    }

    ws.onclose = function (evt) {
      console.log(`websocket ${ws} closed`);
      closeHandler(evt);
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
