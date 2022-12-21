import * as ui from "./ui.js";
import * as config from "./config.js";

//--- theme/settings UI

const UI_THEMES = "race-ui-themes";
const LOCAL = "local-";

let themeCss = undefined;
let themeVars = undefined;

let themeVarView = undefined;
let themeVarEntry = undefined;
let selectedTheme = undefined;


ui.registerLoadFunction(function initialize() {
    ui.registerThemeChangeHandler(themeChanged);
    ui.setChoiceItems("settings.theme", getThemes(), 0);

    ui.setButtonDisabled("settings.remove", true);
    ui.setButtonDisabled("settings.save", true);

    themeVarView = initThemeVarView();
    themeVarEntry = ui.getField("settings.value");

    themeCss = getThemeCss();
    console.log("ui_settings initialized");
});

const themeRE = /blob\:|.*ui_theme.css(?:\?.+)?/;

function getThemeCss() {
    let styleSheets = document.styleSheets;
    for (var i = 0; i < styleSheets.length; i++) {
        let css = styleSheets[i];
        if (css.href && themeRE.test(css.href)) return css;
    }
    return undefined;
}


function initThemeVarView() {
    let view = ui.getList("settings.themeVars");
    if (view) {
        ui.setListItemDisplayColumns(view, ["fit"], [
            { name: "clr", width: "2rem", attrs: [], map: e => colorInput(e) },
            { name: "name", width: "15rem", attrs: [], map: e => e }
        ]);
    }
    return view;
}

var colorRE = new RegExp(".*color|background.*");
var rgbaRE = /^rgba?\((\d+)[,\s]\s*(\d+)[,\s]\s*(\d+)(?:[,\s]\s*(\d+\.{0,1}\d*))?\)$/;

function rgbaToHex(rgba) {
    return `#${rgba.match(rgbaRE).slice(1).map((n, i) => 
        (i === 3 ? Math.round(parseFloat(n) * 255) : parseFloat(n)).toString(16).padStart(2, '0').replace('NaN', '')).join('')}`
}

function colorInput(varName) {
    let docStyle = getComputedStyle(document.documentElement);

    if (colorRE.test(varName)) {
        let key = "--" + varName;
        //let v = themeCss.cssRules[0].styleMap.get(key)[0].trim();
        let v = docStyle.getPropertyValue(key).trim();

        if (v.startsWith("rgb")) {
            v = rgbaToHex(v);
        } else if (!v.startsWith('#')) {
            v = colors[v.toLowerCase()];
            if (!v) v = "#000000"; // unknown color
        }
        v = (v.length > 7) ? v.substring(0, 7) : v; // color input does not support alpha

        return ui.createColorInput(v, "var(--list-item-height)", function(event) {
            let newValue = event.target.value;
            themeVars[varName] = newValue;
            themeCss.cssRules[0].styleMap.set(key, newValue);
            ui.setField(themeVarEntry, newValue);
        });
    }
    return null;
}

ui.exportToMain(function selectTheme(event) {
    let theme = ui.getSelectedChoiceValue(event);
    if (theme) {
        selectedTheme = theme;
        clearThemeVars();
        if (theme.startsWith(LOCAL)) { // load from localStorage
            loadLocalTheme(theme);
        } else {
            loadServerTheme(theme);
        }
    }
});

ui.exportToMain(function saveLocalTheme() {
    if (themeVars) {
        let themeName = themeVars.theme.replaceAll("\"", "");
        if (!themeName.startsWith(LOCAL)) themeName = LOCAL + themeName;

        themeName = prompt("Please enter local theme name", themeName);
        if (themeName) {
            themeName = themeName.trim();
            if (!themeName.startsWith(LOCAL)) themeName = LOCAL + themeName
            themeVars.theme = themeName;

            let themes = getLocalThemes();
            if (!themes.find(e => e === themeName)) {
                themes.push(themeName);
                localStorage.setItem(UI_THEMES, JSON.stringify(themes));


                let allThemes = getThemes();
                ui.setChoiceItems("settings.theme", allThemes, allThemes.length - 1);
            }

            //localStorage.setItem(name, JSON.stringify(themeVars));
            localStorage.setItem(themeName, getCss(themeVars));

            selectedTheme = themeName;
            ui.setButtonDisabled("settings.remove", false);
        }
    } else {
        alert("no edited theme");
    }
});


ui.exportToMain(function removeLocalTheme() {
    console.log("removing " + selectedTheme);
    if (selectedTheme && selectedTheme.startsWith(LOCAL)) {
        if (confirm("delete theme: " + selectedTheme + " ?")) {
            let locThemes = getLocalThemes();
            if (locThemes.find(e => e == selectedTheme)) {
                locThemes = locThemes.filter(t => t !== selectedTheme);
                if (locThemes.length == 0) localStorage.removeItem(UI_THEMES);
                else localStorage.setItem(UI_THEMES, JSON.stringify(locThemes));
            }
            localStorage.removeItem(selectedTheme);

            let remainingThemes = ui.getChoiceItems("settings.theme").filter(e => e !== selectedTheme);
            ui.setChoiceItems("settings.theme", remainingThemes);
            ui.selectChoiceItemIndex("settings.theme", 0); // select the first one
        }

    } else alert("no local theme selected");
});

