import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiData from "./ui_data.js";
import * as uiCesium from "./ui_cesium.js";

//--- data model

class PathEntry {
    constructor(path) {
        this.path = path; 
        this.show = false;
        this.dataSource = undefined;
    }
}

var paths = []; 
var selectedPath = undefined;

var waypoints = []; // the list of currently shown/edited waypoints (GeoJSON coordinate elements)
var selectedWaypoint = undefined;
var distances = []; // computed from waypoints

var pathListView = undefined;
var pointListView = undefined;

//--- module initialization

initWindow();

ws.addWsHandler(handleWsPathEditorMessages);

uiCesium.initLayerPanel("patheditor", config.patheditor, showPathEditor);
console.log("ui_cesium_patheditor initialized");

//--- UI initialization

function initWindow() {
    ui.Icon("geomarker-icon.svg", (e)=> ui.toggleWindow(e,'patheditor'));

    ui.Window("Path Editor", "patheditor", "path-icon.svg")(
        ui.LayerPanel("patheditor", toggleShowPathEditor),
        ui.Panel("path list", true)(
            ui.TreeList("patheditor.path.list", 15, 25, selectPath)
        ),
        ui.Panel("path data", true)(
            ui.TextInput("name", "patheditor.path.name", null, true, null, "5rem"),
            ui.TextInput("info", "patheditor.path.info", null, true, null, "20rem"),
            ui.RowContainer()(
                ui.Button("clear", clearPath),
                ui.Button("save", savePath),
            ),
            ui.List("patheditor.waypoint.list", 5, selectWaypoint),
            ui.RowContainer()(
                ui.Button("enter",enterPointSequence),
                ui.Button("⨁", addPoint),
                ui.Button("⌫", removePoint)
            ),
        ),
        ui.Panel("display parameters", false)()
    );

    pathListView = initPathListView("patheditor.path.list");
    pointListView = initPointListView("patheditor.waypoint.list");
}

function initPathListView(id) {
    let view = ui.getList(id);
    if (view) {
        ui.setListItemDisplayColumns(view, ["header"], [
            { name: "name", width: "8rem", attrs: [], map: e=> e.path.name },
            { name: "date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalDateString(e.path.date)},
            ui.listItemSpacerColumn(),
            { name: "show", tip: "toggle visibility", width: "2.1rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowPath) }
        ]);
    }
    return view;
}

function initPointListView(id) {
    let view = ui.getList(id);
    if (view) {
        // items are normal GeoJSON [lon,lat,alt] "coordinates" Array elements - we don't want to add properties to them
        ui.setListItemDisplayColumns(view, ["header"], [
            { name: "#", width: "2rem", attrs: ["fixed", "alignRight"], map: (e,ie) => {
                ie._idx = ui.indexOfElement(ie); // compute it just once based on item element - it's more efficient
                return waypoints[ie._idx];
            },
            { name: "lat", tip: "latitude [°]", width:  "5.5rem", attrs: ["fixed", "alignRight"], map: e => util.formatFloat(e[1],4)},
            { name: "lon", tip: "longitude [°]", width:  "6.5rem", attrs: ["fixed", "alignRight"], map: e => util.formatFloat(e[0],4)},
            { name: "alt", tip: "altitude [m]", width:  "5.5rem", attrs: ["fixed", "alignRight"], map: e => Math.round(e[2])},
            { name: "dist", tip: "distance from start [m]", width: "5rem", attrs: ["fixed", "alignRight"], map: (e,ie) => distances[ie._idx]},
        ]);
    }
    return view;
}

//--- websocket (data) interface

function handleWsPathEditorMessages(msgType, msg) {
    switch (msgType) {
        case "path":
            handlePathMessage(msg.fireSummary);
            return true;

        default:
            return false;
    }
}

//--- UI callbacks

function selectPath(event) {
    // TBD
}

function selectWaypoint(event) {
    // TBD
}

function enterPointSequence() {

}

function showPathEditor(cond) {
    paths.forEach( path=> {
        if (path.dataSource) path.dataSource.show = cond;
    });
    uiCesium.requestRender();
}