import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

const maskDesc = new Map();
maskDesc
    .set( 10, "good_fire_pixel")
    .set( 11, "saturated_fire_pixel")
    .set( 12, "cloud_contaminated_fire_pixel")
    .set( 13, "high_probability_fire_pixel")
    .set( 14, "medium_probability_fire_pixel")
    .set( 15, "low_probability_fire_pixel")
    .set( 30, "temporally_filtered_good_fire_pixel")
    .set( 31, "temporally_filtered_saturated_fire_pixel")
    .set( 32, "temporally_filtered_cloud_contaminated_fire_pixel")
    .set( 33, "temporally_filtered_high_probability_fire_pixel")
    .set( 34, "temporally_filtered_medium_probability_fire_pixel")
    .set( 35, "temporally_filtered_low_probability_fire_pixel");


function isGoodPixel(mask) { return (mask == 10 || mask == 30); }
function isSaturatedPixel(mask) { return (mask == 11 || mask == 31); }
function isCloudPixel(mask) { return (mask == 12 || mask == 32); }
function isHighPixel(mask) { return (mask == 13 || mask == 33); }
function isProbablePixel(mask) { return (mask == 13 || mask == 14 || mask == 33 || mask == 34); }

var satellites = [];
var satNames = new Map();

var dataSets = []; // complete list in ascending time (latest entries are appended)
var displayDataSets = []; // in reverse, latest on top
var dataSetView = undefined; // showing displayDataSets
var selectedDataSet = undefined;
var selectedPeerDataSet = undefined;

var hotspots = [];
var hotspotView = undefined;
var selectedHotspot = undefined;

// restorable selections
var lastSelDs = undefined;
var lastSelPeerDs = undefined;
var lastSelHs = undefined;

var historyView = undefined; // for selected hotspot
var maskLabel = undefined;

var pixelLevel = "all";  // high, probable, all
var latestOnly = true; // do we just show pixels reported in the last batch
var followLatest = config.goesr.followLatest;
var lockStep = config.goesr.lockStep;

var refDate = Number.MAX_INTEGER;

//--- display params we can change
var pointSize = config.goesr.pointSize;
var maxMissingMin = config.goesr.maxMissingMin;

function getSatelliteWithName (satName) {
    return satellites.find( sat=> sat.name == satName);
}

function getSatelliteWithId (satId) {
    return satellites.find( sat=> sat.satId == satId);
}

function getSatelliteIndex (satId) {
    for (let i=0; i<satellites.length; i++) {
        if (satellites[i].satId == satId) return i;
    }
    return -1;
}

ui.registerLoadFunction(function initialize() {
    dataSetView = initDataSetView();
    hotspotView = initHotspotView();
    historyView = initHistoryView();
    maskLabel = ui.getLabel("goesr.mask");

    ui.setCheckBox("goesr.followLatest", followLatest);
    ui.setCheckBox("goesr.lockStep", lockStep);
    ui.selectRadio( "goesr.level.all");
    initSliders();

    uiCesium.setEntitySelectionHandler(goesrSelection);
    ws.addWsHandler(config.wsUrl, handleWsGoesrMessages);

    uiCesium.initLayerPanel("goesr", config.goesr, showGoesr);
    console.log("ui_cesium_goesr initialized");
});

function initDataSetView() {
    let view = ui.getList("goesr.dataSets");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "sat", tip: "name of satellite", width: "3rem", attrs: [], map: e => e.sat.name },
            { name: "good", tip: "number of good pixels", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nGood },
            { name: "high", tip: "number of high probability fire pixels", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nHigh },
            { name: "med", tip: "number of medium probability fire pixels", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nMedium },
            { name: "low", tip: "number of low probability fire pixels", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.nLow },
            { name: "date", tip: "last report", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.date) }
        ]);
    }
    return view;
}

