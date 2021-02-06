import * as tabdata from './app/tabdata.js';

/**
  * the main module that is directly referenced from the page
  *
  * this should not contain any export functions or data used by other modules
  */

const main = {}

//--- onload callback
main.init = function init() {
  tabdata.initTabData();
}

//--- onunload callback
main.shutdown = function shutdown() {
  tabdata.shutdownTabData();
}

//--- resize callback
main.setWidth = function setWidth() {
  tabdata.setWidth();
}

main.identifyUser = function identifyUser(event) {
  if (event.key=="Enter") {
    tabdata.setEditable();
  }
}

main.setEditable = function setEditable() {
  tabdata.setEditable();
}

main.setReadOnly = function setReadOnly() {
  tabdata.setReadOnly();
}

main.sendChanges = function sendChanges() {
  tabdata.sendChanges();
}

main.setFilters = function setFilters() {
  tabdata.setFilters();
}

main.clearFilters = function clearFilters() {
  tabdata.clearFilters();
}

main.enterDisplayLines = function enterDisplayLines(event) {
  if (event.key=="Enter") tabdata.setDisplayLines();
}

main.enterCheckInterval = function enterCheckInterval(event) {
  if (event.key=="Enter") tabdata.setCheckInterval();
}

main.setDisplay = function setDisplay() {
  tabdata.setDisplayLines();
  tabdata.setCheckInterval();
}

main.resetDisplay = function resetDisplay() {
}

main.highlightRow = function highlightRow(idx,isSelected) {
  tabdata.highlightRow(idx,isSelected);
}

main.highlightColumn = function highlightColumn(idx,isSelected) {
  tabdata.highlightColumn(idx,isSelected);
}

main.clickRow = function clickRow(idx) {
  tabdata.clickRow(idx);
}

main.setRowFocused = function setRowFocused(event) {
  tabdata.setRowFocused(event);
}

main.setRowModified = function setRowModified(event) {
  tabdata.setRowModified(event);
}

main.setRowBlurred = function setRowBlurred(event) {
  tabdata.setRowBlurred(event);
}

main.clickCell = function clickCell (event,colIdx,rowIdx) {
  tabdata.clickCell(event,colIdx,rowIdx);
}

window.main = main; // make it global so that we can reference from HTML


