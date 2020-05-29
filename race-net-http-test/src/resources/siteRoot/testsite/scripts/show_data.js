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