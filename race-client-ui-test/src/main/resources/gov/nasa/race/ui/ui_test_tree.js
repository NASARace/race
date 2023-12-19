/*
 * Copyright (c) 2016, United States Government, as represented by the
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