function getThemes() {
    let allThemes = [];

    config.serverThemes.forEach(t => allThemes.push(t));
    getLocalThemes().forEach(t => allThemes.push(t));

    return allThemes;
}

function getLocalThemes() {
    let themes = localStorage.getItem(UI_THEMES);
    return themes ? JSON.parse(themes) : [];
}

function getThemeLink() {
    let themeLink = document.getElementById("theme");
    return (themeLink && themeLink.rel === "stylesheet") ? themeLink : undefined;
}

function loadServerTheme(theme) {
    console.log("loading server theme: " + theme);
    ui.setButtonDisabled("settings.remove", true); // we can't remove server themes

    let themeLink = getThemeLink();
    if (themeLink) {
        themeLink.href = "ui_theme.css?theme=" + theme;
        checkThemeLoaded(theme);
    }
}

function checkThemeLoaded(theme) {
    let docStyle = getComputedStyle(document.documentElement);
    let curTheme = docStyle.getPropertyValue("--theme").trim();
    curTheme = curTheme.substring(1, curTheme.length - 1); // strip the '"'

    if (curTheme == theme) { // strip the double-quotes
        console.log("theme loaded: " + theme);
        themeCss = getThemeCss();
        ui.setButtonDisabled("settings.remove", !theme.startsWith(LOCAL));
        ui.notifyThemeChangeHandlers();
    } else {
        setTimeout(() => checkThemeLoaded(theme), 500);
    }
}

function themeChanged() {
    if (themeVars) clearThemeVars();
}

function clearThemeVars() {
    themeVars = null;
    ui.setListItems(themeVarView, null);
    ui.setField(themeVarEntry, null);
    ui.setButtonDisabled("settings.save", true);
}

function loadLocalTheme(theme) {
    let v = localStorage.getItem(theme);
    if (v) {
        ui.setButtonDisabled("settings.remove", false); // we can remove local themes
        console.log("switch to local theme: " + theme);

        let cssBlob = new Blob([v]);
        let themeLink = getThemeLink();
        if (themeLink) {
            themeLink.href = URL.createObjectURL(cssBlob);
            checkThemeLoaded(theme);
        }
        /*
        let tvs = JSON.parse(v);
        if (tvs) {
            Object.getOwnPropertyNames(tvs).forEach(name => {
                console.log(name + " -> " + tvs[name]);
                let key = "--" + name;
                themeCss.cssRules[0].styleMap.set(key, tvs[name]);
            });
            ui.notifyThemeChangeHandlers();
        }
        */
    } else {
        console.log("unknown local theme: " + theme);
    }
}


function getThemeVars() {
    let themeVars = {}

    if (themeCss) {
        console.log("@@@ ", themeCss);
        let cssRules = themeCss.cssRules[0]; // themeCss only has custom properties, no rules
        for (var k = 0; k < cssRules.style.length; k++) {
            let name = cssRules.style[k];
            if (name.startsWith('--')) {
                let k = name.substring(2);
                let v = cssRules.style.getPropertyValue(name);
                //console.log(name + " = '" + v + "'");

                themeVars[k] = v;
            }
        }
    }

    return themeVars;
}

function getCss(tvs) {
    let css = ":root {\n";
    Object.getOwnPropertyNames(tvs).forEach(name => {
        css += "--";
        css += name;
        css += ": ";
        css += (name == "theme") ? `"${tvs[name]}"` : tvs[name];
        css += ";\n";
    });
    css += "}"
    return css;
}

ui.exportToMain(function editTheme() {
    themeVars = getThemeVars();
    ui.setListItems(themeVarView, Object.getOwnPropertyNames(themeVars));
    ui.setButtonDisabled("settings.save", false);
});

ui.exportToMain(function selectThemeVar(event) {
    let varName = event.detail.curSelection;
    if (varName) {
        ui.setField(themeVarEntry, themeVars[varName]);
    }
});

ui.exportToMain(function themeVarChange(event) {
    let newValue = ui.getFieldValue(event);
    let selectedThemeVar = ui.getSelectedListItem(themeVarView);

    if (selectedThemeVar) {
        themeVars[selectedThemeVar] = newValue;
        //document.documentElement.style.setProperty("--" + selectedThemeVar, newValue);
        let key = "--" + selectedThemeVar;
        themeCss.cssRules[0].styleMap.set(key, newValue);
        ui.updateListItem(themeVarView, selectedThemeVar);
    }
});

