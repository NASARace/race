import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

class HotspotEntry {
    constructor(hs) {
        this.hotspots = hs.hotspots;
        this.date = hs.hotspots[0].date;
    }
}

var hotspotEntries = [];
var hotspotView = undefined;
var timeSteps = undefined;
var tempThreshold = undefined;
var frpThreshold = undefined;
var resolution = 0.0002;

var pixelPrimitive = undefined;
var tempPrimitive = undefined;

ui.registerLoadFunction(function initialize() {
    hotspotView = initHotspotView();
    initHotspotSliders();
    ws.addWsHandler(config.wsUrl, handleWsHotspotMessages);

    timeSteps = config.hotspot.timeSteps;
    tempThreshold = config.hotspot.temp;
    frpThreshold = config.hotspot.frp;
    resolution = config.hotspot.resolution;

    console.log("ui_cesium_hotspot initialized");
});

function initHotspotView() {
    let view = ui.getList("hotspot.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "size", width: "4rem", attrs: ["fixed", "alignRight"], map: e => e.hotspots.length },
            { name: "date", width: "13rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalDateTimeString(e.date) }
        ]);
    }
    return view;
}

function initHotspotSliders() {
    let e = ui.getSlider('hotspot.history');
    ui.setSliderRange(e, 0, 7, 1, util.f_0);
    ui.setSliderValue(e, config.hotspot.history);

    e = ui.getSlider('hotspot.resolution');
    ui.setSliderRange(e, 0.0000, 0.003, 0.0001, util.fmax_4);
    ui.setSliderValue(e, config.hotspot.resolution);
}


function handleWsHotspotMessages(msgType, msg) {
    switch (msgType) {
        case "hotspots":
            handleHotspotsMessage(msg.hotspots);
            return true;
        case "dropHotspots":
            handleDropHotspotsMessage(msg.dropHotspots);
            return true;
        default:
            return false;
    }
}

function setPixelGrid(pix) {
    if (resolution) {
        let glat = util.roundToNearest(pix.lat, resolution);
        let glon = util.roundToNearest(pix.lon, resolution);

        let s = util.fmax_4.format(glat) + ',' + util.fmax_4.format(glon);
        let id = util.intern(s); // we don't want gazillions of duplicate strings

        pix.glat = glat;
        pix.glon = glon;
        pix.id = id;
    } else {
        pix.glat = undefined;
        pix.glon = undefined;
        pix.id = undefined;
    }
}

function handleHotspotsMessage(hs) {
    if (resolution) {
        hs.hotspots.forEach(pix => setPixelGrid(pix));
    }

    let hsEntry = new HotspotEntry(hs);
    addHotspotEntry(hsEntry);
}

function handleDropHotspotsMessage(date) {
    console.log("drop hotspots: " + date);
}

// we add the latest on top
function addHotspotEntry(e) {
    let d = e.date;
    for (let i = 0; i < hotspotEntries.length; i++) {
        if (d >= hotspotEntries[i].date) {
            hotspotEntries.splice(i, 0, e);
            ui.insertListItem(hotspotView, e, i);
            return;
        }
    }

    hotspotEntries.push(e);
    ui.appendListItem(hotspotView, e);
}

function getCutoffTime(hsIdx) {
    let dateCut = 0;
    let dt = config.hotspot.history * 86400000; // history is configured as days, hotspot date is msec

    let utcClock = ui.getClock("time.utc");
    if (utcClock) { // if we have a UTC clock use current clock time as basis for cutoff
        return ui.getClockEpochMillis(utcClock) - dt;

    } else {
        let e = hotspotEntries[hsIdx];
        return e.date - dt;
    }
}

function computeHotspotPixels(hsIdx) {
    let pixels = [];
    let seen = new Set();
    let cutoff = getCutoffTime(hsIdx);

    for (var i = hsIdx; i < hotspotEntries.length; i++) { // hotspot entries are ordered latest-first
        let e = hotspotEntries[i];
        if (e.date > cutoff) {
            let hs = e.hotspots;
            for (var j = 0; j < hs.length; j++) {
                let pix = hs[j];
                let k = pix.id;
                if (k) {
                    if (!seen.has(k)) {
                        seen.add(k);
                        pixels.push(pix);
                    }
                } else {
                    pixels.push(pix);
                }
            }
        } else {
            break;
        }
    }

    return pixels;
}