function initHotspotView() {
    let view = ui.getList("goesr.hotspots");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "class", tip: "classification of fire pixel", width: "3rem", attrs: ["fixed", "alignRight"], map: e => hotspotClass(e) },
            ui.listItemSpacerColumn(),
            { name: "sat", tip: "name of satellite", width: "4rem", attrs: [], map: e => satNames[e.satId] },
            { name: "lat", width: "6rem", attrs: ["fixed", "alignRight"], map: e => util.f_4.format(e.lat) },
            { name: "lon", width: "6.5rem", attrs: ["fixed", "alignRight"], map: e => util.f_4.format(e.lon) },
        ]);
    }
    return view;
}

function initSliders() {
    let e = ui.getSlider('goesr.maxMissing');
    ui.setSliderRange(e, 0, 120, 10, util.f_0);
    ui.setSliderValue(e, maxMissingMin);

    e = ui.getSlider('goesr.pointSize');
    ui.setSliderRange(e, 0, 8, 1, util.f_0);
    ui.setSliderValue(e, pointSize);
}

function hotspotClass (hs) {
    let missingMin = getMissingMin(hs);
    if (missingMin) {
        return `-${missingMin}m`;
    } else {
        switch (hs.probability) {
            case "high": return ui.createImage("goesr-asset/fire");
            case "medium": return "med";
            case "low": return "low";
            default: return "";
        }
    }
}

function initHistoryView() {
    let view = ui.getList("goesr.history");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "dqf", tip: "pixel quality flag []", width: "2rem", attrs: ["fixed", "alignRight"], map: e => e.dqf },
            { name: "mask", tip: "fire pixel classification code", width: "3rem", attrs: ["fixed", "alignRight"], map: e => e.mask },
            { name: "bright", tip: "pixel brightness [K]", width: "4rem", attrs: ["fixed", "alignRight"], map: e => isNaN(e.temp) ? "-" : Math.round(e.temp) },
            { name: "frp", tip: "fire radiative power [MW]", width: "4rem", attrs: ["fixed", "alignRight"], map: e => isNaN(e.frp) ? "-" : Math.round(e.frp) },
            { name: "area", tip: "surface area [ac]", width: "4rem", attrs: ["fixed", "alignRight"], map: e => isNaN(e.area) ? "-" : Math.round(util.squareMetersToAcres(e.area)) },
            { name: "time", width: "5rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalHMTimeString(e.date) },
        ]);
    }
    return view;
}

ui.exportToMain(function selectGoesrDataSet(event) {
    let ds = event.detail.curSelection;
    if (ds) {
        selectedDataSet = ds;
        if (lockStep) selectedPeerDataSet = getPeerDataSet(selectedDataSet);
        refDate = ds.date;

        if (ds != displayDataSets[0]) { // if we explicitly select an earlier element we disable followLatest
            followLatest = false;
            ui.setCheckBox("goesr.followLatest", false);
        }
    } else {
        selectedDataSet = undefined;
        selectedPeerDataSet = undefined;
        refDate = 0;
    }

    updateHotspots();
});

function getPeerDataSet (ds) {
    if (hasShowingPeer(ds.sat)) {
        let idx = dataSets.indexOf(ds);
        let ds1 = getPreceedingPeer(ds.satId, idx);
        let ds2 = getFollowingPeer(ds.satId, idx);

        if (ds1 && selectedDataSet.date - ds1.date < 180000) return ds1;
        else if (ds2 && ds2.date - selectedDataSet.date < 180000) return ds2;
        else return undefined;
    } else {
        return undefined;
    }
}

function hasShowingPeer (sat) {
    return satellites.find( s=> (s.satId != sat.satId) && s.show );
}

function getPreceedingPeer (satId, idx) {
    for (var i=idx-1; i>=0; i--) {
        if (dataSets[i].satId  != satId) return dataSets[i];
    }
}

function getFollowingPeer (satId, idx) {
    for (var i=idx+1; i<dataSets.length; i++) {
        if (dataSets[i].satId  != satId) return dataSets[i];
    }
}

function updateHotspots() {
    if (selectedDataSet){
        let hotspots = getHotspots();
        setEntities( hotspots);
        ui.setListItems( hotspotView, hotspots);

    } else {
        clearEntities();
        ui.clearList(hotspotView);
    }
    uiCesium.requestRender();
}

