import { initWebSocket } from './websocket.js';
import * as glob from './glob.js';
import * as utils from './utils.js';

/**
  * this is the main tabdata application module which holds and manipulates the data model
  */

//--- module globals (not exported)

var uid = null;        // session-fixed UID
var nodeList = {};     // { ... "upstream": [{ "id": <s>, "info": <s>}, ..], "peer": [..], "downstream": [..] }

var rowList = {};      // { id:"s", info:"s", date:Date, rows:[ {id:"s",info:"s" <,header:"s">}.. ] }
var rows = [];         // rowList.rows to display

var columnList = {};   // { id:"s", info:"s", date:Date, columns:[ {id:"s",info:"s"}.. ] }
var columns = [];      // columnList.columns to display

var data = {};         // { <columnId>: {id:"s", date:Date, rows:{<rowId>:n,...} } }

var upstreamId = undefined;
var violatedConstraints = {}; // map of all current constraint violations: id -> cInfo
var cellConstraints = new WeakMap();  // assoc map of violated constraints associated with a cell: cell -> cInfo
var onlineNodes = new Set();

var isConnected = false;

var inEditMode = false; 
var modifiedRows = new Set();

var ws = {}; // will be set by initWebSocket()

var maxLines = 0;           // we get the default from the css

var maxIdleEditMillis = 150000;  // after which we switch back into r/o mode

//--- the number formatters to use for display
var intFormatter = Intl.NumberFormat(utils.language());
var intRatFormatter = Intl.NumberFormat(utils.language(),{minimumFractionDigits:1});
var ratFormatter = Intl.NumberFormat(utils.language(),{compactFormat: "short"}); // the default number formatter
var ratIntFormatter = Intl.NumberFormat(utils.language(),{maximumFractionDigits:0});


function nodeId () {
  return nodeList ? nodeList.self.id : undefined;
}

//--- exported functions (used by main module)

export function init() {
  setBrowserSpecifics();

  initDefaultValues();

  if (hasRows() && hasColumns()){
     initTable();
     setData();
  }

  ws = initWebSocket(handleWsOpen, handleWsMessage, handleWsClose);
  utils.log("view initialized");
}

export function shutdownTabData() {
  if (ws) {
    ws.close();
  }
}

// this only requests user permissions from the server
export function requestUserPermissions() {
  if (inEditMode) {
    alert("already in edit mode");

  } else {
    var uid = document.getElementById("uid").value;  // uid in request is optional
    sendRequestUserPermissions(uid);
  }
}

// end the edit mode
export function setReadOnly() {

  if (modifiedRows.size > 0) { // if we still have un-published changes give user a chance to send them
    if (confirm("send modified rows")) sendChanges();
  }

  setInactiveChecks(null);

  const elem = document.getElementById("uid");
  elem.classList.remove("active");
  elem.disabled = false;  // make it editable again

  const uid = elem.value;

  document.getElementById("sendButton").disabled = true;
  document.getElementById("editButton").disabled = false;
  document.getElementById("readOnlyButton").disabled = true;

  processCells( (cell,column,row) => {
    if (isEditorCell(cell)) {
      var values = data[column.id].rows;
      setDisplayCell( cell, row, values[row.id]);
    }
  });

  modifiedRows.clear();
  inEditMode = false;

  sendEndEdit(uid);

  utils.log("exit edit mode");
}

export function sendLogout (){
  location.replace("logout");
}

// shortcut for send+exit
export function sendAndExitEditMode () {
  sendChanges();
  setReadOnly();
}

export function sendChanges() {
  if (modifiedRows.size == 0){
    alert("no modified rows yet - nothing to send");

  } else {
    var uid=document.getElementById("uid").value;

    if (utils.isEmpty(uid)) {
      // this should not happen - the input was disabled when we entered edit mode
      alert("please enter user credentials before sending changes");

    } else {
      var tbody = document.getElementById("table_body");

      for (var i=0; i<columns.length; i++){
        var column = columns[i];
        var acc = "";

        for (var j=0; j<rows.length; j++){
          var row = rows[j];
          var tr = tbody.childNodes[j];
          var cell = tr.childNodes[i+1];
          var editElem = cell.firstChild;
          if (modifiedRows.has(editElem)){
            if (acc.length > 0) acc += ",";
            acc += `"${row.id}":${changedRowValue(row,editElem)}`
            editElem.classList.remove("modified");
            editElem.classList.remove("conflict");
            editElem.classList.add("reported");
            modifiedRows.delete(editElem);
          }
        }

        if (acc.length > 0) {
          sendUserChange(uid,Date.now(),column,acc);
        }
      }
    }
  }
}

function changedRowValue (row, editElem) {
  switch (row.type) {
    case "integer":
    case "real":
    case "boolean":
      return editElem.value;

    default:
      return JSON.stringify(editElem.value);
  }
}

export function setFilters () {
  columns = filterColumns();
  rows = displayRows(filterRows());

  initTable();
  setData();
}

