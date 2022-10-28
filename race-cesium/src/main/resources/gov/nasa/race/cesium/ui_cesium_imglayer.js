import * as config from "./config.js";
import * as ws from "./ws.js";
import * as util from "./ui_util.js";
import { ExpandableTreeNode } from "./ui_data.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";

class SourceEntry {
    constructor(source) {
        this.source = source;
        this.show = false;
    }
}

var sources = []; // will be populated by getLayers messages
var sourceView = undefined;
var defaultRender = config.imglayer.render;

var selectedImgLayer = undefined;

//--- module initialization

ui.registerLoadFunction(function initialize() {
    sourceView = initSourceView();

    initImgSliders();

    uiCesium.initLayerPanel("imglayer", config.imglayer, showImgLayer);
    console.log("ui_cesium_imglayer initialized");
});

function initSourceView() {
    let view = ui.getList("imglayer.source.list");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit", "header"], [
            { name: "date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalDateString(e.source.date)},
            ui.listItemSpacerColumn(),
            { name: "show", tip: "toggle visibility", width: "2.1rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowSource) }
        ]);
    }
    return view;
}

function getImageryParams(li) {
    if (li.imageryParams) return li.imageryParams; // if we have some set this has precedence
    else return imageryParams;
}

// this sets imageryParams if they are not set
function getModifiableImageryParams(li) {
    if (!li.imageryParams) li.imageryParams = {...imageryParams };
    return li.imageryParams;
}

function setImgSliders(li) {
    let ip = getImageryParams(li);

    ui.setSliderValue('imglayer.render.alpha', ip.alpha);
    ui.setSliderValue('imglayer.render.brightness', ip.brightness);
    ui.setSliderValue('imglayer.render.contrast', ip.contrast);
    ui.setSliderValue('imglayer.render.hue', ip.hue);
    ui.setSliderValue('imglayer.render.saturation', ip.saturation);
    ui.setSliderValue('imglayer.render.gamma', ip.gamma);
}

function initImgSliders() {
    let e = ui.getSlider('imglayer.render.alpha');
    ui.setSliderRange(e, 0, 1.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.alpha);

    e = ui.getSlider('imglayer.render.brightness');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.brightness);

    e = ui.getSlider('imglayer.render.contrast');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.contrast);

    e = ui.getSlider('imglayer.render.hue');
    ui.setSliderRange(e, 0, 360, 1, util.f_0);
    ui.setSliderValue(e, imageryParams.hue);

    e = ui.getSlider('imglayer.render.saturation');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.saturation);

    e = ui.getSlider('imglayer.render.gamma');
    ui.setSliderRange(e, 0, 2.0, 0.1, util.f_1);
    ui.setSliderValue(e, imageryParams.gamma);
}

ui.exportToMain(function selectImgLayer(event) {
    let li = ui.getSelectedListItem(sourceView);
    if (li) {
        selectedImgLayer = li;
        setImgSliders(li);
    }
})

function showImgLayer (cond) {

}



//--- interactive render parameters

ui.exportToMain(function setImgAlpha(event) {
    if (selectedImgLayer) {
        let v = ui.getSliderValue(event.target);
        selectedImgLayer.layer.alpha = v;
        getModifiableImageryParams(selectedImgLayer).alpha = v;
        requestRender();
    }
});

ui.exportToMain(function setImgBrightness(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerBrightness(v);
    requestRender();

});

function setImgLayerBrightness(v) {
    if (selectedImgLayer) {
        selectedImgLayer.layer.brightness = v;
        getModifiableImageryParams(selectedImgLayer).brightness = v;
        requestRender();
    }
}

ui.exportToMain(function setImgContrast(event) {
    if (selectedImgLayer) {
        let v = ui.getSliderValue(event.target);
        selectedImgLayer.layer.contrast = v;
        getModifiableImageryParams(selectedImgLayer).contrast = v;
        requestRender();
    }
});

ui.exportToMain(function setImgHue(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerHue(v);
    requestRender();
});

function setImgLayerHue(v) {
    if (selectedImgLayer) {
        selectedImgLayer.layer.hue = (v * Math.PI) / 180; // needs radians
        getModifiableImageryParams(selectedImgLayer).hue = v;
        requestRender();
    }
}

ui.exportToMain(function setImgSaturation(event) {
    let v = ui.getSliderValue(event.target);
    setImgLayerSaturation(v);
    requestRender();
});

function setImgLayerSaturation(v) {
    if (selectedImgLayer) {
        selectedImgLayer.layer.saturation = v;
        getModifiableImageryParams(selectedImgLayer).saturation = v;
        requestRender();
    }
}

ui.exportToMain(function setImgGamma(event) {
    if (selectedImgLayer) {
        let v = ui.getSliderValue(event.target);
        selectedImgLayer.layer.gamma = v;
        getModifiableImageryParams(selectedImgLayer).gamma = v;
        requestRender();
    }
});