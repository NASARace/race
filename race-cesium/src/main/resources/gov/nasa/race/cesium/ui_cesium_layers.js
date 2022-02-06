// cesium KML layer module
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import { SkipList } from "./ui_data.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

var layerView = undefined;
var layerEntries = new SkipList( // id-sorted display list for trackEntryView
    3, // max depth
    (a, b) => a.id < b.id, // sort function
    (a, b) => a.id == b.id // identity function
);

class LayerEntry {
    constructor(id, date, url) {
        this.id = id;
        this.date = date; // last change of trackEntries
        this.url = url;

        this.show = true;
        this.dataSource = undefined; // set upon XMLHttpRequest completion
    }
}

export function initialize() {
    layerView = initLayerView();

    ws.addWsHandler(handleWsLayerMessages);

    return true;
}

function initLayerView() {
    let view = ui.getList("layers.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "show", width: "1rem", attrs: [], map: e => e.show ? "●" : "" },
            { name: "id", width: "10rem", attrs: ["alignLeft"], map: e => e.id },
            {
                name: "date",
                width: "6rem",
                attrs: ["fixed", "alignRight"],
                map: e => {
                    (util.isUndefinedDateTime(e.date)) ? "" : util.toLocalTimeString(e.date);
                }
            }
        ]);
    }
    return view;
}

function handleWsLayerMessages(msgType, msg) {
    switch (msgType) {
        case "layer":
            handleLayerMessage(msg.layer);
            return true;
        default:
            return false;
    }
}

function handleLayerMessage(layer) {

    let e = new LayerEntry(layer.name, layer.date, layer.url);
    let idx = layerEntries.insert(e);
    ui.insertListItem(layerView, e, idx);

    loadLayer(layer.url);
}

function loadLayer(url) {
    let viewer = uiCesium.viewer;

    let resource = new Cesium.Resource({
        url: url,
        proxy: new Cesium.DefaultProxy('proxy') // proxy through RACE since KML is notorious for including non-CORS links
    });

    let ds = Cesium.KmlDataSource.load(resource, {
        camera: viewer.scene.camera,
        canvas: viewer.scene.canvas
    });

    viewer.dataSources.add(ds);
}