export function clearFilters () {
  document.getElementById("columnFilter").value = "";
  document.getElementById("rowFilter").value = "";

  columns = columnList.columns;
  rows = displayRows(filterRows());

  initTable();
  setData();
}


//--- internal functions

function setBrowserSpecifics() {
  if (navigator.userAgent.search("Chrome") >= 0){
    document.getElementById("tableContainer").classList.add("chrome")
  }
}

function initDefaultValues (e) {
  var h = window.innerHeight;

  var cssVal = getComputedStyle(document.body).getPropertyValue("--cell-height");
  var ch = utils.convertCSSsizeToPx(cssVal);

  //maxLines = parseInt(getComputedStyle(document.body).getPropertyValue("--max-lines"));
  maxLines = Math.trunc((h * 0.6) / ch) - 2; 
}

function hasRows() {
  return (rowList && !utils.isEmpty(rowList.rows));
}

function hasColumns() {
  return (columnList && !utils.isEmpty(columnList.columns));
}

function isHeader (row) {
  return (row.attrs && row.attrs.includes("header"));
}

function isLocked (row) {
  return (row.attrs && row.attrs.includes("locked"));
}

function isHidden (row) {
  return (row.attrs && row.attrs.includes("hidden"));
}

function isComputed (row) {
  return row.hasOwnProperty("formula");
}

function isLocalColumn (col) {
  return isColumnOwnedBy(col,nodeId());
}

function isColumnOwnedBy (col, nid) {
  return (col.owner == nid) || (col.owner == "<self>" && nid == nodeId()) || (col.owner == "<up>" && nid == upstreamId);
}

function isColumnOnline (col) {
  for (const n of onlineNodes.values()) {
    if (isColumnOwnedBy(col,n)) return true;
  }
  return false;
}

function isEditorCell (cell) {
  return (cell.firstChild instanceof HTMLInputElement);
}

//--- initialization of nodeLists structure

function initNodeLists() {
  var nlRoot = document.getElementById("nodeListContainer");
  utils.removeAllChildren(nlRoot);
  initNodeList("upstream", nodeList.upstream, nlRoot);
  initNodeList("peer", nodeList.peer, nlRoot);
  initNodeList("downstream", nodeList.downstream, nlRoot);
}

function initNodeList(name, list, rootElement) {
  if (list && list.length > 0) {
    const listElem = document.createElement('div');
    listElem.classList.add('nodelist');
    listElem.setAttribute('id', name);

    const label = document.createElement('span');
    label.classList.add('nodelist-label');
    label.textContent = name;
    listElem.appendChild(label);

    for (const nodeInfo of list.values()) {
      const elem = document.createElement('span');
      elem.classList.add('nodename');
      elem.setAttribute('id', nodeInfo.id);
      elem.textContent = utils.nameOfPath(nodeInfo.id);
      listElem.appendChild(elem);
    }

    rootElement.appendChild(listElem);
  }
}

//--- initialization of table structure

function initTable() {
  var table = document.getElementById('my_table');
  utils.removeAllChildren(table);  // in case one of the catalogs got updated

  //table.appendChild( initColGroup());
  table.appendChild( initTHead());
  table.appendChild( initTBody());
}

function initColGroup() {
  var colGroup = document.createElement('colgroup');

  var col = document.createElement("col"); // the row id row
  col.classList.add("row");
  colGroup.appendChild(col);

  for (var i=0; i<columns.length; i++){
    var column = columns[i];

    col = document.createElement("col");
    if (column.id == nodeId) {
      col.classList.add("local");
    }
    colGroup.appendChild(col);
  }

  return colGroup;
}

function initTHead() {
  //--- create table header
  var thead = document.createElement('thead');
  thead.setAttribute('id', 'table_head');

  thead.appendChild(createInfoRow());
  thead.appendChild(createColumnNameRow());
  thead.appendChild(createColumnUpdateRow());

  return thead;
}

function createInfoRow () {
  var row = document.createElement('tr');

  var cell = document.createElement('th');
  cell.classList.add("info","label");
  cell.textContent = "selected";
  row.appendChild(cell);

  cell = document.createElement('th');
  cell.setAttribute("id", "info");
  cell.classList.add('info');
  cell.setAttribute('colspan', columns.length);
  row.appendChild(cell);

  return row;
}

function createColumnNameRow () {
  var row = document.createElement('tr');
  row.setAttribute("id", "column_names");

  var cell = document.createElement('th');
  cell.classList.add("name","label");
  row.appendChild(cell); // the row-id column

  for (var i=0; i<columns.length; i++){
    var column = columns[i];

    cell = document.createElement('th');
    cell.classList.add("name");
    if (isLocalColumn(column))   cell.classList.add("local");

    cell.setAttribute("onmouseenter", `main.highlightColumn(${i+1},true)`);
    cell.setAttribute("onmouseleave", `main.highlightColumn(${i+1},false)`);
    cell.textContent = utils.nameOfPath(column.id);

    row.appendChild(cell);
  }
  return row;
}

