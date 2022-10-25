import * as config from "./config.js";
import * as util from "./ui_util.js";
import * as ui from "./ui.js";
import { ExpandableTreeNode } from "./ui_data.js";

var tree = undefined;
var treeView = undefined;

ui.registerLoadFunction(function initialize() {
    tree = ExpandableTreeNode.from(config.sources);
    treeView = initTreeView();

    ui.setTree(treeView, tree);
});

function initTreeView() {
    let view = ui.getTreeList("test.tree");
    if (view) {
        ui.setListItemDisplayColumns(view, ["header"], [
            { name: "show", tip: "toggle visibility", width: "2.5rem", attrs: ["alignRight"], map: e => ui.createCheckBox(false, toggleShowSource) }
        ]);
    }
    return view;
}

ui.exportToMain( function selectSource(event) {
    let e = event.detail.curSelection;
    console.log("selected item: ", e);
});

function toggleShowSource (event){
    let cb = ui.getCheckBox(event.target);
    if (cb) {
        let showIt = ui.isCheckBoxSelected(cb);
        let src = ui.getListItemOfElement(cb);
        if (src) {
            console.log( showIt ? "show " : "hide ", src);
        }
    }
}