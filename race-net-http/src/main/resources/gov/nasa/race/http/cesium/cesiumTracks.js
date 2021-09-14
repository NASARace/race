var viewer = undefined;
var ws = undefined;
var trackInfoDataSource = undefined;
var trajectoryDataSource = undefined;


var isConsolePanelVisible = false;
var camera = undefined;

const minLabelDepth = 300.0;

function initCesium() {
    viewer = new Cesium.Viewer('cesiumContainer', {
        imageryProvider: imageryProvider,
        terrainProvider: terrainProvider,
        skyBox: false,
        infoBox: false,
        baseLayerPicker: false,
        sceneModePicker: false,
        navigationHelpButton: false,
        homeButton: false,
        timeline: false,
        animation: false
    });

    setCanvasSize();
    window.addEventListener('resize', setCanvasSize);
    viewer.resolutionScale = 1.0;

    trackInfoDataSource = new Cesium.CustomDataSource('trackInfo');
    viewer.dataSources.add(trackInfoDataSource);

    if (trackPaths) {
      trajectoryDataSource = new Cesium.CustomDataSource('trajectory');
      trajectoryDataSource.show = false;
      viewer.dataSources.add(trajectoryDataSource);
      viewer.dataSources.lowerToBottom(trajectoryDataSource);
    }

    // event listeners
    viewer.camera.moveEnd.addEventListener( updateCamera);
    viewer.scene.canvas.addEventListener('mousemove', updateMouseLocation);
}

function updateCamera() {
  let pos = viewer.camera.positionCartographic;
  document.getElementById("altitude").innerText = Math.round(pos.height).toString();
}

function updateMouseLocation(e) {
  var ellipsoid = viewer.scene.globe.ellipsoid;
  var cartesian = viewer.camera.pickEllipsoid(new Cesium.Cartesian3(e.clientX, e.clientY), ellipsoid);
  if (cartesian) {
    var cartographic = ellipsoid.cartesianToCartographic(cartesian);
    var longitudeString = Cesium.Math.toDegrees(cartographic.longitude).toFixed(5);
    var latitudeString = Cesium.Math.toDegrees(cartographic.latitude).toFixed(5);

    document.getElementById("latitude").innerText = latitudeString;
    document.getElementById("longitude").innerText = longitudeString;
  }
}

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
       try {
         let msg = JSON.parse(evt.data.toString());
         handleServerMessage(msg);
       } catch (error) {
         console.log(error);
         console.log(evt.data.toString());
       }
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

function handleCameraMessage (newCamera) {
  camera = newCamera;
  setHomeView();
}

function handleTrackMessage (track) {
  //console.log(JSON.stringify(track));
  updateTrack(track);
}

function handleTrackListMessage (trackList) {
  for (track of trackList) {
    updateTrack(track);
  }
}

function shutdown() {
  ws.close();
}

var entityPrototype = undefined;

function removeEntities (track) {
  let id = track.label;
  viewer.entities.removeById( id);
  trackInfoDataSource.entities.remove( id);
  trajectoryDataSource.entities.remove( id);
}

function updateTrack (track) {
    // this can make use of the configured constants from config.js

    if (track.status == 4 || track.status == 8) {
      removeEntities(track);
      return;
    }

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
                disableDepthTestDistance: minLabelDepth,
                distanceDisplayCondition: trackLabelDC
            }
        });

        if (!entityPrototype) entityPrototype = e;

        viewer.entities.add(e);
    }

    updateTrackInfo(track,pos);

    if (trackPaths) {
        updateTrajectory(track);
    }
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
        disableDepthTestDistance: minLabelDepth,
        distanceDisplayCondition: trackInfoDC
      }
    });

    trackInfoEntities.add(e);
  }
}

function updateTrajectory (track) {
  var trajectoryEntities = trajectoryDataSource.entities;
  var e = trajectoryEntities.getById(track.label);

  if (e) {
    /*
    let positions = e.wall.positions.getValue();
    positions.push( Cesium.Cartesian3.fromDegrees( track.lon, track.lat, track.alt));
    e.wall.positions.setValue(positions);
    */

    let positions = e.wall.positions.valueOf();
    positions.push( Cesium.Cartesian3.fromDegrees( track.lon, track.lat, track.alt));
    e.wall.positions = positions;

  } else {
    e = new Cesium.Entity({
      id: track.label,
      wall: {
        //positions: new CustomProperty( Cesium.Cartesian3.fromDegreesArrayHeights( [track.lon, track.lat, track.alt])),
        positions: Cesium.Cartesian3.fromDegreesArrayHeights( [track.lon, track.lat, track.alt]),
        material: Cesium.Color.fromAlpha(trackColor, 0.2),
        outline: true,
        outlineColor: Cesium.Color.fromAlpha(trackColor, 0.4),
        distanceDisplayCondition: trackInfoDC
      }
    });

    trajectoryEntities.add(e);
  }
}

//--- interaction

function toggleConsolePanel () {
  let consoleBtn = document.getElementById("consoleButton");
  let console = document.getElementById("consolePanel");

  if (!isConsolePanelVisible) {
    console.style.display = "block";
    consoleBtn.innerText = "▽";
  } else {
    console.style.display = "none";
    consoleBtn.innerText = "▷";
  }

  isConsolePanelVisible = !isConsolePanelVisible
}

function setHomeView() {
  viewer.camera.flyTo({
      destination: Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat, camera.alt),
      orientation: {
          heading: Cesium.Math.toRadians(0.0),
          pitch: Cesium.Math.toRadians(-90.0),
          roll: Cesium.Math.toRadians(0.0)
      }
  });
}

function setDownView() {
  viewer.camera.flyTo({
    destination: viewer.camera.positionWC,
    orientation: {
        heading: Cesium.Math.toRadians(0.0),
        pitch: Cesium.Math.toRadians(-90.0),
        roll: Cesium.Math.toRadians(0.0)
    }
  });
}

function toggleTrajectories() {
  let btn = document.getElementById("trajectoryToggle");

  if (trajectoryDataSource.show){
    trajectoryDataSource.show = false;
    btn.style.backgroundColor = "transparent";
  } else {
    trajectoryDataSource.show = true;
    btn.style.backgroundColor = "rgba(10,10,10, 0.5)";
  }
}

//--- CustomProperties

function CustomProperty(value) {
    this._value = value;
    this.isConstant = false;
    this.definitionChanged = new Cesium.Event();
}

CustomProperty.prototype.getValue = function(time, result) {
    return this._value;
};

CustomProperty.prototype.setValue = function(value) {
    this._value = value;
    this.definitionChanged.raiseEvent(this);
};