function createPixelAssets(pixels) {
    let refDate = pixels[0].date;
    let lastDate = undefined;
    let clr = undefined;
    let clrAttr = undefined;

    clearPrimitives();
    let geoms = [];
    let points = [];

    for (var i = 0; i < pixels.length; i++) {
        let pix = pixels[i];

        if (pix.date != lastDate) {
            clr = getPixelColor(pix, refDate);
            clrAttr = Cesium.ColorGeometryInstanceAttribute.fromColor(clr);
            lastDate = pix.date;
        }

        if (clr) {
            // TODO - should we use glat/glon if defined ? 
            let lat = pix.lat;
            let lon = pix.lon;

            let geom = new Cesium.GeometryInstance({
                geometry: new Cesium.CircleGeometry({
                    center: Cesium.Cartesian3.fromDegrees(lon, lat),
                    radius: pix.size / 2,
                    granularity: pixGranularity,
                    vertexFormat: Cesium.VertexFormat.POSITION_ONLY
                }),
                attributes: {
                    color: clrAttr
                }
            })
            geoms.push(geom);

            if (clr === timeSteps[0].color && pix.temp > tempThreshold.value) {
                let point = {
                    position: Cesium.Cartographic.fromDegrees(lon, lat),
                    pixelSize: 3,
                    color: tempThreshold.color
                };
                if (pix.frp > frpThreshold.value) {
                    point.outlineWidth = 1.0;
                    point.outlineColor = frpThreshold.color;
                }
                points.push(point);
            }
        }
    }

    if (geoms.length > 0) {
        pixelPrimitive = new Cesium.Primitive({
            geometryInstances: geoms,
            allowPicking: false,
            asynchronous: true,
            releaseGeometryInstances: true,
            
            appearance: new Cesium.MaterialAppearance({
                faceForward: true,
                flat: true,
                translucent: false,
                //renderState: { depthTest: { enabled: false, } }, // this makes it appear always on top but translucent
            }),
        });        
        uiCesium.addPrimitive(pixelPrimitive);
    }

    if (points.length > 0) {
        let positions = points.map(e => e.position);
        uiCesium.withSampledTerrain(positions, 11, (updatedPositions) => {
            tempPrimitive = new Cesium.PointPrimitiveCollection({
                blendOption: Cesium.BlendOption.OPAQUE
            });

            for (var i = 0; i < updatedPositions.length; i++) {
                let pt = points[i];
                let pos = updatedPositions[i];
                pt.position = Cesium.Cartesian3.fromRadians(pos.longitude, pos.latitude, 10);
                tempPrimitive.add(pt);
            }

            uiCesium.addPrimitive(tempPrimitive);
        });
    }

    uiCesium.requestRender();
}

const pixGranularity = Cesium.Math.toRadians(5);

function getPixelColor(pixel, refDate) {
    let dt = util.hoursFromMillis(refDate - pixel.date);
    for (var i = 0; i < timeSteps.length; i++) {
        let ts = timeSteps[i];
        if (dt < ts.value) {
            return ts.color;
        }
    }

    return timeSteps[timeSteps.length - 1].color;
}

function clearPrimitives() {
    if (pixelPrimitive) uiCesium.removePrimitive(pixelPrimitive);
    pixelPrimitive = undefined;

    if (tempPrimitive) uiCesium.removePrimitive(tempPrimitive);
    tempPrimitive = undefined;
}

function showPrimitives(isVisible) {
    if (tempPrimitive) tempPrimitive.show = isVisible;
    if (pixelPrimitive) pixelPrimitive.show = isVisible;
    uiCesium.requestRender();
}

//--- interaction

ui.exportToMain(function toggleShowHotspots(event) {
    showPrimitives(ui.isCheckBoxSelected(event.target));
});

ui.exportToMain(function setHotspotHistory(event) {

});

function showPixels(selIdx) {
    if (selIdx >= 0) {
        let pixels = computeHotspotPixels(selIdx);
        //if (pixels.length > 100) uiCesium.lowerFrameRateFor(pixels.length * 5, 5);
        createPixelAssets(pixels);
    }
}

ui.exportToMain(function selectHotspotTime(event) {
    showPixels(ui.getSelectedListItemIndex(hotspotView));
});

ui.exportToMain(function earliestHotspotTime(event) {
    ui.selectLastListItem(hotspotView);
});

ui.exportToMain(function earlierHotspotTime(event) {
    ui.selectNextListItem(hotspotView);
});

ui.exportToMain(function laterHotspotTime(event) {
    ui.selectPrevListItem(hotspotView);
});

ui.exportToMain(function latestHotspotTime(event) {
    ui.selectFirstListItem(hotspotView);
});

ui.exportToMain(function clearHotspots() {
    ui.clearSelectedListItem(hotspotView);
    clearPrimitives();
    uiCesium.requestRender();
});

ui.exportToMain(function resetDisplayParams() {
    timeSteps = structuredClone(config.hotspot.timeSteps);
    tempThreshold = structuredClone(config.hotspot.temp);
    frpThreshold = structuredClone(config.hotspot.frp);
});

ui.exportToMain(function setHotspotResolution(event) {
    let v = ui.getSliderValue(event.target);
    resolution = v;
    hotspotEntries.forEach(e => {
        e.hotspots.forEach(pix => setPixelGrid(pix));
    });

    if (pixelPrimitive) {
        showPixels(ui.getSelectedListItemIndex(hotspotView));
    }
});