function createColumnUpdateRow () {
  var row = document.createElement('tr');
  row.setAttribute("id", "column_dtgs");

  var cell = document.createElement('th'); // blank
  cell.classList.add("dtg","label");
  cell.textContent = "update"
  row.appendChild(cell);

  for (var i=0; i<columns.length; i++){
    var column = columns[i];

    // no content yet (will be set when we get a columnData), just create cell
    cell = document.createElement('th');
    cell.classList.add("dtg");
    if (isLocalColumn(column))   cell.classList.add("local");

    cell.setAttribute("onmouseenter", `main.highlightColumn(${i+1},true)`);
    cell.setAttribute("onmouseleave", `main.highlightColumn(${i+1},false)`);
    cell.textContent = "00:00:00"

    row.appendChild(cell);
  }
  return row;
}

function initTBody () {
  var tbody = document.createElement("tbody");
  tbody.setAttribute("id","table_body");

  for (var i=0; i<rows.length; i++){
    tbody.appendChild( initRow(rows[i], i));
  }
  return tbody;
}

function initRow (row, idx) {
  var tr = document.createElement('tr');

  if (isHeader(row)) {
    tr.classList.add("header");
  }

  if (isComputed(row)) {
    tr.classList.add("computed");
  }

  var cell = document.createElement('th');
  cell.classList.add("tooltip");
  cell.setAttribute("onmouseenter", `main.highlightRow(${idx},true)`);
  cell.setAttribute("onmouseleave", `main.highlightRow(${idx},false)`);
  cell.setAttribute("onclick", `main.clickRow(${idx})`);

  if (row.level > 0) {
    cell.style.paddingLeft = `${row.level}rem`;
  }
  var rowLabel = row.label;
  if (row.isCollapsed) rowLabel += " …";
  cell.textContent = rowLabel;
  
  tr.appendChild(cell);

  var colIdx = 0;
  for (var p of columns){
    // no data yet, will be set when we get a columnData message
    var cell = document.createElement('td');
    cellConstraints.set( cell, []);  // our own property to keep track of associated constraints that are violated
    
    //cell.setAttribute("onclick", `main.clickCell(event,${colIdx},${idx})`);
    cell.setAttribute("onmouseenter", `main.highlightCell(event,${colIdx},${idx},true)`);
    cell.setAttribute("onmouseleave", `main.highlightCell(event,${colIdx},${idx},false)`);

    if (isLocalColumn(p)) cell.classList.add("local");
    tr.appendChild(cell);
    colIdx += 1;
  }
  return tr;
}

function checkEditableCell (cell,column,columnPatterns,row,rowPatterns) {
  var i;
  for (var i=0; i<columnPatterns.length;i++) {  // note there can be more than one columnPattern match
    if (columnPatterns[i].test(column.id)) {
      if (rowPatterns[i].test(row.id)) {
        setEditCell(cell,row, data[column.id].rows[row.id]);
        return true; // we only set it editable once
      }
    }
  }
  return false;
}

function setRowsEditable (permissions) {
  var tbody = document.getElementById('table_body');
  var columnPatterns = [];
  var rowPatterns = [];

  permissions.forEach( (p) => {
    columnPatterns.push( glob.glob2regexp(p.colPattern)); 
    rowPatterns.push( glob.glob2regexp(p.rowPattern)); 
  });

  var hasEditableRows = false;

  for (var i=0; i<columns.length; i++) {
    var column = columns[i];
    if (columnPatterns.find( r=> r.test(column.id))) { // only iterate over rows for columns that have at least one match
      for (var j=0; j<rows.length; j++){
        var row = rows[j];
        if (!isLocked(row)) {
          var tr = tbody.childNodes[j];
          var cell = tr.childNodes[i+1];
          if (checkEditableCell(cell,column,columnPatterns,row,rowPatterns)) hasEditableRows = true;
        }
      }
    }
  }

  if (hasEditableRows) {
    document.getElementById("editButton").disabled = true;
    document.getElementById("sendButton").disabled = false;
    document.getElementById("readOnlyButton").disabled = false;
    inEditMode = true;
    setInactiveChecks(setReadOnly,maxIdleEditMillis);
  }
}

function setInactiveChecks (action,maxIdleMillis = 150000) {
  var time;

  if (action) {
    document.addEventListener('mousemove', resetIdleTimer);
    document.addEventListener('keypress', resetIdleTimer);
    document.getElementById("tableContainer").addEventListener('scroll', resetIdleTimer);
  } else {
    document.removeEventListener('mousemove', resetIdleTimer);
    document.removeEventListener('keypress', resetIdleTimer);
    document.getElementById("tableContainer").removeEventListener('scroll', resetIdleTimer);
  }

  function resetIdleTimer() {
    clearTimeout(time);
    time = setTimeout(action, maxIdleMillis)
  }
}

