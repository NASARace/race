var viewer = undefined;
var ws = undefined;
var trackInfoDataSource = undefined;

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

    trackInfoDataSource = new Cesium.CustomDataSource('trackInfo');
    viewer.dataSources.add(trackInfoDataSource);
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
  console.log(JSON.stringify(track));
  updateTrack(track);
}

function handleTrackListMessage (trackList) {
}

function shutdown() {
  ws.close();
}

var entityPrototype = undefined;

function updateTrack (track) {
    // this can make use of the configured constants from config.js

    let hdg = Cesium.Math.toRadians(track.hdg);
    let pitch = track.pitch ? track.pitch : 0.0;
    let roll = track.roll ? track.roll : 0.0;

    let pos = Cesium.Cartesian3.fromDegrees(track.lon, track.lat, track.alt);
    let hpr = Cesium.HeadingPitchRoll.fromDegrees(track.hdg, pitch, roll);
    let orientation = Cesium.Transforms.headingPitchRollQuaternion(pos, hpr);

    var e = viewer.entities.getById(track.label);
    if (e) { // just update position and attitude
      e.position = pos;
      e.orientation = orientation;

    } else { // add a new entry
        e = new Cesium.Entity({
            id: track.label,
            position: pos,
            orientation: orientation,

            point: entityPrototype ? entityPrototype.point : {
                pixelSize: trackPoint,
                color: trackColor,
                outlineColor: Cesium.Color.YELLOW,
                distanceDisplayCondition: trackPointDC
            },
            model: entityPrototype ? entityPrototype.model : {
                uri: trackModel,
                color: trackColor,
                //silhouetteColor: Cesium.Color.ORANGE,
                //silhouetteAlpha: 1.0,
                //silhouetteSize: 2.0,
                minimumPixelSize: trackModelSize,
                distanceDisplayCondition: trackModelDC
            },
            label: {
                text: track.label,
                //scale: 0.5,
                horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
                font: trackLabelFont,
                fillColor: trackColor,
                showBackground: true,
                backgroundColor: trackLabelBackground, // alpha does not work against model
                pixelOffset: trackLabelOffset,
                disableDepthTestDistance: Number.POSITIVE_INFINITY,
                distanceDisplayCondition: trackLabelDC
            }
        });

        if (!entityPrototype) entityPrototype = e;

        viewer.entities.add(e);
    }

    updateTrackInfo(track,pos);
}

function updateTrackInfo (track,pos) {
  let fl = Math.round(track.alt * 0.00656167979) * 5;
  let hdg = Math.round(track.hdg);
  let spd = Math.round(track.spd * 1.94384449);

  let trackInfo = `FL${fl} ${hdg}° ${spd}kn`;
  let trackInfoEntities = trackInfoDataSource.entities;

  var e = trackInfoEntities.getById(track.label);
  if (e) {
    e.position = pos;
    e.label.text = trackInfo;

  } else {
    e = new Cesium.Entity({
      id: track.label,
      position: pos,

      label: {
        font: trackInfoFont,
        horizontalOrigin: Cesium.HorizontalOrigin.LEFT,
        fillColor: trackColor,
        showBackground: true,
        backgroundColor: trackLabelBackground, // alpha does not work against model
        pixelOffset: trackInfoOffset,
        disableDepthTestDistance: Number.POSITIVE_INFINITY,
        distanceDisplayCondition: trackInfoDC
      }
    });

    trackInfoEntities.add(e);
  }
}