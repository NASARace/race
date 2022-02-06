var ws = undefined;

export function initialize(wsUrl = "ws") {
    if ("WebSocket" in window) {
        console.log("initializing websocket " + wsUrl);
        ws = new WebSocket(wsUrl);

        ws.onopen = function() {
            // nothing yet
        };

        ws.onmessage = function(evt) {
            try {
                let msg = JSON.parse(evt.data.toString());
                handleServerMessage(msg);
            } catch (error) {
                console.log(error);
                //console.log(evt.data.toString());
            }
        }

        ws.onclose = function() {
            console.log("connection is closed...");
        };
    } else {
        console.log("WebSocket NOT supported by your Browser!");
    }
}

// each handler is a function that takes two parameters: (msgType,msg) and returns true if message dispatch should be shortcut
var wsHandlers = [];

export function addWsHandler(newHandler) {
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

export function shutdown() {
    ws.close();
}