function setCellConstraint (cr, cInfo, isAdd) {
  var tbody = document.getElementById('table_body');
  var colId = cr.col;
  var rowId = cr.row;
  var level = cInfo.level;

  var colIdx = columnIndex(colId)+1;
  if (colIdx >= 1) {
    var rowIdx = rowIndex(rowId);
    if (rowIdx >= 0) {
      var tr = tbody.childNodes[rowIdx];
      var cell = tr.childNodes[colIdx];
      var constraintCls = "constraintLevel_" + level;
      var ccs = cellConstraints.get(cell);
      
      if (isAdd) {
        cell.classList.add(constraintCls);
        ccs.push(cInfo);
      } else {
        cell.classList.remove(constraintCls);
        var idx = ccs.findIndex( (ci) => ci.id == cInfo.id );
        if (idx >= 0) ccs.splice(idx,1);
      }
    }
  }
}

function setData() {
  if (columns.length > 0 && rows.length > 0) {
    for (var i=0; i<columns.length; i++){
      var column = data[columns[i].id];
      if (column){
        setColumnData(i,column);
      }
    }
  }
}

function setCell (cell, row, values) {
  if (isEditorCell(cell)) { // we are editing this
    var editElem = cell.firstChild;
    if (modifiedRows.has(editElem)){ // we already changed it - flag conflict
      editElem.classList.add("conflict");
    }
    editElem.classList.remove("reported");
    setEditorValue(editElem,values[row.id]);

  } else { // just a display cell
    setDisplayCell(cell, row, values[row.id]);
  }
}

// change online status of a single column
function setColumnOnlineStatus (colId, isOnline) {
  var colIdx = columnIndex(colId);

  if (colIdx >= 0) {
    var trDtgs = document.getElementById('column_dtgs');
    if (isOnline) {
      trDtgs.childNodes[colIdx+1].classList.add("online");
    } else {
      trDtgs.childNodes[colIdx+1].classList.remove("online");
    }
  }
}

// set online stati of all columns according to onlineNodes
function updateColumnOnlineStatus () {
  columns.forEach( col=> setColumnOnlineStatus(col.id,isColumnOnline(col)));
}

function setColumnData (i, columnData, filterRow) {
  var tbody = document.getElementById('table_body');
  var trDtgs = document.getElementById('column_dtgs');
  var i1 = i + 1;
  var values = columnData.rows;

  trDtgs.childNodes[i1].textContent = utils.timeString(columnData.date);

  for (var j=0; j<rows.length; j++){
    var row = rows[j];
    if (filterRow && !filterRow(row)) continue;

    var tr = tbody.childNodes[j];
    var cell = tr.childNodes[i1];

    setCell(cell, row, values);
  }
}

function formatArray (v) {
  var s = JSON.stringify(v);
  if (s.length > 15) return "[..]";
  return s;
}

// set contents of a readonly cell
function setDisplayCell (cell, row, cv) {
  utils.removeAllChildren(cell);

  if (cv == undefined) {
    cell.innerText = "";

  } else {
    var v = cv.value;

    switch (row.type) {
      case "string":
        cell.innerText = v;
        break;

      case "boolean":
        cell.innerText = v ? "true" : "false";
        break;

      case "integer":
        cell.innerText = Number.isInteger(v) ? intFormatter.format(v) : ratIntFormatter.format(v);
        break;

      case "real": 
        cell.innerText = Number.isInteger(v) ? intRatFormatter.format(v) : ratFormatter.format(v);
        break;

      case "link":
        var a = document.createElement("a");
        a.href = v;
        a.target = "_blank";
        a.innerText = "<link>";
        cell.appendChild(a);
        break;

      case "integerList":
      case "realList":
        cell.innerText = formatArray(v);
        break;

      default:
        cell.innerText = "?";  // unknown type
    }
  }
}

function setEditCell (cell, row, cv) {
  utils.removeAllChildren(cell);

  switch (row.type) {
    case "string":
    case "link":
      setCellEditor(cell,row,formatStringValue(cv));
      break;

    case "boolean":
      setChoiceCell(cell, ["true","false"], formatStringValue(cv));
      break;

    case "integer":
      setCellEditor(cell,row,formatIntegerValue(cv));
      break;

    case "real": 
      setCellEditor(cell,row,formatRealValue(cv));
      break;

    case "integerList":
    case "realList":
      setTextInputCell(cell,formatJsonValue(cv));
      break;

    default:
      setTextInputCell(cell,formatJsonValue(cv)); // ?? should we issue a warning here
  }
}

function setCellEditor (cell,row,v) {
  if (row.values && row.values.length > 0) setChoiceCell(cell,row.values,v); else setTextInputCell(cell,v);
}

function formatStringValue (cv) {
  return cv ? cv.value : '';
}

function formatIntegerValue (cv) {
  return cv ? (Number.isInteger(cv.value) ? intFormatter.format(cv.value) : ratIntFormatter.format(cv.value)) : '';
}

function formatRealValue (cv) {
  return cv ? (Number.isInteger(cv.value) ? intRatFormatter.format(cv.value) : ratFormatter.format(cv.value)) : '';
}

function formatJsonValue (cv) {
  return cv ? JSON.stringify(cv.value) : '';
}

