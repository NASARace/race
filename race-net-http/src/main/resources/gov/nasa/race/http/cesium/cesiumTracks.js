var viewer = undefined;
var ws = undefined;

function initCesium() {
   const stamen = new Cesium.OpenStreetMapImageryProvider({
        url: 'http://tile.stamen.com/terrain'
    });

    viewer = new Cesium.Viewer('cesiumContainer', {
        imageryProvider: stamen,
        terrainProvider: Cesium.createWorldTerrain(),
        skyBox: false,
        infoBox: false,
        timeline: false,
        animation: false
    });

    setCanvasSize();
    window.addEventListener('resize', setCanvasSize);
    viewer.resolutionScale = 2.0;
}

    //--- functions

function setCanvasSize() {
    viewer.canvas.width = window.innerWidth;
    viewer.canvas.height = window.innerHeight;
}

function initWS() {
   if ("WebSocket" in window) {
     console.log("initializing websocket " + wsURL);
     ws = new WebSocket(wsURL);

     ws.onopen = function() {
        // nothing yet
     };

     ws.onmessage = function(evt) {
       let msg = JSON.parse(evt.data.toString());
       handleServerMessage(msg);
     }

     ws.onclose = function() {
        console.log("connection is closed...");
     };
  } else {
     console.log("WebSocket NOT supported by your Browser!");
  }
}

function handleServerMessage (msg) {
   //console.log(JSON.stringify(msg));

   var msgType = Object.keys(msg)[0];  // first member name

   switch (msgType) {
     case "camera": handleCameraMessage(msg.camera); break;
     case "track": handleTrackMessage(msg.track); break;
     case "trackList": handleTrackListMessage(msg.trackList); break;
     default: console.log("ignoring unknown server message: " + msgType);
   }
}

function handleCameraMessage (camera) {
    console.log(JSON.stringify(camera));

    viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat, camera.alt),
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
        }
    });
}

function handleTrackMessage (track) {
  //console.log(JSON.stringify(track));
  //updateTrack(track);
}

function handleTrackListMessage (trackList) {
}

function shutdown() {
  ws.close();
}

function updateTrack (track) {
    let pitch = track.pitch ? track.pitch : 0.0;
    let roll = track.roll ? track.roll : 0.0;

    let pos = Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt);
    let hpr = new Cesium.HeadingPitchRoll(track.hdg, pitch, roll);
    let orientation = Cesium.Transforms.headingPitchRollQuaternion(pos, hpr);

    let e = new Cesium.Entity({
        id: track.label,
        position: pos,
        orientation: orientation,

        point: {
            pixelSize: 6,
            color: trackColor,
            //outlineColor: Cesium.Color.YELLOW,
            distanceDisplayCondition: new Cesium.DistanceDisplayCondition(60000)
        },
        billboard: {
            image: "./plane.png",
            alignedAxis: Cesium.Cartesian3.UNIT_Z,
            rotation: Cesium.Math.toRadians(-hdg),
            distanceDisplayCondition: new Cesium.DistanceDisplayCondition(1000, 60000)
        },
        model: {
            uri: "./airplane1.glb",
            color: Cesium.Color.RED,
            silhouetteColor: Cesium.Color.ORANGE,
            silhouetteAlpha: 1.0,
            silhouetteSize: 2.0,
            distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 1000)
        },
        label: {
            text: 'XYZ333',
            //scale: 0.5,
            font: "14px sans-serif",
            fillColor: Cesium.Color.YELLOW,
            showBackground: true,
            backgroundColor: new Cesium.Color(0.5, 0.5, 0.5, 0.3), // alpha does not work against model
            pixelOffset: new Cesium.Cartesian2(25, 25),
            disableDepthTestDistance: Number.POSITIVE_INFINITY,
            distanceDisplayCondition: new Cesium.DistanceDisplayCondition(0, 40000)
        }
    });

    viewer.entities.add(e);
}