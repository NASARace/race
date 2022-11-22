// TODO - should we support multiple web sockets per document ? 

var ws = undefined;
var wsUrl = undefined;
var isShutdown = false;

window.addEventListener('load', e => {
    setTimeout(() => {
        initialize()
    }, 0); // make sure this load handler runs last;
});

window.addEventListener('unload', shutdown);

export function initialize() {
    if ("WebSocket" in window) {
        console.log("initializing websocket: " + wsUrl);

        if (wsUrl) {
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
            console.log("no WebSocket url set");
        }
    } else {
        console.log("WebSocket NOT supported by your Browser!");
    }
}

// each handler is a function that takes two parameters: (msgType,msg) and returns true if message dispatch should be shortcut
var wsHandlers = [];

export function addWsHandler(url, newHandler) {
    wsUrl = url;
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