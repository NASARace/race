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
    constructor(id, date, url, show) {
        this.id = id;
        this.date = date; // last change of trackEntries
        this.url = url;
        this.show = show;

        this.dataSource = undefined; // set upon XMLHttpRequest completion
    }

    setVisible(isVisible) {
        if (isVisible != this.show) {
            if (this.dataSource) this.dataSource.show = isVisible;
            this.show = isVisible;
        }
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
            { name: "id", width: "8rem", attrs: ["alignLeft"], map: e => e.id },
            {
                name: "date",
                width: "6rem",
                attrs: ["fixed", "alignRight"],
                map: e => util.toLocalTimeString(e.date)
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
    let e = new LayerEntry(layer.name, layer.date, layer.url, layer.show);
    let idx = layerEntries.insert(e);
    ui.insertListItem(layerView, e, idx);

    loadLayer(e);
}

function loadLayer(layerEntry) {
    let viewer = uiCesium.viewer;

    let resource = new Cesium.Resource({
        url: layerEntry.url,
        proxy: new Cesium.DefaultProxy('proxy') // proxy through RACE since KML is notorious for including non-CORS links
    });

    Cesium.KmlDataSource.load(resource, { // FIXME - what about other layer types
        camera: viewer.scene.camera,
        canvas: viewer.scene.canvas
    }).then(ds => {
        console.log("layer loaded " + ds.name);
        ds.show = layerEntry.show;
        layerEntry.dataSource = ds;
        viewer.dataSources.add(ds);
    });
}

//--- user interface

ui.exportToMain(function selectLayer(event) {
    let le = event.detail.curSelection;
    if (le) {
        ui.setCheckBox("layers.show", le.show);
    }
})

ui.exportToMain(function toggleLayer(event) {
    let le = ui.getSelectedListItem(layerView);
    if (le) {
        le.setVisible(ui.isCheckBoxSelected(event));
        ui.updateListItem(layerView, le);
    }
})