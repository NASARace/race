import * as config from "./config.js";
import * as ui from "./ui.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";

export var viewer = undefined;
export var camera = undefined;

var mapLayerView = undefined;
var selectedMapLayer = undefined;
var activeBaseLayer = undefined;

export function initialize() {
    Cesium.Ion.defaultAccessToken = config.cesiumAccessToken;

    viewer = new Cesium.Viewer('cesiumContainer', {
        //imageryProvider: config.imageryProvider, //config.imageryProviders[0].provider,
        terrainProvider: config.terrainProvider,
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

    viewer.resolutionScale = window.devicePixelRatio; // 2.0
    viewer.scene.fxaa = true;

    // event listeners
    viewer.camera.moveEnd.addEventListener(updateCamera);
    viewer.scene.canvas.addEventListener('mousemove', updateMouseLocation);

    ws.addWsHandler(handleWsViewMessages);

    initImageryLayers();
    initViewWindow();

    return true;
}

function initImageryLayers() {
    let imageryLayers = viewer.imageryLayers;
    let layerInfos = config.imageryLayers;
    let defaultImageryLayer = imageryLayers.get(0); // Bing aerial

    layerInfos.forEach(li => {
        let layer = li.provider ? new Cesium.ImageryLayer(li.provider) : defaultImageryLayer;
        li.layer = layer;

        layer.alpha = li.display[0];
        layer.brightness = li.display[1];
        layer.contrast = li.display[2];
        layer.hue = li.display[3];
        layer.saturation = li.display[4];
        layer.gamma = li.display[5];

        if (li.show) {
            if (li.isBase) {
                if (activeBaseLayer) { // there can only be one
                    activeBaseLayer.show = false;
                    activeBaseLayer.layer.show = false;
                }
                activeBaseLayer = li;
            }
            layer.show = true;
        } else {
            layer.show = false;
        }

        if (li.provider) { // this is a new layer
            imageryLayers.add(layer);
        }
    });
}

function initViewWindow() {
    mapLayerView = initMapLayerView();
    initMapSliders();
}

function initMapLayerView() {
    let view = ui.getList("view.map.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [{
                name: "show",
                width: "2rem",
                attrs: [],
                map: e => {
                    if (e.isBase) return ui.createRadio(e.show, _toggleShowMapLayer, null);
                    else return ui.createCheckBox(e.show, _toggleShowMapLayer, null);
                }
            },
            { name: "id", width: "12rem", attrs: ["alignLeft"], map: e => e.descr }
        ]);

        ui.setListItems(view, config.imageryLayers);
    }

    return view;
}

function _showLayer(layerInfo, newState) {
    layerInfo.show = newState;
    layerInfo.layer.show = newState;
}

// note we get this before the item is selected
function _toggleShowMapLayer(event) {
    let e = ui.getSelector(event.target);
    if (e) {
        let li = ui.getListItemOfElement(e);
        if (li) {
            let show = ui.isSelectorSet(e);
            if (show) {
                if (li.isBase) { // there can only be one at a time
                    if (activeBaseLayer) {
                        let ie = ui.getNthSubElementOfListItem(mapLayerView, activeBaseLayer, 0);
                        if (ie) ui.setSelector(ie.firstChild, false);
                        _showLayer(activeBaseLayer, false);
                    }
                    activeBaseLayer = li;
                }
            }
            _showLayer(li, show)
        }
    }
}

ui.exportToMain(function selectMapLayer(event) {
    let li = ui.getSelectedListItem(mapLayerView);
    if (li) {
        selectedMapLayer = li;
        setMapSliders(li);
    }
})

function setMapSliders(li) {
    let display = li.display;
    ui.setSliderValue('view.map.alpha', display[0]);
    ui.setSliderValue('view.map.brightness', display[1]);
    ui.setSliderValue('view.map.contrast', display[2]);
    ui.setSliderValue('view.map.hue', display[3]);
    ui.setSliderValue('view.map.saturation', display[4]);
    ui.setSliderValue('view.map.gamma', display[5]);
}

function initMapSliders() {
    let e = ui.getSlider('view.map.alpha');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, 1.0);

    e = ui.getSlider('view.map.brightness');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, 1.0);

    e = ui.getSlider('view.map.contrast');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, 1.0);

    e = ui.getSlider('view.map.hue');
    ui.setSliderRange(e, 0, 360, 1, util.f_0);
    ui.setSliderValue(e, 0.0);

    e = ui.getSlider('view.map.saturation');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, 1.0);

    e = ui.getSlider('view.map.gamma');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, 1.0);
}