const colors = {
    "aliceblue": "#f0f8ff",
    "antiquewhite": "#faebd7",
    "aqua": "#00ffff",
    "aquamarine": "#7fffd4",
    "azure": "#f0ffff",
    "beige": "#f5f5dc",
    "bisque": "#ffe4c4",
    "black": "#000000",
    "blanchedalmond": "#ffebcd",
    "blue": "#0000ff",
    "blueviolet": "#8a2be2",
    "brown": "#a52a2a",
    "burlywood": "#deb887",
    "cadetblue": "#5f9ea0",
    "chartreuse": "#7fff00",
    "chocolate": "#d2691e",
    "coral": "#ff7f50",
    "cornflowerblue": "#6495ed",
    "cornsilk": "#fff8dc",
    "crimson": "#dc143c",
    "cyan": "#00ffff",
    "darkblue": "#00008b",
    "darkcyan": "#008b8b",
    "darkgoldenrod": "#b8860b",
    "darkgray": "#a9a9a9",
    "darkgreen": "#006400",
    "darkkhaki": "#bdb76b",
    "darkmagenta": "#8b008b",
    "darkolivegreen": "#556b2f",
    "darkorange": "#ff8c00",
    "darkorchid": "#9932cc",
    "darkred": "#8b0000",
    "darksalmon": "#e9967a",
    "darkseagreen": "#8fbc8f",
    "darkslateblue": "#483d8b",
    "darkslategray": "#2f4f4f",
    "darkturquoise": "#00ced1",
    "darkviolet": "#9400d3",
    "deeppink": "#ff1493",
    "deepskyblue": "#00bfff",
    "dimgray": "#696969",
    "dodgerblue": "#1e90ff",
    "firebrick": "#b22222",
    "floralwhite": "#fffaf0",
    "forestgreen": "#228b22",
    "fuchsia": "#ff00ff",
    "gainsboro": "#dcdcdc",
    "ghostwhite": "#f8f8ff",
    "gold": "#ffd700",
    "goldenrod": "#daa520",
    "gray": "#808080",
    "green": "#008000",
    "greenyellow": "#adff2f",
    "honeydew": "#f0fff0",
    "hotpink": "#ff69b4",
    "indianred ": "#cd5c5c",
    "indigo": "#4b0082",
    "ivory": "#fffff0",
    "khaki": "#f0e68c",
    "lavender": "#e6e6fa",
    "lavenderblush": "#fff0f5",
    "lawngreen": "#7cfc00",
    "lemonchiffon": "#fffacd",
    "lightblue": "#add8e6",
    "lightcoral": "#f08080",
    "lightcyan": "#e0ffff",
    "lightgoldenrodyellow": "#fafad2",
    "lightgrey": "#d3d3d3",
    "lightgreen": "#90ee90",
    "lightpink": "#ffb6c1",
    "lightsalmon": "#ffa07a",
    "lightseagreen": "#20b2aa",
    "lightskyblue": "#87cefa",
    "lightslategray": "#778899",
    "lightsteelblue": "#b0c4de",
    "lightyellow": "#ffffe0",
    "lime": "#00ff00",
    "limegreen": "#32cd32",
    "linen": "#faf0e6",
    "magenta": "#ff00ff",
    "maroon": "#800000",
    "mediumaquamarine": "#66cdaa",
    "mediumblue": "#0000cd",
    "mediumorchid": "#ba55d3",
    "mediumpurple": "#9370d8",
    "mediumseagreen": "#3cb371",
    "mediumslateblue": "#7b68ee",
    "mediumspringgreen": "#00fa9a",
    "mediumturquoise": "#48d1cc",
    "mediumvioletred": "#c71585",
    "midnightblue": "#191970",
    "mintcream": "#f5fffa",
    "mistyrose": "#ffe4e1",
    "moccasin": "#ffe4b5",
    "navajowhite": "#ffdead",
    "navy": "#000080",
    "oldlace": "#fdf5e6",
    "olive": "#808000",
    "olivedrab": "#6b8e23",
    "orange": "#ffa500",
    "orangered": "#ff4500",
    "orchid": "#da70d6",
    "palegoldenrod": "#eee8aa",
    "palegreen": "#98fb98",
    "paleturquoise": "#afeeee",
    "palevioletred": "#d87093",
    "papayawhip": "#ffefd5",
    "peachpuff": "#ffdab9",
    "peru": "#cd853f",
    "pink": "#ffc0cb",
    "plum": "#dda0dd",
    "powderblue": "#b0e0e6",
    "purple": "#800080",
    "rebeccapurple": "#663399",
    "red": "#ff0000",
    "rosybrown": "#bc8f8f",
    "royalblue": "#4169e1",
    "saddlebrown": "#8b4513",
    "salmon": "#fa8072",
    "sandybrown": "#f4a460",
    "seagreen": "#2e8b57",
    "seashell": "#fff5ee",
    "sienna": "#a0522d",
    "silver": "#c0c0c0",
    "skyblue": "#87ceeb",
    "slateblue": "#6a5acd",
    "slategray": "#708090",
    "snow": "#fffafa",
    "springgreen": "#00ff7f",
    "steelblue": "#4682b4",
    "tan": "#d2b48c",
    "teal": "#008080",
    "thistle": "#d8bfd8",
    "tomato": "#ff6347",
    "turquoise": "#40e0d0",
    "violet": "#ee82ee",
    "wheat": "#f5deb3",
    "white": "#ffffff",
    "whitesmoke": "#f5f5f5",
    "yellow": "#ffff00",
    "yellowgreen": "#9acd32"
};