function getHotspots() {
    let list = selectedDataSet.hotspots;
    if (selectedPeerDataSet) list = list.concat(selectedPeerDataSet.hotspots);
    list = list.filter(hs=> filterPixel(hs));
    list = list.sort( (a,b) => b.center - a.center); // spatial clustering (roughly east to west)
    return list;
}

function _getHotspots () {
    let hsList = [];
    let cutoff = latestOnly ? 0 : maxMissingMin * 60000; // duration in millis

    if (selectedDataSet) {
        let hsMaps = satellites.map( sat=> new Map());
        let processed = 0;

        for (let i=0; i<displayDataSets.length; i++) {
            let ds = dataSets[i];
            let satIdx = ds.sat.satIdx;

            if (ds.date > refDate) continue; // newer than selected date
            if (ds.date < (refDate - cutoff)) {
                if (++processed == satellites.length) break; // done
            }

            let hsMap = hsMaps[satIdx];
            if (hsMap.size == 0) { // first dataset for this sat
                ds.hotspots.forEach( hs=> {
                    if (filterPixel(hs)) hsMap.set(hs.center, hs)
                });
            } else {
                ds.hotspots.forEach( hs=> {
                    if (filterPixel(hs)) {
                        if (!hsMap.has(hs.center)) hsMap.set(hs.center, hs) 
                    }
                });
            }
        }

        hsMaps.forEach( hsMap=> hsMap.forEach( (hs,key)=> hsList.push(hs)));
        hsList.sort( sortHotspots); // cluster approximation over all satellites
    }

    return hsList;
}

// approximation of a cluster function 
function sortHotspots(a, b) {
    let x = a.lat + a.lon;
    let y = b.lat + b.lon;
    return (x < y) ? -1 : 1;
}

function setEntities (hotspots) {
    //satellites.forEach( sat=> sat.dataSource.entities.removeAll());
    let now = Date.now();

    hotspots.forEach( hs=> {
        let satIdx = getSatelliteIndex(hs.satId);
        let dataSource = satellites[satIdx].dataSource;

        let e = dataSource.entities.getById(hs.center);
        if (e) { 
            updateHotspotEntity( e, hs);
            e._timeStamp = now;
        } else {
            let e = createHotspotEntity(hs);
            e._timeStamp = now;
            dataSource.entities.add( e);
        }
    });

    // clean up obsolete entities
    satellites.forEach( sat=> {
        let ec = sat.dataSource.entities;
        util.filterIterator(ec.values, e=> e._timeStamp != now).forEach( e=> {
            if (e._hotspot) e._hotspot.entity = undefined; // remove backlink
            ec.remove(e)
        });
    });

    uiCesium.requestRender();
}

function clearEntities() {
    satellites.forEach( sat=> {
        sat.dataSource.entities.values.forEach( e=> e._hotspot.entity = undefined); // remove backlinks
        sat.dataSource.entities.removeAll()
    });
}

// we only call this on same location entities, no need to update pos or polygon vertices
function updateHotspotEntity (e, hs) {
    let clr = color(hs);

    let point = e.point;
    point.pixelSize = pointSize;
    point.color = clr;
    point.outlineColor = outlineColor(hs);
    point.outlineWidth = outlineWidth(hs);

    let polygon = e.polygon;
    polygon.material = polygonMaterial(hs);
    polygon.outlineColor = clr;
    polygon.outlineWidth = outlineWidth(hs);

    e._hotspot = hs;
    hs.entity = e;
}

function createHotspotEntity (hs) {
    let clr = color(hs);

    let e = new Cesium.Entity({
        position: Cesium.Cartesian3.fromDegrees( hs.lon, hs.lat),
        point: {
            pixelSize: pointSize,
            color: clr,
            outlineColor: outlineColor(hs),
            outlineWidth: outlineWidth(hs),
            distanceDisplayCondition: config.goesr.pointDC,
            disableDepthTestDistance: Number.NEGATIVE_INFINITY
        },
        polygon: {
            hierarchy: polygon(hs),
            fill: true,
            material: polygonMaterial(hs),
            outline: true,
            outlineColor: clr,
            outlineWidth: outlineWidth(hs),
            distanceDisplayCondition: config.goesr.boundsDC,
            height: 0
                //zIndex: 1
        },
    });

    e._hotspot = hs;
    hs.entity = e; // watch out - backlink that could cause memory leak
    return e;
}

