/* example app code for basic.html */

import { SkipList } from "./ui_data.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";


const NO_PATH = "";
const LINE_PATH = "~";
const WALL_PATH = "≈";

const tracks = new Map(); // map of objects received from server
const displayTracks = new SkipList((a, b) => a.id < b.id, (a, b) => a.id == b.id); // sorted list of objects to display

_initTracks(tracks);


//--- mock data
function _initTracks(map) {
    [
        { id: "UAL1634", pos: { lat: 37.1234, lon: -120.1234 }, date: Date.parse("2021-12-03T10:14:00Z"), path: NO_PATH },
        { id: "UPS2958", pos: { lat: 37.1324, lon: -120.1023 }, date: Date.parse("2021-12-03T10:14:10Z"), path: NO_PATH },
        { id: "PCM7700", pos: { lat: 37.4234, lon: -120.1203 }, date: Date.parse("2021-12-03T10:14:05Z"), path: NO_PATH },
        { id: "ASA2230", pos: { lat: 37.1000, lon: -120.1230 }, date: Date.parse("2021-12-03T10:14:12Z"), path: NO_PATH },
        { id: "PCM7702", pos: { lat: 37.1200, lon: -120.4123 }, date: Date.parse("2021-12-03T10:14:01Z"), path: NO_PATH },
        { id: "UAL5121", pos: { lat: 37.3200, lon: -120.3123 }, date: Date.parse("2021-12-03T10:14:42Z"), path: NO_PATH },
        { id: "UAL4242", pos: { lat: 37.2000, lon: -120.2123 }, date: Date.parse("2021-12-03T10:14:24Z"), path: NO_PATH },

        { id: "PCM7700", pos: { lat: 37.4235, lon: -120.1204 }, date: Date.parse("2021-12-03T10:14:15Z") } // update
    ].forEach(t => {
        if (map.has(t.id)) {
            map.set(t.id, t); // nothing to be done with display list
        } else {
            map.set(t.id, t);
            displayTracks.insert(t);
        }
    });
}

//--- ui initialization and callbacks

app.initialize = function() {
    if (ui.initialize()) {
        console.log("initializing data");

        ui.setField("console.position.latitude", "37.54323");
        ui.setField("console.position.longitude", "-121.87432");

        let slider = ui.getSlider("console.position.zoom");
        ui.setSliderRange(slider, 0, 100, 1);
        ui.setSliderValue(slider, 50);
        //ui.setSliderRange(slider, 400000, 1000000, 100000, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }));
        //ui.setSliderValue(slider, 400000);

        ui.setListItemDisplayColumns("console.tracks.list", ["fit"], [
            { name: "path", width: "1rem", attrs: [], map: track => track.path },
            { name: "id", width: "5rem", attrs: ["alignLeft"], map: track => track.id },
            { name: "date", width: "5rem", attrs: ["fixed"], map: track => util.toLocalTimeString(track.date) }
        ]);
        //ui.setListItemDisplay("console.tracks.list", "8rem", ["alignLeft"], track => track.id); // single column
        ui.setListItems("console.tracks.list", displayTracks);

        ui.setChoiceItems("console.tracks.channel", ["SFDPS", "TAIS", "ASDE-X"], 0);

        //let d = new Date(Date.now());
        //ui.setClock("console.time.localClock", d);
        //ui.setClock("console.time.utcClock", d);

        ui.startTime();
    }
}

app.selectTrack = function(event) {
    let track = ui.getSelectedListItem(event);
    console.log("selected " + JSON.stringify(track));

    if (track.path) {
        ui.setCheckBox("console.tracks.showPath", true);
        switch (track.path) {
            case LINE_PATH:
                ui.selectRadio("console.tracks.showLinePath");
                break;
            case WALL_PATH:
                ui.selectRadio("console.tracks.showWallPath");
                break;
        }
    } else {
        ui.setCheckBox("console.tracks.showPath", false);
        ui.clearRadiosOf("console.tracks.options");
    }
}

app.doSomethingWithSelectedTrack = function() {
    console.log("exec doSomethingWithSelectedTrack");
}

app.toggleCheck = function(event) {
    let isChecked = ui.toggleMenuItemCheck(event);
    ui.setMenuItemDisabled("console.tracks.list.condMenuItem", isChecked);
    console.log("checked: " + isChecked);
}

app.doSomethingDifferent = function() {
    console.log("and now to something completely different");
}

app.doSomethingConditional = function() {
    if (ui.isMenuItemDisabled("console.tracks.list.condMenuItem")) throw "this should have been disabled!";
    else console.log("do something if unchecked");
}

app.showTrackPath = function(event) {
    let isChecked = ui.toggleCheckbox(event);
    let track = ui.getSelectedListItem("console.tracks.list");

    if (track) {
        console.log("show track path: " + isChecked);
        if (isChecked) {
            track.path = LINE_PATH;
            ui.selectRadio("console.tracks.showLinePath");
        } else {
            track.path = NO_PATH;
            ui.clearRadiosOf("console.tracks.options");
        }

        ui.updateListItem("console.tracks.list", track);
    } else {
        console.log("no track selected");
    }
}

app.resetTracks = function() {
    ui.clearListSelection("console.tracks.list");
    ui.setCheckBox("console.tracks.showPath", false);
    ui.clearRadiosOf("console.tracks.options");

    displayTracks.forEach(track => {
        track.path = NO_PATH;
        ui.updateListItem("console.tracks.list", track);
    });

    console.log("Reset tracks");
}

function selectPath(event, pathType, pathSymbol) {
    if (ui.selectRadio(event)) {
        let track = ui.getSelectedListItem("console.tracks.list");
        if (track) {
            ui.setCheckBox("console.tracks.showPath");
            track.path = pathSymbol;
            ui.updateListItem("console.tracks.list", track);
        }
    }
}

app.selectLinePath = function(event) {
    selectPath(event, "line", LINE_PATH);
}

app.selectWallPath = function(event) {
    selectPath(event, "wall", WALL_PATH);
}

app.queryTracks = function(event) {
    let input = ui.getFieldValue(event);
    console.log("query tracks: " + input);
}

app.selectChannel = function(event) {
    let input = ui.getSelectedChoiceValue(event);
    console.log("select channel: " + input);
}

app.zoomChanged = function(event) {
    console.log("new zoom level: " + ui.getSliderValue(event.target));
}