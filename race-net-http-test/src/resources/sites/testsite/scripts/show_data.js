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
 function show_data() {

    /* static data */
    document.getElementById("data").innerHTML = "and the data is: 42 (what else could it be)"

    /* dynamic push
    if ("WebSocket" in window) {
       console.log("WebSocket is supported by your Browser!");
       var ws = new WebSocket("wss://localhost:8080/ws");

       ws.onopen = function() {
          ws.send("Hi server!");
          console.log("message is sent...");
       };

       ws.onmessage = function (evt) {
          var received_msg = evt.data;
          document.getElementById("data").innerHTML = received_msg;
          console.log("got message: " + received_msg);
       };

       ws.onclose = function() {
          console.log("connection is closed...");
       };
    } else {
       console.log("WebSocket NOT supported by your Browser!");
    }
    */
 }