function color(hs) {
    let mask = hs.mask;
    if (isGoodPixel(mask) || ((isCloudPixel(mask) || isSaturatedPixel(mask)))) return config.goesr.goodColor;
    else if (isProbablePixel(mask)) return config.goesr.probableColor;
    else if (isSaturatedPixel(mask)) return config.goesr.saturatedColor;
    else if (isCloudPixel(mask)) return config.goesr.cloudColor;
    else return config.goesr.otherColor;
}

function polygonMaterial(hs) { // those should be translucent
    let mask = hs.mask;
    if (isGoodPixel(mask)) return config.goesr.goodFillColor;
    else if (isProbablePixel(mask)) return config.goesr.probableFillColor;
    else return config.goesr.otherFillColor;
}

function outlineColor(hs) {
    let mask = hs.mask;
    if (getMissingMin(hs)) {
        return config.goesr.missingColor;
    } else {
        if (isGoodPixel(mask)) return config.goesr.goodOutlineColor;
        else if (isCloudPixel(mask)) return config.goesr.cloudColor;
        else if (isSaturatedPixel(mask)) return config.goesr.saturatedColor;
        else if (isProbablePixel(mask)) return config.goesr.probableOutlineColor;
        else return config.goesr.otherColor;
    }
}

function getMissingMin(hs) {
    let diffMin = Math.round((refDate - hs.date) / 60000); // diff in minutes since last update 
    return (diffMin < 5) ? 0 : Math.round(diffMin / 5) * 5; // report in 5min steps (update interval)
}

function outlineWidth(hs) {
    if (isGoodPixel(hs.mask)) return config.goesr.strongOutlineWidth; // make this more prominent
    else return config.goesr.outlineWidth;
}

function polygon (hs) {
    let vertices = hs.bounds.map(p => new Cesium.Cartesian3.fromDegrees(p[1], p[0]));
    return new Cesium.PolygonHierarchy(vertices);
}

function getHotspotHistory (hs) {
    let center = hs.center;
    let hist = [];
    dataSets.forEach( ds=> {
        if (ds.sat.satId == hs.satId && ds.date <= hs.date) {
            ds.hotspots.forEach( h=> {
                if (h.center == center) hist.push(h);
            })
        }
    });
    return hist;
}

ui.exportToMain(function toggleGoesrLatestOnly(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        latestOnly = ui.isCheckBoxSelected(cb);
        updateHotspots();
    }
});

function goesrSelection() {
    let sel = uiCesium.getSelectedEntity();
    if (sel && sel._hotspot) {
        let hs = sel._hotspot;
        if (selectedHotspot != hs) {
            ui.setSelectedListItem(hotspotView, hs);
        }
    }
}

//--- data messages

function handleWsGoesrMessages(msgType, msg) {
    switch (msgType) {
        case "goesrSatellites":
            handleGoesrSatellites(msg.goesrSatellites);
            return true;
        case "goesrDataSet":
            handleGoesrDataSet(msg.goesrDataSet);
            return true;
        default:
            return false;
    }
}

function handleGoesrSatellites(goesrSatellites) {
    satellites = goesrSatellites;
    var idx = 0;
    satellites.forEach( sat=> {
        ui.setCheckBox("goesr." + sat.name, sat.show);
        satNames[sat.satId] = sat.name;
        sat.satIdx = idx++;
        sat.dataSource = new Cesium.CustomDataSource("goesr-" + sat.name);
        uiCesium.addDataSource(sat.dataSource);
    });
}

function handleGoesrDataSet (dataSet) {
    dataSet.sat = getSatelliteWithId(dataSet.satId);
    if (dataSet.sat) {
        dataSets.push(dataSet);

        saveSelections();
        updateDataSets();

        let now = ui.getClockEpochMillis("time.utc"); // we don't want to do this during init of history
        if (followLatest && Math.abs(now - dataSet.date) < 30000) {
            ui.selectFirstListItem(dataSetView);
        } else {
            restoreSelections();
        }
    }
}

