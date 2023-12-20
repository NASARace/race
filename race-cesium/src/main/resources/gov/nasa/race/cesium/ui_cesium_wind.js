/*
 * Copyright (c) 2023, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import * as ws from "./ws.js";
import * as config from "./config.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import * as uiCesium from "./ui_cesium.js";
import * as uiData from "./ui_data.js";
import * as windUtils from "./wind-particles/windUtils.js";
import { ParticleSystem } from "./wind-particles/particleSystem.js";

var viewerParameters = {
    lonRange: new Cesium.Cartesian2(),
    latRange: new Cesium.Cartesian2(),
    pixelSize: 0.0
};

var globeBoundingSphere = new Cesium.BoundingSphere(Cesium.Cartesian3.ZERO, 0.99 * 6378137.0);

// those are just initial values (should come from config)

const defaultVectorRender = config.windlayer.vectorRender;
const defaultAnimRender = config.windlayer.animRender;
const defaultContourRender = config.windlayer.contourRender;

const WindFieldType = {
    GRID: "grid",
    VECTOR: "vector",
    CONTOUR: "contour"
}

const REMOTE = "";
const LOADING = "…";
const LOADED = "○";
const SHOWING = "●";

const csvGridPrefixLine = /^# *nx: *(\d+), *x0: *(.+) *, *dx: *(.+) *, *ny: *(\d+), *y0: *(.+) *, *dy: *(.+)$/;
const csvVectorPrefixLine = /^# *length: *(\d+)$/;

const polyLineColorAppearance = new Cesium.PolylineColorAppearance();

// one per each windField message received from the server
// wraps the server spec plus our internal state for this windField
class WindFieldEntry {

    // factory method
    static create (windField) {
        if (windField.wfType == WindFieldType.GRID) return new AnimFieldEntry(windField);
        if (windField.wfType == WindFieldType.VECTOR) return new VectorFieldEntry(windField);
        if (windField.wfType == WindFieldType.CONTOUR) return new ContourFieldEntry(windField);
        throw "unknown windField type: " + windField.wfType;
    }

    static compareFiltered (a,b) {
        switch (util.compare(a.forecastDate,b.forecastDate)) {
            case -1: return -1;
            case 0: return util.compare(a.wfSource, b.wfSource);
            case 1: return 1;
        }
    }

    constructor(windField) {
        //--- those come from the server message
        this.area = windField.area;
        this.forecastDate = windField.forecastDate; // forecast time for this windfield
        this.baseDate = windField.baseDate; // when the windfield was created
        this.wfType = windField.wfType;  // grid or vector
        this.wfSrs = windField.wfSrs; // the underlying spatial reference system
        this.wfSource = windField.wfSource; // HRRR or station
        this.url = windField.url; // wherer to get the data
        this.bounds = windField.bounds; // rectangle in which windfield was computed

        //--- local data
        this.status = REMOTE;
        this.show = false;
        // assets (such as primitives) are subclass specific
    }

    setStatus (newStatus) {
        this.status = newStatus;
        // update view
    }

    replaceWith (other) {
        console.log("not yet.");
    }

    //--- override in subclasses
    setVisible (showIt) {}
    startViewChange() {}
    endViewChange() {}
    renderChanged() {}
    updateDisplayPanel() {}
}

class AnimFieldEntry extends WindFieldEntry {
    constructor (windField) {
        super(windField);
        this.particleSystem = undefined; // only lives while we show the grid animation, owns a number of CustomPrimitives
        this.render = {...defaultAnimRender};
    }

    static animShowing = 0;

    setVisible (showIt) {
        if (showIt != this.show) {
            if (showIt) {
                AnimFieldEntry.animShowing++;
                if (!this.particleSystem) {
                    this.loadParticleSystemFromUrl(); // async
                } else {
                    this.particleSystem.forEachPrimitive( p=> p.show = true);
                    uiCesium.setRequestRenderMode(false);

                }
                this.setStatus( SHOWING);

            } else {
                AnimFieldEntry.animShowing--;
                if (this.particleSystem) {
                    // TODO - do we have to stop rendering first ?
                    this.particleSystem.forEachPrimitive( p=> p.show = false);
                    if (AnimFieldEntry.animShowing == 0) {
                        uiCesium.setRequestRenderMode(true);
                        uiCesium.requestRender();
                    }
                    this.setStatus( LOADED);
                }
            }
            this.show = showIt;
        }
    }

    startViewChange() {
        if (this.status == SHOWING) {
            this.particleSystem.forEachPrimitive( p=> p.show = false);
            uiCesium.requestRender();
        }
    }

    endViewChange() {
        if (this.status == SHOWING) {
            this.particleSystem.applyViewerParameters(viewerParameters);
            this.particleSystem.forEachPrimitive( p=> p.show = true);
            uiCesium.requestRender();
        }
    }

    async loadParticleSystemFromUrl() {

        let nx, x0, dx, ny, y0, dy; // grid bounds and cell size
        let hs, us, vs, ws; // the data arrays
        let hMin = 1e6, uMin = 1e6, vMin = 1e6, wMin = 1e6;
        let hMax = -1e6, uMax = -1e6, vMax = -1e6, wMax = -1e6;
    
        let i = 0;
    
        function procLine (line) {
            if (i > 1) { // grid data line
                let values = util.parseCsvValues(line);
                if (values.length == 5) {
                    const h = values[0];
                    const u = values[1];
                    const v = values[2];
                    const w = values[3];
    
                    if (h < hMin) hMin = h;
                    if (h > hMax) hMax = h;
                    if (u < uMin) uMin = u;
                    if (u > uMax) uMax = u;
                    if (v < vMin) vMin = v;
                    if (v > vMax) vMax = v;
                    if (w < wMin) wMin = w;
                    if (w > wMax) wMax = w;
    
                    const j = i-2;
                    hs[j] = h;
                    us[j] = u;
                    vs[j] = v;
                    ws[j] = w;
                }
            } else if (i > 0) { // ignore header line
            } else { // prefix comment line with grid bounds
                let m = line.match(csvGridPrefixLine);
                if (m && m.length == 7) {
                    nx = parseInt(m[1]);
                    x0 = Number(m[2]);
                    dx = Number(m[3]);
                    ny = parseInt(m[4]);
                    y0 = Number(m[5]);
                    dy = Number(m[6]);
    
                    let len = (nx * ny);
                    hs = new Float32Array(len);
                    us = new Float32Array(len);
                    vs = new Float32Array(len);
                    ws = new Float32Array(len);
                }
            }
            i++;
        };
    
        function axisData (nv,v0,dv) {
            let a = new Float32Array(nv);
            for (let i=0, v=v0; i<nv; i++, v += dv) { a[i] = v; }
            let vMin, vMax;
            if (dv < 0) {
                vMin = a[nv-1];
                vMax = a[0];
            } else {
                vMin = a[0];
                vMax = a[nv-1];
            }
        
            return { array: a, min: vMin, max: vMax };
        }
    
        this.setStatus( LOADING);
        await util.forEachTextLine(this.url, procLine);

        console.log("loaded ", i-2, " grid points from ", this.url);
    
        let data = {
            dimensions: { lon: nx, lat: ny, lev:1 },
            lon: axisData(nx, x0 < 0 ? 360 + x0 : x0, dx),  // normalize to 0..360
            lat: axisData(ny, y0, dy),
            lev: { array: new Float32Array([1]), min: 1, max: 1 },
            H: { array: hs, min: hMin, max: hMax },
            U: { array: us, min: uMin, max: uMax },
            V: { array: vs, min: vMin, max: vMax },
            W: { array: ws, min: wMin, max: wMax }
        };
        //console.log("@@ data:", data);
    
        this.particleSystem = new ParticleSystem(uiCesium.viewer.scene.context, data, this.render, viewerParameters);
        this.particleSystem.forEachPrimitive( p=> uiCesium.addPrimitive(p));
        uiCesium.setRequestRenderMode(false);
    }

    renderChanged() {
        if (this.particleSystem) {
            this.particleSystem.applyUserInput(this.render);
        }
    }

    // TODO - this needs a general suspend/resume mode, also for pan, zoom etc.
    updatePrimitives() {
        if (this.isAnimShowing()) { 
            this.showAnim(false);
            this.particleSystem.canvasResize(uiCesium.viewer.scene.context);
            this.showAnim(true);
        }
    }

    updateDisplayPanel() {
        let r = this.render;

        ui.setSliderValue( ui.getSlider("wind.anim.particles"), r.particlesTextureSize);
        ui.setSliderValue( ui.getSlider("wind.anim.height"), r.particleHeight);
        ui.setSliderValue( ui.getSlider("wind.anim.fade_opacity"), r.fadeOpacity);
        ui.setSliderValue( ui.getSlider("wind.anim.drop"), r.dropRate);
        ui.setSliderValue( ui.getSlider("wind.anim.drop_bump"), r.dropRateBump);
        ui.setSliderValue( ui.getSlider("wind.anim.speed"), r.speedFactor);
        ui.setSliderValue( ui.getSlider("wind.anim.width"), r.lineWidth);

        ui.setField( ui.getField("wind.anim.color"), r.color.toCssHexString());
    }
}

class VectorFieldEntry extends WindFieldEntry {
    constructor (windField) {
        super(windField);
        this.render = {...defaultVectorRender};

        this.pointPrimitive = undefined; // Cesium.Primitive instantiated when showing the static vector field
        this.linePrimitive = undefined;
    }



    setVisible (showIt) {
        if (showIt != this.show) {
            this.show = showIt;
            if (showIt) {
                if (!this.pointPrimitive) {
                    this.loadVectorsFromUrl(); // this is async, it will set vectorPrimitives when done
                } else {
                    uiCesium.showPrimitive(this.pointPrimitive, true);
                    uiCesium.showPrimitive(this.linePrimitive, true);
                    uiCesium.requestRender();
                }
                this.setStatus( SHOWING);

            } else {
                if (this.pointPrimitive) {
                    uiCesium.showPrimitive(this.pointPrimitive, false);
                    uiCesium.showPrimitive(this.linePrimitive, false);
                    uiCesium.requestRender();
                    this.setStatus( LOADED);
                }
            }
        }
    }

    async loadVectorsFromUrl() {
        let points = new Cesium.PointPrimitiveCollection();
        let vectors = []; // array of GeometryInstances
        let i = 0;
        let j = 0;
        let render = this.render;

        let dc = new Cesium.DistanceDisplayConditionGeometryInstanceAttribute(0,50000);
        let vecAttrs = {  distanceDisplayCondition: dc };
        let vecClrs = [render.color];
        
        function procLine (line) {
            if (i > 1) { // vector line
                let values = util.parseCsvValues(line);
                if (values.length == 7) {
                    let p0 = new Cesium.Cartesian3(values[0],values[1],values[2]);
                    let p1 = new Cesium.Cartesian3(values[3],values[4],values[5]);
    
                    //let spd = values[6];
            
                    let pp = points.add({
                        position: p0,
                        pixelSize: render.pointSize,
                        color: render.color
                    });
                    pp.distanceDisplayCondition = new Cesium.DistanceDisplayCondition(0,150000);
                    // pp.scaleByDistance = new Cesium.NearFarScalar(1.5e2, 15, 8.0e6, 0.0);
                                
                    vectors[j++] = new Cesium.GeometryInstance({
                        geometry: new Cesium.PolylineGeometry({
                            positions: [p0,p1],
                            colors: vecClrs,
                            width: render.strokeWidth,
                        }),
                        attributes:  vecAttrs
                    });
                }
            } else if (i > 0) { // header line (ignore)
            } else { // prefix comment line "# columns: X, rows: Y"
                let m = line.match(csvVectorPrefixLine);
                if (m) {
                    let len = parseInt(m[1]);
                    vectors = Array(len);
                }
            }
            i++;
        };
    
        this.setStatus( LOADING);
        await util.forEachTextLine( this.url, procLine);
        console.log("loaded ", i-2, " vectors from ", this.url);

        this.vectors = vectors;
        this.pointPrimitive = points;
        this.linePrimitive = this.createVectorPrimitive(vectors);

        uiCesium.addPrimitive(this.pointPrimitive);
        if (this.linePrimitive) uiCesium.addPrimitive(this.linePrimitive);
        uiCesium.requestRender();
    }

    createVectorPrimitive(vectors) {
        if (this.render.strokeWidth) {
            return new Cesium.Primitive({
                geometryInstances: this.vectors,
                appearance: polyLineColorAppearance,
                releaseGeometryInstances: false
            });
        } else {
            return null; // no point creating a primitive if there is nothing to render
        }
    }

    renderChanged() {
        let render = this.render;
        let oldLinePrimitive = this.pointPrimitive;
        if (oldLinePrimitive) {
            let len = oldLinePrimitive.length;
            for (let i=0; i<len; i++) {
                let pt = oldLinePrimitive.get(i);
                pt.color = render.color;
                pt.pixelSize = render.pointSize;
            }
        }

        oldLinePrimitive = this.linePrimitive;
        if (oldLinePrimitive) {
            // unfortunately we cannot change display of rendered primitive GeometryInstances - we have to re-create it
            let vectors = this.vectors;
            vectors.forEach( gi=> gi.geometry._colors[0] = render.color );
            this.linePrimitive = this.createVectorPrimitive(vectors);
            uiCesium.removePrimitive(oldLinePrimitive);
            uiCesium.addPrimitive(this.linePrimitive);
        }

        uiCesium.requestRender();
    }

    updateDisplayPanel() {
        let render = this.render;
        ui.setSliderValue("wind.vector.point_size", render.pointSize);
        ui.setSliderValue("wind.vector.width", render.strokeWidth);
        ui.setField("wind.vector.color", render.color.toCssHexString());
    }
}

class ContourFieldEntry extends WindFieldEntry {
    constructor (windField) {
        super(windField);
        this.dataSource = undefined;
        this.render = {...defaultContourRender};
    }

    setVisible (showIt) {
        if (showIt != this.show) {
            this.show = showIt;
            if (showIt) {
                if (!this.dataSource) {
                    this.loadContoursFromUrl(); // this is async, it will set vectorPrimitives when done
                } else {
                    this.dataSource.show = true;
                    uiCesium.requestRender();
                }
                this.setStatus( SHOWING);

            } else {
                if (this.dataSource) {
                    this.dataSource.show = false;
                    uiCesium.requestRender();
                    this.setStatus( LOADED);
                }
            }
        }
    }

    async loadContoursFromUrl() {
        let renderOpts = this.getRenderOpts();
        let response = await fetch(this.url);
        let data = await response.json();

        Cesium.GeoJsonDataSource.load(data, renderOpts).then(  // TODO - does this support streams? 
            ds => {
                this.dataSource = ds;
                this.postProcessDataSource();

                uiCesium.addDataSource(ds);
                uiCesium.requestRender();
                //setTimeout( () => uiCesium.requestRender(), 300); // ??
            }
        );
    }

    getRenderOpts() {
        return { 
            stroke: this.render.strokeColor, 
            strokeWidth: this.render.strokeWidth, 
            fill: this.render.fillColors[0]
        };
    }

    postProcessDataSource() {
        let entities = this.dataSource.entities.values;
        let render = this.render;

        for (const e of entities) {
            let props = e.properties;
            if (props) {
                let spd = this.getPropValue(props, "spd");
                if (spd) {
                    let i = Math.min( Math.trunc(spd / 5), render.fillColors.length-1);
                    e.polygon.material = render.fillColors[i];
                }
            }
        }
    }

    getPropValue(props,key) {
        let p = props[key];
        return p ? p._value : undefined;
    }

    updateDisplayPanel() {
    }
}

class WindFieldArea {
    constructor (area, bounds) {
        this.label = area;
        this.bounds = bounds;
        this.show = false;
        this.entity = undefined; // display
    }

    setVisible (showIt) {

    }

    static compare (a,b) { return util.compare(a.label, b.label); }
}

//--- module data

const windFieldEntries = new Map(); // unique-key -> WindFieldEntry

var areas = [] // sorted list of areas
var selectedArea = undefined;
var displayEntries = [];  // time/source sorted entries for selected area and type
var selectedEntry = undefined;
var selectedType = "vector";

createIcon();
createWindow();

var areaView = ui.getChoice("wind.areas");
var entryView = initEntryView();
initAnimDisplayControls();
initVectorDisplayControls();
initContourDisplayControls();

ws.addWsHandler(handleWsWindMessages);
setupEventListeners();

console.log("ui_cesium_wind initialized");

function createIcon() {
    return ui.Icon("wind-icon.svg", (e)=> ui.toggleWindow(e,'wind'), "wind_icon");
}

function createWindow() {
    return ui.Window("Wind", "wind", "wind-icon.svg")(
        ui.Panel("wind-fields", true)(
            ui.RowContainer()(
                ui.Choice("area","wind.areas",selectArea),
                ui.CheckBox("show area", toggleShowArea),
                ui.HorizontalSpacer(6)
            ),
            ui.RowContainer()(
                ui.Radio("vector", selectVectorWindFields,null,true),
                ui.Radio("anim", selectGridWindFields),
                ui.Radio("contour", selectContourWindFields)
            ),
            ui.List("wind.entries", 6, selectWindFieldEntry)
        ),
        ui.Panel("anim display")(
            ui.ColumnContainer("align_right")(
                ui.Slider("particles", "wind.anim.particles", windParticlesChanged),
                ui.Slider("extra height", "wind.anim.height", windHeightChanged),
                ui.Slider("fade opacity", "wind.anim.fade_opacity", windFadeOpacityChanged),
                ui.Slider("drop", "wind.anim.drop", windDropRateChanged),
                ui.Slider("drop bump", "wind.anim.drop_bump", windDropRateBumpChanged),
                ui.Slider("speed factor", "wind.anim.speed", windSpeedChanged),
                ui.Slider("line width", "wind.anim.width", windWidthChanged),
                ui.ColorField("color", "wind.anim.color", true, animColorChanged),
            )
        ),
        ui.Panel("vector display")(
            ui.Slider("point size", "wind.vector.point_size", vectorPointSizeChanged),
            ui.Slider("line width", "wind.vector.width", vectorLineWidthChanged),
            ui.ColorField("line color", "wind.vector.color", true, vectorLineColorChanged),

        ),
        ui.Panel("contour display")(
            ui.Slider("stroke width", "wind.contour.stroke_width", contourStrokeWidthChanged),
            ui.ColorField("stroke color", "wind.contour.stroke_color", true, contourStrokeColorChanged),
            ui.ColorField("0-5mph", "wind.contour.color0", true, contourFillColorChanged),
            ui.ColorField("5-10mph", "wind.contour.color1", true, contourFillColorChanged),
            ui.ColorField("10-15mph", "wind.contour.color2", true, contourFillColorChanged),
            ui.ColorField("15-20mph", "wind.contour.color3", true, contourFillColorChanged),
            ui.ColorField(">20mph", "wind.contour.color4", true, contourFillColorChanged)
        )
    );
}

function initEntryView() {
    let view = ui.getList("wind.entries");
    if (view) {
        ui.setListItemDisplayColumns(view, ["header"], [
            { name: "", width: "2rem", attrs: [], map: e => e.status },
            { name: "forecast", width: "9.5rem",  attrs: ["fixed"], map: e => util.toLocalDateHMTimeString(e.forecastDate) },
            { name: "Δt", tip: "forecast hour", width: "2rem", attrs: ["fixed", "alignRight"], map: e => util.hoursBetween(e.baseDate, e.forecastDate) },
            ui.listItemSpacerColumn(1),
            { name: "src", width: "4rem", attrs:[], map: e=> e.wfSource },
            ui.listItemSpacerColumn(2),
            { name: "show", tip: "toggle windfield visibility", width: "2.1rem", attrs: [], map: e => ui.createCheckBox(e.show, toggleShowWindField) },
        ]);
    }
    return view;
}

function initAnimDisplayControls() {
    var e = undefined;
    //e = ui.getChoice("wind.max_particles");
    //ui.setChoiceItems(e, ["0", "16", "32", "64", "128", "256"], 3);
    let r = defaultAnimRender;

    e = ui.getSlider("wind.anim.particles");
    ui.setSliderRange(e, 0, 128, 16);
    ui.setSliderValue(e, r.particlesTextureSize);

    e = ui.getSlider("wind.anim.height");
    ui.setSliderRange(e, 0, 10000, 500);
    ui.setSliderValue(e, r.particleHeight);

    e = ui.getSlider("wind.anim.fade_opacity");
    ui.setSliderRange(e, 0.8, 1.0, 0.01, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }));
    ui.setSliderValue(e, r.fadeOpacity);

    e = ui.getSlider("wind.anim.drop");
    ui.setSliderRange(e, 0.0, 0.01, 0.001, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 3 }));
    ui.setSliderValue(e, r.dropRate);

    e = ui.getSlider("wind.anim.drop_bump");
    ui.setSliderRange(e, 0.0, 0.05, 0.005, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 3 }));
    ui.setSliderValue(e, r.dropRateBump);

    e = ui.getSlider("wind.anim.speed");
    ui.setSliderRange(e, 0.0, 0.3, 0.02, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }));
    ui.setSliderValue(e, r.speedFactor);

    e = ui.getSlider("wind.anim.width");
    ui.setSliderRange(e, 0.0, 3.0, 0.5, new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }));
    ui.setSliderValue(e, r.lineWidth);

    e = ui.getField("wind.anim.color");
    ui.setField(e, r.color.toCssHexString());
}

function initVectorDisplayControls() {
    var e = undefined;

    e = ui.getSlider("wind.vector.point_size");
    ui.setSliderRange(e, 0, 8, 0.5);
    ui.setSliderValue(e, defaultVectorRender.pointSize);

    e = ui.getSlider("wind.vector.width");
    ui.setSliderRange(e, 0, 5, 0.2);
    ui.setSliderValue(e, defaultVectorRender.strokeWidth);

    e = ui.getField("wind.vector.color");
    ui.setField(e, defaultVectorRender.color.toCssHexString());
}

function initContourDisplayControls() {
    var e = undefined;

    e = ui.getSlider("wind.contour.stroke_width");
    ui.setSliderRange(e, 0, 3, 0.5);
    ui.setSliderValue(e, defaultContourRender.strokeWidth);

    e = ui.getField("wind.contour.stroke_color");
    ui.setField(e, defaultContourRender.strokeColor.toCssHexString());

    for (var i = 0; i<defaultContourRender.fillColors.length; i++) {
        e = ui.getField(`wind.contour.color${i}`);
        if (e) {
            ui.setField(e, defaultContourRender.fillColors[i].toCssHexString());
        }
    }
}

//--- WS messages

function handleWsWindMessages(msgType, msg) {
    switch (msgType) {
        case "windField":
            handleWindFieldMessage(msg.windField);
            return true;
        default:
            return false;
    }
}

function handleWindFieldMessage(windField) {
    let we = WindFieldEntry.create(windField);
    windFieldEntries.set(windField.url, we);
    addArea(we);
    if (isSelected(we)) updateEntryView();
}

function addArea (we) {
    if (!areas.find( a=> a.label == we.area)) {
        let wa = new WindFieldArea(we.area, we.bounds);
        uiData.sortInUnique( areas, wa, WindFieldArea.compare);
        ui.setChoiceItems(areaView, areas);
        if (!selectedArea) ui.selectChoiceItem(areaView, wa);
    }
}

function updateEntryView() {
    displayEntries = util.filterMapValues(windFieldEntries, we=> isSelected(we));
    displayEntries.sort(WindFieldEntry.compareFiltered);

    ui.setListItems(entryView, displayEntries);
}

function isSelected (we) {
    return (we.area == selectedArea.label) && (we.wfType == selectedType);
}

//--- rendering suspend/resume

function startViewChange() {
    windFieldEntries.forEach( (e,k) => e.startViewChange() );
}

function endViewChange() {
    updateViewerParameters();
    windFieldEntries.forEach( (e,k) => e.endViewChange() );
}

//--- interaction

function selectArea(event) {
    selectedArea = ui.getSelectedChoiceValue(areaView);
    updateEntryView();
};

function toggleShowArea(event) {
    toggleShow( event, (ae,showIt) => ae.showArea(showIt));
};

function selectGridWindFields(event) {
    selectedType = "grid";
    updateEntryView();
}

function selectVectorWindFields(event) {
    selectedType = "vector";
    updateEntryView();
}

function selectContourWindFields(event) {
    selectedType = "contour";
    updateEntryView();
}

function toggleShowWindField(event) {
    let we = ui.getListItemOfElement(event.target);
    if (we) {
        we.setVisible(ui.isCheckBoxSelected(event));
        ui.updateListItem(entryView, we);
    }
};


function selectWindFieldEntry(event) {
    selectedEntry = ui.getSelectedListItem(entryView);
    if (selectedEntry) selectedEntry.updateDisplayPanel();
}

//--- vector controls

function vectorPointSizeChanged(event) {
    if (selectedEntry) {
        let n = ui.getSliderValue(event.target);
        selectedEntry.render.pointSize = n;
        selectedEntry.renderChanged();
    }
}

function vectorLineWidthChanged(event) {
    if (selectedEntry) {
        let n = ui.getSliderValue(event.target);
        selectedEntry.render.strokeWidth = n;
        selectedEntry.renderChanged();
    }
}

function vectorLineColorChanged(event) {
    if (selectedEntry) {
        let clrSpec = event.target.value;
        if (clrSpec) {
            selectedEntry.render.color = Cesium.Color.fromCssColorString(clrSpec);
            selectedEntry.renderChanged();
        }
    }
}

//--- contour controls

function contourStrokeWidthChanged(event) {

}

function contourStrokeColorChanged(event) {

}

function contourFillColorChanged(event) {

}

//--- grid animation sliders

// we have to delay particleSystem updates while user moves the slider
var pendingUserInputChange = false; 

function triggerAnimRenderChange(windEntry, newInput) {
    if (newInput) pendingUserInputChange = true;

    setTimeout(() => {
        if (pendingUserInputChange) {
            pendingUserInputChange = false;
            triggerAnimRenderChange(windEntry, false);
        } else {
            windEntry.renderChanged();
        }
    }, 300);
}

function windParticlesChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        let n = ui.getSliderValue(event.target);
        // todo - shall we adjust here?
        e.render.particlesTextureSize = n;
        e.render.maxParticles = n*n;
        triggerAnimRenderChange(e, true);
    }
}

function windFadeOpacityChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.render.fadeOpacity = ui.getSliderValue(event.target);
        triggerAnimRenderChange(e, true);
    }
}

function windSpeedChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.render.speedFactor = ui.getSliderValue(event.target);
        triggerAnimRenderChange(e, true);
    }
}

function windWidthChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.render.lineWidth = ui.getSliderValue(event.target);
        triggerAnimRenderChange(e, true);
    }
}

function windHeightChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.render.particleHeight = ui.getSliderValue(event.target);
        triggerAnimRenderChange(e, true);
    }
}

function windDropRateChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.render.dropRate = ui.getSliderValue(event.target);
        triggerAnimRenderChange(e, true);
    }
}

function windDropRateBumpChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        e.render.dropRateBump = ui.getSliderValue(event.target);
        triggerAnimRenderChange(e, true);
    }
}

function animColorChanged(event) {
    let e = ui.getSelectedListItem(entryView);
    if (e) {
        let clrSpec = event.target.value;
        if (clrSpec) {
            e.render.color = Cesium.Color.fromCssColorString(clrSpec);
            triggerAnimRenderChange(e, true);
        }
    }
}


//--- viewer callbacks

function updateViewerParameters() {
    let viewer = uiCesium.viewer;

    var viewRectangle = viewer.camera.computeViewRectangle(viewer.scene.globe.ellipsoid);
    var lonLatRange = windUtils.viewRectangleToLonLatRange(viewRectangle);

    viewerParameters.lonRange.x = lonLatRange.lon.min;
    viewerParameters.lonRange.y = lonLatRange.lon.max;
    viewerParameters.latRange.x = lonLatRange.lat.min;
    viewerParameters.latRange.y = lonLatRange.lat.max;

    var pixelSize = viewer.camera.getPixelSize(
        globeBoundingSphere,
        viewer.scene.drawingBufferWidth,
        viewer.scene.drawingBufferHeight
    );

    if (pixelSize > 0) {
        viewerParameters.pixelSize = pixelSize;
    }
}

function setupEventListeners() {
    let scene = uiCesium.viewer.scene;

    uiCesium.viewer.camera.moveStart.addEventListener(startViewChange);
    uiCesium.viewer.camera.moveEnd.addEventListener(endViewChange);

    var resized = false;

    window.addEventListener("resize", () => {
        resized = true;
        //scene.primitives.show = false;
        //windEntries.forEach(e => e.removePrimitives());
    });

    scene.preRender.addEventListener(() => {
        if (resized) {
            //windEntries.forEach(e => e.updatePrimitives());
            resized = false;
            //scene.primitives.show = true;
        }
    });
}

//--- data acquisition and display

var restoreRequestRenderMode = false;

function suspendRequestRenderMode (){
    if (uiCesium.isRequestRenderMode) {
        restoreRequestRenderMode = true;
        uiCesium.setRequestRenderMode(false);
    }
}

function resumeRequestRenderMode (){
    if (restoreRequestRenderMode) {
        uiCesium.setRequestRenderMode(true);
        restoreRequestRenderMode = false;
    }
}

//--- CSV (local wind vectors)


async function loadCsvVector(windEntry) {
 
    return windEntry.data;
}

//--- CSV grid based particle system data




