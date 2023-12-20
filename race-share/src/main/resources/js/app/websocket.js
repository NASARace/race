/*
 * Copyright (c) 2023, United States Government, as represented by the
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
