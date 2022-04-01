import * as config from "./config.js";
import * as ws from "./ws.js";
import { SkipList, CircularBuffer } from "./ui_data.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";


var sentinelView = undefined;
var sentinelEntries = new Map();
var sentinelList = new SkipList( // id-sorted display list for trackEntryView
    3, // max depth
    (a, b) => a.id < b.id, // sort function
    (a, b) => a.id == b.id // identity function
);

class SentinelAssets {
    constructor(symbol) {
        this.symbol = symbol; // billboard
    }
}

class SentinelEntry {
    constructor(sentinel) {
        this.id = sentinel.id;
        this.sentinel = sentinel;
        this.status = " ";

        this.assets = null;
    }

}

export function initialize() {
    sentinelView = initSentinelView();

    //uiCesium.setEntitySelectionHandler(trackSelection);
    ws.addWsHandler(handleWsSentinelMessages);

    return true;
}

function initSentinelView() {
    let view = ui.getList("sentinel.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "show", width: "1rem", attrs: [], map: e => e.status },
            { name: "id", width: "4rem", attrs: ["alignLeft"], map: e => e.id },
            {
                name: "date",
                width: "6rem",
                attrs: ["fixed", "alignRight"],
                map: e => util.toLocalTimeString(e.sentinel.date)
            }
        ]);
    }
    return view;
}

function handleWsSentinelMessages(msgType, msg) {
    switch (msgType) {
        case "sentinel":
            handleSentinelMessage(msg.sentinel);
            return true;
        default:
            return false;
    }
}

function handleSentinelMessage(sentinel) {
    //console.log(JSON.stringify(sentinel));

    let e = sentinelEntries.get(sentinel.id)
    if (e) {
        updateSentinelEntry(e, sentinel);
    } else {
        addSentinelEntry(sentinel);
    }
}

function addSentinelEntry(sentinel) {
    let e = new SentinelEntry(sentinel);

    sentinelEntries.set(sentinel.id, e);
    let idx = sentinelList.insert(e);
    ui.insertListItem(sentinelView, e, idx);

    if (sentinel.gps) e.assets = createAssets(sentinel);
}

function updateSentinelEntry(e, sentinel) {
    let old = e.sentinel;

    e.sentinel = sentinel;
    ui.updateListItem(sentinelView, e);

    if (!old.gps && sentinel.gps) e.assets = createAssets(sentinel);
}

function createAssets(sentinel) {
    let gps = sentinel.gps;
    if (gps) {
        console.log("@@ add entity")
        let entity = new Cesium.Entity({
            id: sentinel.id,
            position: Cesium.Cartesian3.fromDegrees(gps.longitude, gps.latitude, 0),
            billboard: {
                image: 'sentinel-asset/sentinel',
                distanceDisplayCondition: config.sentinelBillboardDC,
                color: config.sentinelColor,
                heightReference: Cesium.HeightReference.CLAMP_TO_GROUND,
            }
        });
        uiCesium.viewer.entities.add(entity);
        return entity;

    } else {
        return null;
    }

}