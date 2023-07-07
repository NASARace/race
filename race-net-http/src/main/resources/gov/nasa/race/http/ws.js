import * as config from "./config.js";

// TODO - should we support multiple web sockets per document ? if so we have to keep a map url->handler

var ws = undefined;
var wsUrl = config.ws.url;
var isShutdown = false;

// each handler is a function that takes two parameters: (msgType,msg) and returns true if message dispatch should be shortcut
var wsHandlers = [];

window.addEventListener('unload', shutdown);

// execute after all js modules have initialized to make sure handlers have been set
// this is crucial for modules that get initialization data through the ws - as soon as we are connected this is sent by the server
export function postExec() {
    if (wsUrl) {
        if ("WebSocket" in window) {
            console.log("initializing websocket: " + wsUrl);            
            ws = new WebSocket(wsUrl);

            ws.onopen = function() {
                // nothing yet
            };

            ws.onmessage = function(evt) {
                try {
                    let data = evt.data.toString();
                    let msg = JSON.parse(data);
                    handleServerMessage(msg);
                } catch (error) {
                    console.log(error);
                    console.log(evt.data.toString());
                }
            };

            ws.onerror = function(evt) {
                if (!isShutdown) {
                    console.log('websocket error: ', evt);
                    // TODO - if we get a 'WebSocket is already in CLOSING or CLOSED state' outside of a shutdown we should try to reconnect
                }
            };

            ws.onclose = function() {
                console.log("connection is closed...");
            };

        } else {
            console.log("WebSocket NOT supported by your Browser!");
        }
    } else {
        console.log("no WebSocket url set");
    }
}

export function addWsHandler(newHandler, url = wsUrl) {
    wsHandlers.push(newHandler);
}

function handleServerMessage(msg) {
    //console.log(JSON.stringify(msg));

    var msgType = Object.keys(msg)[0]; // first member name

    for (let i = 0; i < wsHandlers.length; i++) {
        if (wsHandlers[i](msgType, msg)) return;
    }
    // if we get here the message was ignored
}

export function sendWsMessage (data) {
    ws.send(data);
}

export function shutdown() {
    isShutdown = true;
    ws.close();
}