function setCanvasSize() {
    viewer.canvas.width = window.innerWidth;
    viewer.canvas.height = window.innerHeight;
}

export function setEntitySelectionHandler(onSelect) {
    let selHandler = new Cesium.ScreenSpaceEventHandler(viewer.scene.canvas);
    selHandler.setInputAction(onSelect, Cesium.ScreenSpaceEventType.LEFT_CLICK);
}

export function addDataSource(dataSrc) {
    viewer.dataSources.add(dataSrc);
}

export function clearSelectedEntity() {
    viewer.selectedEntity = null;
}

export function getSelectedEntity() {
    return viewer.selectedEntity;
}

export function setSelectedEntity(e) {
    viewer.selectedEntity = e;
}

//--- generic 'view' window of the UI

function handleWsViewMessages(msgType, msg) {
    switch (msgType) {
        case "camera":
            handleCameraMessage(msg.camera);
            return true;
        case "setClock":
            handleSetClock(msg.setClock);
            return true;
        default:
            return false;
    }
}

//--- websock handler funcs

function handleCameraMessage(newCamera) {
    camera = newCamera;
    setHomeView();
}

function handleSetClock(setClock) {
    ui.setClock("time.utc", setClock.time, setClock.timeScale);
    ui.setClock("time.loc", setClock.time, setClock.timeScale);
    ui.resetTimer("time.elapsed", setClock.timeScale);
    ui.startTime();
}

function updateCamera() {
    let pos = viewer.camera.positionCartographic;
    ui.setField("view.altitude", Math.round(pos.height).toString());
}

function updateMouseLocation(e) {
    var ellipsoid = viewer.scene.globe.ellipsoid;
    var cartesian = viewer.camera.pickEllipsoid(new Cesium.Cartesian3(e.clientX, e.clientY), ellipsoid);
    if (cartesian) {
        let cartographic = ellipsoid.cartesianToCartographic(cartesian);
        let longitudeString = Cesium.Math.toDegrees(cartographic.longitude).toFixed(5);
        let latitudeString = Cesium.Math.toDegrees(cartographic.latitude).toFixed(5);

        ui.setField("view.latitude", latitudeString);
        ui.setField("view.longitude", longitudeString);
    }
}


//--- user control 

export function setHomeView() {
    viewer.selectedEntity = undefined;
    viewer.trackedEntity = undefined;
    viewer.camera.flyTo({
        destination: Cesium.Cartesian3.fromDegrees(camera.lon, camera.lat, camera.alt),
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
ui.exportToMain(setHomeView);

export function setDownView() {
    viewer.camera.flyTo({
        destination: viewer.camera.positionWC,
        orientation: {
            heading: Cesium.Math.toRadians(0.0),
            pitch: Cesium.Math.toRadians(-90.0),
            roll: Cesium.Math.toRadians(0.0)
        }
    });
}
ui.exportToMain(setDownView);

export function toggleFullScreen(event) {
    ui.toggleFullScreen();
}
ui.exportToMain(toggleFullScreen);

function setImageryProvider(event, providerName) {
    let p = config.imageryProviders.find(p => p.name == providerName);
    if (p) {
        console.log("switching to " + p.name);
        viewer.imageryProvider = p.provider;
    } else {
        console.log("unknown imagery provider: " + providerName);
    }
}
ui.exportToMain(setImageryProvider);

function toggleOverlayProvider(event, providerName) {
    let provider = config.overlayProviders.find(p => p.name == providerName);
    if (provider) {
        console.log("toggle overlay" + providerName);
    } else {
        console.log("unknown overlay provider: " + providerName);
    }
}
ui.exportToMain(toggleOverlayProvider);


//--- map rendering parameters

ui.exportToMain(function setMapAlpha(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.alpha = v;
        selectedMapLayer.display[0] = v;
    }
});

ui.exportToMain(function setMapBrightness(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.brightness = v;
        selectedMapLayer.display[1] = v;
    }
});

ui.exportToMain(function setMapContrast(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.contrast = v;
        selectedMapLayer.display[2] = v;
    }
});

ui.exportToMain(function setMapHue(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.hue = (v * Math.PI) / 180; // needs radians
        selectedMapLayer.display[3] = v;
    }
});

ui.exportToMain(function setMapSaturation(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.saturation = v;
        selectedMapLayer.display[4] = v;
    }
});

ui.exportToMain(function setMapGamma(event) {
    if (selectedMapLayer) {
        let v = ui.getSliderValue(event.target);
        selectedMapLayer.layer.gamma = v;
        selectedMapLayer.display[5] = v;
    }
});