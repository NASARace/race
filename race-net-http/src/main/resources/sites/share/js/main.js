import * as share from './app/share.js';

/**
  * the main module that is directly referenced from the page
  *
  * this should not contain any export functions or data used by other modules
  */

//--- globals

// the websocket authentication handler set by the application
var wsAuthHandler = undefined  // function (obj): boolean

const main = {}

//--- onload callback
main.init = function init() {
  share.init();
}

//--- onunload callback
main.shutdown = function shutdown() {
  share.shutdownTabData();
}


main.identifyUser = function identifyUser(event) {
  if (event.key=="Enter") {
    share.requestUserPermissions();
  }
}

main.setEditable = function setEditable() {
  share.requestUserPermissions();
}

main.setReadOnly = function setReadOnly() {
  share.setReadOnly();
}

main.sendChanges = function sendChanges() {
  share.sendChanges();
}

main.setFilters = function setFilters() {
  share.setFilters();
}

main.clearFilters = function clearFilters() {
  share.clearFilters();
}

main.highlightRow = function highlightRow(idx,isSelected) {
  share.highlightRow(idx,isSelected);
}

main.highlightColumn = function highlightColumn(idx,isSelected) {
  share.highlightColumn(idx,isSelected);
}

main.clickRow = function clickRow(idx) {
  share.clickRow(idx);
}

main.setRowFocused = function setRowFocused(event) {
  share.setRowFocused(event);
}

main.setRowModified = function setRowModified(event) {
  share.setRowModified(event);
}

main.setRowBlurred = function setRowBlurred(event) {
  share.setRowBlurred(event);
}

main.highlightCell = function highlightCell(event,colIdx,rowIdx,isSelected) {
  share.highlightCell(event,colIdx,rowIdx,isSelected);
}

main.clickCell = function clickCell (event,colIdx,rowIdx) {
  share.showCellInfo(event,colIdx,rowIdx);
}

main.showColumnList = function showColumnList (event) {
  share.showColumnList();
}

main.showRowList = function showRowList (event) {
  share.showRowList();
}

main.sendAndExitEditMode = function sendAndExitEditMode (event) {
  if (event.key=="Enter" && event.ctrlKey) {
    share.sendAndExitEditMode();
  }
}

main.logout = function logout () {
  share.sendLogout();
}

window.main = main; // make it global so that we can reference from HTML