function setCellEditorAttributes (editElem) {
  editElem.setAttribute("class", "cell");
  editElem.setAttribute("onfocus", "main.setRowFocused(event)");
  editElem.setAttribute("onblur", "main.setRowBlurred(event)");
  editElem.setAttribute("onchange", "main.setRowModified(event)");
  editElem.setAttribute("onkeyup", "main.sendAndExitEditMode(event)")
}

function setTextInputCell (cell, v) {
  var input = document.createElement('input');
  input.setAttribute("type", "text");
  setCellEditorAttributes(input);
  input.value = v;

  cell.appendChild(input);
}

function setChoiceCell (cell, rowValues, v) {
  var select = document.createElement('select');
  setCellEditorAttributes(select);
  select.value = v;

  //--- undefined option
  var opt = document.createElement('option');
  opt.value = '';
  opt.text = '-';
  if (v == '') opt.select = true;
  select.appendChild(opt);

  //--- row defined options
  for (const vs of rowValues) {
    opt = document.createElement('option');
    opt.text = vs;
    if (v == vs) opt.selected = true;
    select.appendChild(opt);
  }
  cell.appendChild(select);
}

function setEditorValue (editElem, v) {
  // TBD - update the cell editor value
}

function columnIndex (colId) {
  for (var i=0; i<columns.length; i++){
    if (columns[i].id == colId) return i;
  }
  return -1;
}

function rowIndex (rowId) {
  for (var i=0; i<rows.length; i++) {
    if (rows[i].id == rowId) return i;
  }
  return -1;
}

function processCells (cellFunc){
  var tbody = document.getElementById("table_body");

  for (var i=0; i<rows.length; i++) {
    var row = rows[i];
    var tr = tbody.childNodes[i];
    for (var j=0; j<columns.length; j++){
      var column = columns[j];
      var cell = tr.childNodes[j+1];

      cellFunc( cell, column, row);
    }
  }
}

/**
 * this computes the rows from rowList that match the current rowFilter (glob pattern)
 */
function filterRows () {
  var pattern = document.getElementById("rowFilter").value;
  if (pattern) {
    var regex = glob.glob2regexp(pattern); //new RegExp(pattern)
    return rowList.rows.filter( f => regex.test(f.id) && !isHidden(f));
  } else {
    return rowList.rows.filter( f => !isHidden(f));
  }
}

/**
 * set display label and indentation level properties for each row object
 * This function could also add artificial header rows, i.e. could return a different
 * row array than what we pass in
 */
function displayRows (filteredRows) {
  var headerStack = []; // stack of header ids

  for (let r of filteredRows) {
    var rid = r.id;
    var pid = utils.parentOfPath(rid);

    while (headerStack.length>0 && utils.top(headerStack) != pid) headerStack.pop();
    if (headerStack.length == 0) {
      r.label = utils.isAbsolutePath(rid) ? rid.substring(1) : rid;
      r.level = 0;
    } else {
      r.label = utils.nameOfPath(rid);
      r.level = headerStack.length;
    }

    headerStack.push(rid);
  }

  return filteredRows;
}


function rowIdPrefix (row) {
  if (row.id.endsWith('/')) {
    return row.id;
  } else {
    return row.id + '/';
  }
}

function expandRow (i) {
  var row = rows[i];
  var idPrefix = rowIdPrefix(row);
  var k = i+1;

  if (row.isCollapsed) { // expand - add all children
    var allRows = rowList.rows;
    for ( var j = allRows.indexOf(row)+1; j < allRows.length; j++){
      var f = allRows[j];
      if (!isHidden(f) && f.id.startsWith(idPrefix)) {
        rows.splice(k,0,f);
        k += 1;
      } else break;
    }
    row.isCollapsed = false;

  } else { // collapse - remove all children
    while (k < rows.length && rows[k].id.startsWith(idPrefix)){
      rows.splice(k,1);
    }
    row.isCollapsed = true;
  }

  initTable();
  setData();
}

function filterColumns () {
  var pattern = document.getElementById("columnFilter").value;
  if (pattern) {
    var regex = glob.glob2regexp(pattern); //new RegExp(pattern);
    return columnList.columns.filter( p => regex.test(p.id))
  } else {
    return columnList.columns;
  }
}

//--- column/row highlighting

export function highlightColumn (i, isSelected) {
  var tbody = document.getElementById("table_body");
  var idCell = document.getElementById("column_names").childNodes[i];
  //var pathCell = document.getElementById("column_paths").childNodes[i];
  var dtgCell = document.getElementById("column_dtgs").childNodes[i];

  if (isSelected) {
    idCell.classList.add("selected");
    //pathCell.classList.add("selected");
    dtgCell.classList.add("selected");
    for (var row of tbody.childNodes) {
      row.childNodes[i].classList.add("selected");
    }
    showColumnInfo(columns[i-1]);
  } else {
    idCell.classList.remove("selected");
    //pathCell.classList.remove("selected");
    dtgCell.classList.remove("selected");
    for (var row of tbody.childNodes) {
      row.childNodes[i].classList.remove("selected");
    }
    utils.info(null);
  }
}