function saveSelections() {
    lastSelDs = selectedDataSet;
    lastSelPeerDs = selectedPeerDataSet;
    lastSelHs = selectedHotspot;
}

function restoreSelections() {
    if (lastSelDs) ui.setSelectedListItem(dataSetView, lastSelDs);
    if (lastSelHs) ui.selSelectedListItem(hotspotView, lastSelHs);
}

function withRestoredSelections(cond, f) {
    if (cond) saveSelections();
    f();
    if (cond) restoreSelections();
}

function updateDataSets() {
    displayDataSets = dataSets.filter( ds=> ds.sat.show).reverse(); // we should really use a list here
    ui.setListItems( dataSetView, displayDataSets);
}


function filterPixel(hs) {
    return (pixelLevel == "all" ||
        (pixelLevel == "high" && isHighPixel(hs.mask)) ||
        (pixelLevel == "probable" && isProbablePixel(hs.mask)));
}

ui.exportToMain(function toggleShowGoesrSatellite(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let satName = ui.getCheckBoxLabel(cb)
        let se = getSatelliteWithName(satName);
        if (se) {
            se.show = !se.show            
            let restoreSel = selectedDataSet && (selectedDataSet.satId != se.satId);
            if (restoreSel) saveSelections();

            updateDataSets();
            if (restoreSel && lastSelDs) ui.setSelectedListItem(dataSetView, lastSelDs);

            updateHotspots();
        }
    }
});

function showGoesr(cond) {
    // we don't want to change the hotspotEntry show, just make the assets disappear
    satellites.forEach( sat=> {
        sat.dataSource.show = cond;
    });
    uiCesium.requestRender();
}

ui.exportToMain(function setGoesrPixelLevel(event) {
    pixelLevel = ui.getRadioLabel(event.target);  // high, probable, all
    clearEntities();
    updateHotspots();
});

ui.exportToMain(function selectGoesrHotspot(event) {
    let hs = event.detail.curSelection;
    if (hs) {
        ui.setListItems(historyView, getHotspotHistory(hs));
    } else {
        ui.clearList(historyView);
    }
    ui.setLabelText(maskLabel, null);
});

ui.exportToMain(function selectGoesrHistory(event) {
    let hs = event.detail.curSelection;
    ui.setLabelText(maskLabel, hs ? getMaskDescription(hs.mask) : null);
});

function getMaskDescription(mask) {
    let desc = maskDesc.get(mask);
    return desc ? desc : "";
}

ui.exportToMain(function zoomToGoesrHotspot(event) {
    let lv = ui.getList(event);
    if (lv) {
        let hs = ui.getSelectedListItem(lv);
        if (hs) {
            uiCesium.zoomTo(Cesium.Cartesian3.fromDegrees(hs.lon, hs.lat, config.goesr.zoomHeight));
            if (hs.entity) uiCesium.setSelectedEntity(hs.entity);
        }
    }
});

ui.exportToMain(function setGoesrMaxMissing(event) {
});

ui.exportToMain(function setGoesrPointSize(event) {
    pointSize = ui.getSliderValue(event.target);
    satellites.forEach( sat=>{
        if (sat.dataSource) {
            sat.dataSource.entities.values.forEach( e=> {
                if (e.point) e.point.pixelSize = pointSize;
            })
        }
    });
    uiCesium.requestRender();
});

ui.exportToMain(function toggleFollowLatestGoesr(event) {
    followLatest = ui.isCheckBoxSelected(event.target);
    if (followLatest && ui.getSelectedListItemIndex(dataSetView) != 0) {
        ui.selectFirstListItem(dataSetView);
    }
});

ui.exportToMain(function toggleGoesrLockStep(event) {
    lockStep = ui.isCheckBoxSelected(event.target);
    updateHotspots();
});

ui.exportToMain(function toggleShowGoesr(event) {
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        showGoesr( ui.isCheckBoxSelected(cb));
    }
});