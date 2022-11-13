 // reg test for ui_data.js - run by executing
 //   cd race/race-client-ui/src/test/javascript
 //   node test_treenode.js
 
 import { TreeNode, ExpandableTreeNode } from '../../main/resources/gov/nasa/race/ui/ui_data.js';
 import assert from 'assert/strict';

 function showNode(n) { console.log(n.nodePrefix() + n.name + " (" + n.isExpanded + ")"); }

 function testTreeNode() {
    let root = new ExpandableTreeNode("<root>",null,true);

    root.sortInPathName("infrastructure/powerlines/bay-area");
    root.sortInPathName("infrastructure/fire-stations/scc");
    root.sortInPathName("infrastructure/sub-stations/scc");
    root.sortInPathName("area/two", "Two");
    root.sortInPathName("area/one", "One");
    
    root.collapseAll();
    root.expandChildren();
    
    console.log("\n-- list all")
    root.depthFirstChildren(showNode);

    console.log("\n-- print list of expanded children")
    root.collapseAll();

    root.expand();
    root.expandChildren();
    root.expandedDescendants().forEach(showNode);

    console.log("\n-- find and expand 'fire-stations'");
    let n = root.findFirst(n=> n.name === "fire-stations");
    showNode(n);
    n.expand();
    console.log("\n-- now expanded descendants are:");
    root.expandedDescendants().forEach(showNode);

 }

 function testTreeConstruction() {
    console.log("\n--- test tree construction");
    let items = [
        { pathName: "infrastruct/powerlines/ca" },
        { pathName: "infrastruct/celltowers/ca"},
        { pathName: "infrastruct/substations/ca"},
        { pathName: "emergency/hospitals/ca"},
        { pathName: "emergency/firestations/ca"},
        { pathName: "local/plan" }
    ];
    let root = ExpandableTreeNode.from(items);

    //root.depthFirstChildren(showNode);
    root.expandedDescendants().forEach(showNode);
 }

function testOrderedTreeConstruction() {
   console.log("\n--- test ordered tree construction");
   let items = [
       { pathName: "infrastruct/powerlines/ca" },
       { pathName: "infrastruct/celltowers/ca"},
       { pathName: "emergency/hospitals/ca"}, // note that still preserves order on each level
       { pathName: "infrastruct/substations/ca"},
       { pathName: "emergency/firestations/ca"},
       { pathName: "local/plan" }
   ];
   let root = ExpandableTreeNode.fromPreOrdered(items);
   root.expandAll()
   root.forEach(showNode);
}

 testTreeNode();
 testTreeConstruction();
 testOrderedTreeConstruction();