function showColumnInfo (column) {
  var txt = column.id + ": " + column.info;
  utils.info(txt);
}

export function highlightRow (i, isSelected) {
  var row = document.getElementById("table_body").childNodes[i];
  if (isSelected) {
    for (var cell of row.childNodes) {
      cell.classList.add( "selected");
    }
    showRowInfo(rows[i]);
  } else {
    for (var cell of row.childNodes) {
      cell.classList.remove("selected");
    }
    utils.info(null);
  }
}

function showRowInfo (row) {
  var txt = row.id + ": " + row.info;
  if (row.min || row.max){
    txt += ".         [";
    if (row.min) txt += row.min;
    txt += ' , '
    if (row.max) txt += row.max;
    txt += ']';
  }
  if (row.formula) {
    txt += ".         =";
    txt += row.formula;
  }
  utils.info(txt);
}

export function clickRow (i) {
  if (isHeader(rows[i])) {
    expandRow(i);
  }
}

function displayConstraints (ccs) {
  var msg = "";
  if (ccs && ccs.length > 0) {
    msg = "violated:"
    for (var i=0; i < ccs.length; i++) {
      if (i > 0) msg += ",";
      msg += ccs[i].info;
    }
  }

  return msg;
}

function displayCell (col,row) {
  return `${utils.nameOfPath(col.id)}::${row.id}`;
}


export function showCellInfo (event, colIdx, rowIdx) {
  var v = event.target.textContent;
  var cr = displayCell(columns[colIdx], rows[rowIdx]);
  var cs = displayConstraints(cellConstraints.get(event.target));

  utils.info( ` [${cr}] = ${v} ${cs}`);
}

export function highlightCell (event,colIdx,rowIdx,isSelected) {
  if (isSelected) {
    showCellInfo(event,colIdx,rowIdx);
  } else {
    utils.info('');
  }
}

//--- incoming messages

function handleWsOpen (evt) {
  isConnected = true;
  var status = document.getElementById("status");
  status.classList.add("ok");
  status.value = "connected";
  reconnectAttempt = 1;
}

function handleWsClose (evt) {
  isConnected = false;
  var status = document.getElementById("status");
  utils.swapClass(status,"ok","alert");
  status.value = "disconnected";

  checkForReconnect();
}

var maxReconnectAttempts = 1;
var reconnectAttempt = 1;

function checkForReconnect () {
  if (!isConnected) {
    if (reconnectAttempt >= maxReconnectAttempts) {
      console.log("giving up, no server");
      document.getElementById("status").value = "no server";
      return;
    } else {
      document.getElementById("status").value = "server check " + reconnectAttempt;
      reconnectAttempt += 1;
    }

    const req = new XMLHttpRequest();
    const url = utils.parentOfPath(document.URL);
    req.open("GET", url);
    req.send();

    //req.onerror = (e) => {
    //  console.log("server unresponsive: " + JSON.stringify(e));
    //}
    console.log("trying to reconnect.. " + reconnectAttempt);

    req.onreadystatechange = (e) => {
      if (req.status == 200) {
        console.log("reconnected - reloading document");
        location.reload();
      } else {
        setTimeout(checkForReconnect, 5000);
      }
    }
  }
}

// the websocket message handler (msg is the payload data object of the websocket message)
function handleWsMessage(msg) {
  //console.log(JSON.stringify(msg));
  var msgType = Object.keys(msg)[0];  // first member name

  switch (msgType) {
    //--- app specific messages
    case "columnDataChange": handleColumnDataChange(msg.columnDataChange); break;
    case "nodeList": handleNodeList( msg.nodeList); break;
    case "columnList": handleColumnList( msg.columnList); break;
    case "rowList": handleRowList( msg.rowList); break;
    case "columnData": handleColumnData( msg.columnData); break;
    case "constraintChange": handleConstraintChange(msg.constraintChange); break;
    case "nodeReachabilityChange": handleNodeReachabilityChange(msg.nodeReachabilityChange); break;
    case "upstreamChange": handleUpstreamChange(msg.upstreamChange); break;
    case "setUser": handleSetUser(msg.setUser); break;
    case "userPermissions": handleUserPermissions( msg.userPermissions); break;
    case "changeRejected": handleChangeRejected (msg.changeRejected); break;

    //--- general server notifications
    case "alert": handleAlert(msg.alert); break;
    case "terminateEdit": handleTerminateEdit(msg.terminateEdit); break;

    default:
      //console.log("checking " + JSON.stringify(msg) + " with " + window.wsAuthHandler);
      if (!(window.wsAuthHandler && window.wsAuthHandler(ws,msg))) {
        utils.log(`ignoring unknown message type ${msgType}`);
      }
  }
}

function handleSetUser (setUser) {
  let uid = setUser.uid;
  let clamped = setUser.clamped;

  let elem = document.getElementById('uid');
  elem.value = uid;
  if (clamped) {
    elem.classList.add("readonly");
    elem.disabled = true;
  }

  document.getElementById('logoutButton').disabled = false;
}

// { alert: { text:"s", log:<b>}}
function handleAlert (alertMsg){
  alert(alertMsg.text);
  if (alertMsg.log) utils.log(alertMsg.log);
}

function handleTerminateEdit (terminateMsg) {
  alert(terminateMsg.reason);
  utils.log("server terminated edit: " + terminateMsg.reason);
  setReadOnly();
}

// { columnDataChange: { columnId:"s", changeNodeId:"s", date:n, changedValues: { <rowId>: { value:X,date:n } ... }}}
function handleColumnDataChange (cdc) {
  // console.log(JSON.stringify(cdc));
  var i = columnIndex(cdc.columnId);
  if (i >= 0) {
    var cd = data[cdc.columnId];
    cd.date = new Date(cdc.date);
    var cvs = cdc.changedValues;
    var changedRowIds = Object.keys(cvs);

    changedRowIds.forEach( function (rowId,idx) {
      var cv = cvs[rowId];
      cd.rows[rowId] = cv;
    });

    setColumnData(i, cd, function(row) { return cvs.hasOwnProperty(row.id); });
  }
}

function handleConstraintChange (cc) {  
  //console.log(JSON.stringify(cc))
  if (cc.reset) violatedConstraints = {}

  if (cc.hasOwnProperty("resolved")) {
    var resolved = cc.resolved;
    for (var id in resolved) {
      var cInfo = resolved[id];
      cInfo.id = id;
      if (cInfo.hasOwnProperty("assoc")) {
        cInfo.assoc.forEach(  (cr) => setCellConstraint( cr, cInfo, false) );
      }
      delete violatedConstraints[id];
      utils.log("resolved: " + cInfo.id);
    }
  }

  if (cc.hasOwnProperty("violated")) {
    var violated = cc.violated;
    for (var id in violated) {
      var cInfo = violated[id];
      cInfo.id = id; // add the id for reverse lookup
      if (cInfo.hasOwnProperty("assoc")) {
        cInfo.assoc.forEach(  (cr) => setCellConstraint( cr, cInfo, true) );
      }
      violatedConstraints[id] = cInfo;
      utils.log("violated: " + cInfo.id);
    }
  }
}

function handleNodeReachabilityChange (nrc) {
  //console.log(JSON.stringify(nrc));
  if (nrc.online) nrc.online.forEach( id=> {
    onlineNodes.add(id);
    utils.setClass( document.getElementById(id), 'connected', true);
  });
  if (nrc.offline) nrc.offline.forEach( id=> {
    utils.setClass( document.getElementById(id), 'connected', false);
  });
  updateColumnOnlineStatus();
}


// {columnData:{id:"s",rev:n,date:n,rows:[<rowId>:n]}
function handleColumnData (columnData) {
  columnData.date = new Date(columnData.date) // convert epoch into Date object

  if (columnData.rows){
    if (data[columnData.id]) utils.log(`${columnData.id} data updated`);

    data[columnData.id] = columnData;
    if ((rows.length > 0) && (columns.length > 0)){
      var i = columnIndex(columnData.id);
      if (i>=0) {
        setColumnData(i, columnData);
      }
    }
  }
}

function handleNodeList (newNodeList) {
  nodeList = newNodeList
  
  document.getElementById("nodeId").value = nodeList.self.id;

  initNodeLists();
}


function handleUpstreamChange (upstreamChange) {
  console.log(JSON.stringify(upstreamChange));
  const elem = document.getElementById("upstreamId");
  if (upstreamChange.isOnline) {
    upstreamId = upstreamChange.id;
    elem.value = upstreamId;
    utils.log(`connected to upstream ${upstreamChange.id}`);
  } else {
    upstreamId = undefined;
    elem.value = "";
    utils.log("no upstream connection");
  }
  updateColumnOnlineStatus();
}

// {rowList:{rev:n,rows:[{id:"s",info:"s" <,header:"s">}..]}}
function handleRowList (newRowList) {
  rowList = newRowList;
  rows = displayRows(filterRows());

  document.getElementById("rowListId").value = rowList.id;
  //utils.setAndFitToText( document.getElementById("rowListId"), rowList.id, 7);

  if (hasColumns())  {
    initTable();
    setData();
  }
}

// {columnList:{ rev:n, columns:[ {id:"s",info:"s"}.. ]}}
function handleColumnList (newColumnList) {
  columnList = newColumnList;
  columns = filterColumns();

  document.getElementById("columnListId").value = columnList.id
  //utils.setAndFitToText( document.getElementById("columnListId"), columnList.id, 7);

  if (hasRows())  {
    initTable();
    setData();
  }
}

// {uid,permissions{}}
function handleUserPermissions (usrPerm) {
  //console.log(JSON.stringify(usrPerm));
  var uid = usrPerm.uid; // TODO - should we check if that is the current user? Not critical since update has to be checked by server anyways
  if (usrPerm.permissions.length > 0) {
    const elem = document.getElementById("uid");
    if (elem.value != uid) {
      elem.value = uid;
    }

    elem.classList.add("active");
    elem.disabled = true; // lock it until we are done

    setRowsEditable(usrPerm.permissions);
    utils.log(`enter edit mode for user ${uid}`);

  } else {
    var msg = `no edit permissions for user ${uid}`
    alert(msg);
    utils.log(msg);
  }
}

function handleChangeRejected (cr) {
  var msg = `change from user '${cr.uid}' rejected: ${cr.reason}`
  alert(msg);
  utils.log(msg);
}

//--- webauthn messages

// a PublicKeyCredential does not have own enumerable properties hence JSON.stringify() would return {}
// Plus we need to turn Uint8Arrays back into base64URL encoded strings
function createPkcToObject (pkc) {
  return {
    type: pkc.type,
    id: pkc.id,
    response: {
      attestationObject: utils.base64URLEncode(pkc.response.attestationObject),
      clientDataJSON: utils.base64URLEncode(pkc.response.clientDataJSON)
    },
    clientExtensionResults: pkc.getClientExtensionResults()
  };
}


function handleWebauthnReg (pkcCreateOptions) {
  //console.log(JSON.stringify(pkcCreateOptions));

  // convert the random strings from base64URL back into Uint8Arrays - the CredentialContainer will otherwise reject
  pkcCreateOptions.user.id = utils.base64URLDecode(pkcCreateOptions.user.id);
  pkcCreateOptions.challenge = utils.base64URLDecode(pkcCreateOptions.challenge);

  navigator.credentials.create({publicKey: pkcCreateOptions}).then(
    credential => { // this should be a PublicKeyCredential
      credential = createPkcToObject(credential);
      var msg = JSON.stringify(credential);
      ws.send(msg);
    },
    failure => {
      alert("credential creation rejected: " + failure);
    }
  );
}

function getPkcToObject (pkc) {
  return {
    type: pkc.type,
    id: pkc.id,
    response: {
      authenticatorData: utils.base64URLEncode(pkc.response.authenticatorData),
      clientDataJSON: utils.base64URLEncode(pkc.response.clientDataJSON),
      signature: utils.base64URLEncode(pkc.response.signature),
      userHandle: null // make the server look it up through uid/cached request - we don't want to give any assoc in the reply
    },
    clientExtensionResults: pkc.getClientExtensionResults()
  };
}

function handleWebauthnAuth (pkcRequestOptions) {
  //console.log(JSON.stringify(pkcRequestOptions));

  // convert the random strings from base64URL back into Uint8Arrays - the CredentialContainer will otherwise reject
  pkcRequestOptions.challenge = utils.base64URLDecode(pkcRequestOptions.challenge);
  for (const c of pkcRequestOptions.allowCredentials) {
    c.id = utils.base64URLDecode(c.id);
  }

  navigator.credentials.get({publicKey: pkcRequestOptions}).then(
    credential => {
      credential = getPkcToObject(credential);
      var msg = JSON.stringify(credential);
      ws.send(msg);
    },
    failure => {
      alert("credential request rejected: " + failure);
    }
  );
}

//--- outgoing messages

function sendRequestUserPermissions (uid){
  if (ws) {
    ws.send(`{"requestEdit": "${uid}"}`);
  }
}

function sendUserChange (uid,changeDate,column,changedRows) {
  if (ws) {
    var msg = `{"userChange":{ "uid": "${uid}","columnId": "${column.id}", "date":${changeDate}, "changedValues":{${changedRows}}}}`;
    ws.send(msg);
    utils.log(`sent data changes for ${column.id}`);
  }
}

function sendEndEdit (uid) {
  if (ws) {
    var msg = `{"endEdit":{"uid": "${uid}"}}`;
    ws.send(msg);
  }
}

//--- row display

export function setRowFocused (event) {
  var input = event.target;
  input.classList.add("focused");
}

export function setRowBlurred (event) {
  var input = event.target;
  input.classList.remove("focused");
}

export function setRowModified(event) {
  var editElem = event.target;

  if (!editElem.classList.contains("modified")){
    editElem.classList.add("modified");

    modifiedRows.add(editElem);
  }

  if (event.keyCode == 13) {
    sendChanges();
    if (event.getModifierState("Control")) setReadOnly();
  }
}

//--- auxiliary data display

function showDataWindow (title, data) {
  var win = window.open("", title, "toolbar=no,location=no,status=no,menubar=no,scrollbars=yes,resizable=yes,width=600,height=600");

  var json = JSON.stringify(data,null,4);
  var content = `<pre style="word-wrap: break-word; white-space: pre-wrap;">${json}</pre>`;
  win.document.body.innerHTML = content;
}

export function showColumnList () {
  showDataWindow(`ColumnList: ${columnList.id}`, columnList);
}

export function showRowList () {
  showDataWindow(`RowList: ${rowList.id}`, rowList);
}