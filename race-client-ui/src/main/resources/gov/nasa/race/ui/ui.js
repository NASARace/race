//--- public functions

function uiInit() {
    _initializeIcons();
    _initializeWindows();
    _initializePanels();
    _initializeFields();
    _initializeChoices();
    _initializeCheckboxes();
    _initializeRadios();
    _initializeLists();
    _initializeMenus();

    if (initializeData) initializeData();
}

//--- windows

function _initializeWindows() {
    for (e of document.getElementsByClassName("ui_window")) {
        uiInitWindow(e);
    }
}

function uiInitWindow(e) {
    if (e.children.length == 0 || !e.children[0].classList.contains("ui_titlebar")) {
        let tb = _createElement("DIV", "ui_titlebar", e.dataset.title);
        let cb = _createElement("BUTTON", "ui_close_button", "⨉");
        cb.onclick = (event) => {
            let w = event.target.closest('.ui_window');
            if (w) uiCloseWindow(w);
        };
        cb.setAttribute("tabindex", "-1");
        tb.appendChild(cb);

        let wndContent = _createElement("DIV", "ui_window_content");
        _moveChildElements(e, wndContent);
        e.appendChild(tb);
        e.appendChild(wndContent);
    }

    _makeDraggable(e);
}

function _makeDraggable(e) {
    var p1 = e.offsetLeft,
        p2 = e.offsetTop,
        p3 = p1,
        p4 = p2;

    let titlebar = e.getElementsByClassName("ui_titlebar")[0];
    titlebar.onmousedown = startDragWindow;

    function startDragWindow(mouseEvent) {
        p3 = mouseEvent.clientX;
        p4 = mouseEvent.clientY;
        document.onmouseup = stopDragWindow;
        document.onmousemove = dragWindow;
    }

    function dragWindow(mouseEvent) {
        mouseEvent.preventDefault();

        p1 = p3 - mouseEvent.clientX;
        p2 = p4 - mouseEvent.clientY;
        p3 = mouseEvent.clientX;
        p4 = mouseEvent.clientY;

        e.style.top = (e.offsetTop - p2) + "px";
        e.style.left = (e.offsetLeft - p1) + "px";
    }

    function stopDragWindow() {
        document.onmouseup = null;
        document.onmousemove = null;
    }
}

function uiShowWindow(o) {
    let e = _elementOf(o);
    if (e) {
        e.style.display = "block";
    }
}

function uiCloseWindow(o) {
    let e = _elementOf(o);
    if (e) {
        e.style.display = "none";
    }
}

function uiToggleWindow(o) {
    let e = _elementOf(o);
    if (e) {
        if (!e.style.display || e.style.display == "none") {
            e.style.display = "block";
        } else {
            e.style.display = "none";
        }
    }
}

//--- panels

function _initializePanels() {
    for (panel of document.getElementsByClassName("ui_panel")) {
        let prev = panel.previousElementSibling;
        if (!prev || !prev.classList.contains("ui_panel_header")) {
            let panelTitle = panel.dataset.panel;
            let panelHeader = _createElement("DIV", "ui_panel_header", panelTitle);
            if (panel.classList.contains("expanded")) panelHeader.classList.add("expanded");
            else panelHeader.classList.add("collapsed");
            panel.parentElement.insertBefore(panelHeader, panel);
        }
    }

    for (panelHeader of document.getElementsByClassName("ui_panel_header")) {
        panelHeader.addEventListener("click", uiTogglePanelExpansion);

        let panel = panelHeader.nextElementSibling;
        if (panelHeader.classList.contains("collapsed")) {
            panel.style.maxHeight = 0;
        }
    }
}

function uiTogglePanelExpansion(event) {
    const panelHeader = event.target;
    const panel = panelHeader.nextElementSibling;

    if (panelHeader.classList.contains("expanded")) { // collapse

        if (!panel.style.maxHeight) { // we have to give max-height an initial value but without triggering a transition
            panel.style.maxHeight = panel.scrollHeight + "px";
            setTimeout(() => { uiTogglePanelExpansion(event); }, 100);
        } else {
            _swapClass(panelHeader, "expanded", "collapsed");
            _swapClass(panel, "expanded", "collapsed");
            panel.style.maxHeight = 0;
        }

    } else { // expand
        _swapClass(panelHeader, "collapsed", "expanded");
        _swapClass(panel, "collapsed", "expanded");
        panel.style.maxHeight = panel.scrollHeight + "px";
    }
}

//--- icon functions

function _initializeIcons() {
    // add boilerplate childnode:  <svg viewBox="0 0 32 32"> <use class="ui_icon_svg" href="${src}#layer1"></use> </svg>
    for (icon of document.getElementsByClassName("ui_icon")) {
        let src = icon.dataset.src;

        let svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        icon.appendChild(svg);
        svg.setAttribute("viewBox", "0 0 32 32");

        let use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.classList.add("ui_icon_svg");
        use.setAttribute("href", src + "#layer1");
        svg.appendChild(use);
    }
}

function uiSetIconOn(event) {
    event.target.classList.add("on");
}

function uiSetIconOff(event) {
    function uiSetIconOn(event) {
        event.target.classList.remove("on");
    }
}

function uiToggleIcon(event) {
    _toggleClass(event.target, "on");
}

//--- fields 

function _initializeFields() {
    // expand wrapper fields
    for (e of document.getElementsByClassName("ui_field")) {
        if (e.tagName == "DIV") {
            let id = e.dataset.id;
            let labelText = e.dataset.label;

            if (id && labelText) {
                let label = _createElement("DIV", "ui_field_label", labelText);
                e.appendChild(label);

                let field = _createElement("INPUT", e.classList);
                field.setAttribute("type", "text");
                field.id = id;
                e.appendChild(field);

                if (e.classList.contains("input")) {
                    field.setAttribute("placeholder", e.dataset.placeholder);
                    field.addEventListener("keypress", _checkKeyEvent);
                } else {
                    field.readOnly = true;
                }
            }
        }
    }
}

function _checkKeyEvent(event) {
    if (event.key == "Enter") {
        document.activeElement.blur();
    }
}

function uiSetField(o, newContent) {
    let e = uiGetField(o);
    if (e) {
        e.value = newContent;
    }
}

function uiGetFieldValue(o) {
    let e = uiGetField(o);
    if (e) {
        return e.value;
    }
    return undefined;
}

function uiGetField(o) {
    let e = _elementOf(o);
    if (e && e.classList.contains("ui_field")) {
        if (e.tagName == "INPUT") return e;
        else if (e.tagName == "DIV") return e.lastElementChild;
    }
    throw "not a field";
}

//--- choices

function _initializeChoices() {
    for (e of document.getElementsByClassName("ui_choice")) {
        if (e.tagName == "DIV") {
            let id = e.dataset.id;
            let labelText = e.dataset.label;
            let rows = e.dataset.rows;

            if (id && labelText) {
                let label = _createElement("DIV", "ui_field_label", labelText);
                e.appendChild(label);
            }

            let field = _createElement("SELECT", e.classList);
            field.id = id;
            if (rows) field.setAttribute("size", rows);
            e.appendChild(field);
        }
    }
}

function uiSetChoiceItems(o, items, selIndex = -1) {
    let e = uiGetChoice(o);
    _removeChildrenOf(e);

    let i = 0;
    for (item of items) {
        let ie = _createElement("OPTION", "ui_choice_item", item);
        ie.value = i;
        e.appendChild(ie);
        i++;
    }
    if (selIndex >= 0 && selIndex < i) {
        e.value = selIndex;
    }
}

function uiGetSelectedChoiceValue(o) {
    let e = uiGetChoice(o);
    let idx = e.value;
    if (idx >= 0) return _nthChildOf(e, idx).textContent;
}

function uiGetChoice(o) {
    let e = _elementOf(o);
    if (e && e.classList.contains("ui_choice")) {
        if (e.tagName == "SELECT") return e;
        else if (e.tagName == "DIV") return e.lastElementChild;
    }
    throw "not a choice";
}

//--- checkboxes

function _initializeCheckboxes() {
    for (e of document.getElementsByClassName("ui_checkbox")) {
        let labelText = e.dataset.label;
        if (_hasNoChildElements(e) && labelText) {
            let btn = _createElement("DIV", "ui_checkbox_button");
            e.appendChild(btn);

            let lbl = _createElement("DIV", "ui_checkbox_label", labelText);
            e.appendChild(lbl);

            e.setAttribute("tabindex", "0");
        }
    }
}

function uiToggleCheckbox(o) {
    let checkbox = uiGetCheckboxOf(o);
    if (checkbox) {
        return _toggleClass(checkbox, "checked");
    }
    throw "not a checkbox";
}

function uiSetCheckBox(o, check = true) {
    let e = uiGetCheckboxOf(o);
    if (e) {
        if (check) e.classList.add("checked");
        else e.classList.remove("checked");
    }
}

function uiGetCheckboxOf(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_checkbox")) return e;
        else if (eCls.contains("ui_checkbox_button")) return e.parentElement;
        else if (eCls.contains("ui_checkbox_label")) return e.parentElement;
    }
    return undefined;
}

function uiIsCheckboxSelected(o) {
    let e = uiGetCheckboxOf(o);
    if (e) {
        return e.classList.contains("checked");
    }
    throw "not a checkbox";
}


//--- radios

function _initializeRadios() {
    for (e of document.getElementsByClassName("ui_radio")) {
        let labelText = e.dataset.label;
        if (_hasNoChildElements(e) && labelText) {
            let btn = _createElement("DIV", "ui_radio_button");
            e.appendChild(btn);

            let lbl = _createElement("DIV", "ui_radio_label", labelText);
            e.appendChild(lbl);

            e.setAttribute("tabindex", "0");
        }
    }
}

function uiSelectRadio(o) {
    let e = uiGetRadioOf(o);
    if (e) {
        if (!e.classList.contains("selected")) {
            for (r of e.parentElement.getElementsByClassName("ui_radio")) {
                if (r !== e) {
                    if (r.classList.contains("selected")) r.classList.remove("selected");
                    r.firstChild.textContent = undefined;
                }
            }
            e.classList.add("selected");
        }
        return true;
    } else {
        return false;
    }
}

function uiIsRadioSelected(o) {
    let e = uiGetRadioOf(o);
    if (e) {
        return e.classList.contains("selected");
    }
    throw "not a radio";
}

function uiGetRadioOf(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_radio")) return e;
        else if (eCls.contains("ui_radio_button")) return e.parentElement;
        else if (eCls.contains("ui_radio_label")) return e.parentElement;
    }
    return undefined;
}

function uiClearRadiosOf(o) {
    let e = _elementOf(o);
    if (e) {
        for (r of e.getElementsByClassName("ui_radio")) r.classList.remove("selected");
    }
}

//--- lists

function _initializeLists() {
    // this should be in CSS but unfortunately the 'attr()' function so far only seems to be very limited
    let itemHeight = _rootVar("--list-item-height");
    let itemPadding = _rootVar("--list-item-padding");

    for (e of document.getElementsByClassName("ui_list")) {
        let nRows = _intDataAttrValue(e, "rows", 8);
        e.style.maxHeight = `calc(${nRows} * (${itemHeight} + ${itemPadding}))`; // does not include padding, borders or margin
        e.setAttribute("tabindex", "0");
        e._isItem = (ie, itemText) => { return ie.textContent == itemText; }
        if (e.dataset.columnWidths) _setRowPrototype(e, e.dataset.columnWidths);
        if (e.dataset.keyColumn) _setKeyColumn(e, parseInt(e.dataset.keyColumn));
        if (e.firstElementChild && e.firstElementChild.classList.contains("ui_popup_menu")) _hoistChildElement(e.firstElementChild);
        e._uiSelectedItem = undefined; // we add a property to keeo track of selections
    }
}

function _setRowPrototype(e, colSpec) {
    let colWidths = colSpec.split(' ');
    let re = _createElement("DIV", "ui_list_item");
    let fitWidth = false;

    if (colWidths.length > 0) {
        let i = 0;
        if (colWidths[0] == "fit:") {
            fitWidth = true;
            i++;
        }
        for (; i < colWidths.length; i++) {
            let cw = colWidths[i];
            let ce = _createElement("DIV", "ui_list_subitem");
            let textAlign = "right";
            let j = 0;

            if (cw.charAt(j) == "-") {
                ce.style.textAlign = "left";
                j++;
            }
            if (cw.charAt(j) == "#") {
                ce.style.fontFamily = _rootVar("--font-family-mono");
                j++;
            }
            if (j > 0) cw = cw.substring(j);
            ce.style.width = cw;

            re.appendChild(ce);
        }
    }

    if (fitWidth) e.style.width = "inherit"; // override the theme width

    e._uiRowPrototype = re;
    e._isItem = (ie, itemText) => { return ie.firstElementChild.textContent == itemText; } // per default the first subitem text is the key
}

function _setKeyColumn(e, keyIdx) {
    if (e._uiRowPrototype && keyIdx >= 0 && keyIdx < e._uiRowPrototype.childElementCount) {
        e.isItem = (ie, itemText) => { return ie.children[keyIdx] == itemText; }
        e._uiKeyColumnIndex = keyIdx;
    } else throw "illegal key column index";
}

function uiSetListItemKeyColumn(o, idx) {
    let listBox = uiGetListOf(o);
    if (listBox) _setKeyColumn(listBox, idx);
}

function _itemIndexOf(ie) {
    var i = -1;
    var e = ie;
    while (e) {
        eCls = e.classList;
        i++;
        e = e.previousElementSibling;
    }
    return i;
}

function uiGetSelectedListItemIndex(o) {
    let listBox = uiGetListOf(o);
    if (listBox) {
        if (listBox._uiSelectedItem) return listBox._uiSelectedItem._uiItemIndex;
        else return -1;
    } else throw "not a list";
}

function uiGetSelectedListItem(o) {
    let listBox = uiGetListOf(o);
    if (listBox) {
        let sel = listBox._uiSelectedItem;
        if (sel) {
            if (sel.childElementCount) {
                return Array.from(sel.children, c => c.textContent);
            } else {
                return sel.textContent;
            }
        } else return undefined;

    } else throw "not a list";
}

function uiGetSelectedListItemText(o) {
    let listBox = uiGetListOf(o);
    if (listBox) {
        let sel = listBox._uiSelectedItem;
        if (sel) {
            let keyIdx = listBox._uiKeyColumnIndex;
            if (sel.childElementCount > 0 && keyIdx) {
                return sel.children[keyIdx].textContent;
            } else {
                return sel.textContent;
            }
        } else return undefined;
    } else throw "not a list";
}

function uiAppendListItem(o, item) {
    let e = uiGetListOf(o);
    if (e) {
        let ie = _createElement("DIV", "ui_list_item", item);
        ie._uiItemIndex = e.childElementCount;
        e.appendChild();
    }
}

function uiSetListItems(o, items) {
    let e = uiGetListOf(o);
    if (e) {
        // TODO - do we have to store/restore scrollLeft/scrollTop ?
        if (e._uiSelectedItem) {
            e._uiSelectedItem.classList.remove("selected");
            e._uiSelectedItem = undefined;
        }

        let i = 0;
        let n = items.length;
        let ies = e.children;
        let i1 = ies.length;
        let proto = e._uiRowPrototype;

        for (i = 0; i < n; i++) {
            if (i < i1) { // update existing element
                _setListItem(ies[i], items[i]);
            } else {
                e.appendChild(_createListItem(items[i], i, proto));
            }
        }
    }
}

function uiUpdateListItem(o, idx, itemData) {
    let e = uiGetListOf(o);
    if (e) {
        let ie = e.children[idx];
        if (ie) {
            _setListItem(ie, itemData);
        }
    } else throw "not a list";
}

function _setSubItemsOf(ie, itemData) {
    let ces = ie.children;
    let n = ces.length;
    for (var i = 0; i < itemData.length && i < n; i++) {
        ces[i].textContent = itemData[i];
    }
}

function _setListItem(ie, itemData) {
    if (ie.childElementCount > 0 && Array.isArray(itemData)) {
        _setSubItemsOf(ie, itemData);
    } else {
        ie.textContent = itemData;
    }
}

function _createListItem(itemData, idx, rowProto = undefined) {
    let ie = undefined;

    if (rowProto && Array.isArray(itemData)) {
        ie = rowProto.cloneNode(true);
        _setSubItemsOf(ie, itemData);

    } else {
        ie = _createElement("DIV", "ui_list_item", itemData);
        ie.textContent = itemData;
    }

    ie.addEventListener("click", _selectListItem);
    ie._uiItemIndex = idx;

    return ie;
}

function _selectListItem(event) {
    let tgt = event.target;
    let itemElement = _nearestElementWithClass(tgt, "ui_list_item");
    if (itemElement) {
        let listBox = _nearestParentWithClass(itemElement, "ui_list");
        if (listBox) {
            let prevItem = listBox._uiSelectedItem;
            if (prevItem) prevItem.classList.remove("selected");
            listBox._uiSelectedItem = itemElement;
            itemElement.classList.add("selected");
        }
    }
}

function uiSortinListItem(o, item) {
    let e = uiGetListOf(o);
    if (e) {
        let i = 0;
        for (ie of e.children) {
            if (item < ie.textContent) { // FIXME
                let ieNew = _createElement("DIV", "ui_list_item", item);
                ieNew._uiItemIndex = i;
                e.insertBefore(ieNew, ie);
                while (ie) {
                    i++;
                    ie._uiItemIndex = i;
                    ie = ie.nextElementSibling;
                }
                return;
            }
            i++;
        }
    }
}

function uiListItemIndexOf(o, itemText) {
    let e = uiGetListOf(o);
    if (e) {
        let i = 0;
        for (ie of e.children) {
            if (e._isItem(ie, itemText)) return i;
            i++;
        }
        return -1;
    }
    throw "not a list"
}

function uiRemoveListItem(o, item) {
    let e = uiGetListOf(o);
    if (e) {
        let ecs = e.children;
        let n = ecs.length;
        for (var i = 0; i < n; i++) {
            let ec = ecs[i];
            if (e._isItem(ec, item)) {
                for (var j = i + 1; j < n; j++) {
                    ecs[j]._uiItemIndex = j - 1;
                }
                if (e._uiSelectedItem === ec) e._uiSelectedItem = undefined;
                e.removeChild(ec);
                return;
            }
        }
    }
}

function uiClearListSelection(o) {
    let e = uiGetListOf(o);
    if (e) {
        let sel = e._uiSelectedItem;
        if (sel) sel.classList.remove("selected");
        e._uiSelectedItem = undefined;
    }
}

function uiClearList(o) {
    let e = uiGetListOf(o);
    if (e) {
        _removeChildrenOf(e);
        e._uiSelectedItem = undefined;
    }
}

function uiGetListOf(o) {
    let e = _elementOf(o);

    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_list")) return e;
        else if (eCls.contains("ui_list_item")) return e.parentElement;
        else if (eCls.contains("ui_list_subitem")) return e.parentElement.parentElement;
    }

    return undefined;
}

//--- menus

var _uiActivePopupMenus = [];

function _initializeMenus() {
    window.addEventListener("click", _windowPopupHandler);

    for (e of document.getElementsByClassName("ui_menuitem")) {
        e.addEventListener("mouseenter", _menuItemHandler);
        e.addEventListener("click", (event) => {
            event.stopPropagation();
            for (var i = _uiActivePopupMenus.length - 1; i >= 0; i--) {
                _uiActivePopupMenus[i].style.visibility = "hidden";
            }
            _uiActivePopupMenus = [];
        });
    }

    for (e of document.getElementsByClassName("disabled")) {
        if (e.classList.contains("ui_menuitem")) {
            e._uiSuspendedOnClickHandler = e.onclick;
            e.onclick = null;
        }
    }
}

function _windowPopupHandler(event) {
    for (p of _uiActivePopupMenus) {
        p.style.visibility = "hidden";
    }
    _uiActivePopupMenus = [];
}

function _menuItemHandler(event) {
    let mi = event.target;
    let sub = _firstChildWithClass(mi, "ui_popup_menu");
    let currentTop = _peekTop(_uiActivePopupMenus);

    if (currentTop !== mi.parentNode) {
        _uiActivePopupMenus.pop();
        currentTop.style.visibility = "hidden";
    }

    if (sub) {
        let rect = mi.getBoundingClientRect();
        let left = (rect.right + sub.scrollWidth <= window.innerWidth) ? rect.right : rect.left - sub.scrollWidth;
        let top = (rect.top + sub.scrollHeight <= window.innerHeight) ? rect.top : rect.bottom - sub.scrollHeight;

        sub.style.left = left + "px";
        sub.style.top = top + "px";
        sub.style.visibility = "visible";

        _uiActivePopupMenus.push(sub);
    }
}

function uiPopupMenu(event, o) {
    event.preventDefault();
    let popup = uiGetPopupOf(o);
    if (popup) {
        let left = _computePopupLeft(event.pageX, popup.scrollWidth);
        let top = _computePopupTop(event.pageY, popup.scrollHeight);

        popup.style.left = left + "px";
        popup.style.top = top + "px";
        popup.style.visibility = "visible";

        _uiActivePopupMenus.push(popup);
    }
}


function uiGetPopupOf(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_popup_menu")) return e;
        else if (eCls.contains("ui_popup_menu_item")) return e.parentNode;
    }

    throw "not a menu";
}

function uiGetMenuItemOf(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_menuitem")) return e;
    }
    throw "not a menuitem";
}

function uiIsMenuItemDisabled(o) {
    let e = uiGetMenuItemOf(o);
    if (e) {
        return e.classList.contains("disabled");
    }
    throw "not a menuitem";
}

function uiSetMenuItemDisabled(o, isDisabled = true) {
    let e = uiGetMenuItemOf(o);
    if (e) {
        if (isDisabled) {
            e.classList.add("disabled");
            e._uiSuspendedOnClickHandler = e.onclick;
            e.onclick = null;
        } else {
            e.classList.remove("disabled");
            e.onclick = e._uiSuspendedOnClickHandler;
        }
    }
}

function uiToggleMenuItemCheck(event) {
    let mi = event.target;
    if (mi && mi.classList.contains("ui_menuitem")) {
        return _toggleClass(mi, "checked");
    } else {
        throw "not a menuitem";
    }
}

//--- general purpose utility functions

function uiNonEmptyString(v) {
    if (v) {
        if (v.length > 0) return v;
        else return undefined;
    } else return undefined;
}

//--- private functions

function _elementOf(o) {
    if (typeof o === 'string' || o instanceof String) {
        return document.getElementById(o);
    } else if (o instanceof HTMLElement) {
        return o;
    } else {
        let tgt = o.target;
        if (tgt && tgt instanceof HTMLElement) return tgt;

        return undefined;
    }
}

function _swapClass(element, oldCls, newCls) {
    element.classList.remove(oldCls);
    element.classList.add(newCls);
}

function _toggleClass(element, cls) {
    if (element.classList.contains(cls)) {
        element.classList.remove(cls);
        return false;
    } else {
        element.classList.add(cls);
        return true;
    }
}

function _containsAnyClass(element, ...cls) {
    let cl = element.classList;
    for (c of cls) {
        if (cl.contains(c)) return true;
    }
    return false;
}

function _rootVar(varName) {
    return getComputedStyle(document.documentElement).getPropertyValue(varName);
}

function _rootVarInt(varName, defaultValue = 0) {
    let v = getComputedStyle(document.documentElement).getPropertyValue(varName);
    if (v) {
        return parseInt(v, 10);
    } else {
        return defaultValue;
    }
}

function _rootVarFloat(varName, defaultValue = 0.0) {
    let v = getComputedStyle(document.documentElement).getPropertyValue(varName);
    if (v) {
        return parseFloat(v);
    } else {
        return defaultValue;
    }
}

function _intDataAttrValue(element, varName, defaultValue = 0) {
    let data = element.dataset;
    if (data) {
        let v = data[varName];
        if (v) return parseInt(v);
    }
    return defaultValue;
}

function _nearestElementWithClass(e, cls) {
    while (e) {
        if (e.classList.contains(cls)) return e;
        e = e.parentElement;
    }
    return undefined;
}

function _nearestParentWithClass(e, cls) {
    return _nearestElementWithClass(e.parentElement, cls);
}

function _childIndexOf(element) {
    var i = -1;
    var e = element;
    while (e != null) {
        e = e.previousElementSibling;
        i++;
    }
    return i;
}

function _firstChildWithClass(element, cls) {
    var c = element.firstChild;
    while (c) {
        if (c instanceof HTMLElement && c.classList.contains(cls)) return c;
        c = c.nextElementSibling;
    }
    return undefined;
}

function _nthChildOf(element, n) {
    var i = 0;
    var c = element.firstChild;
    while (c) {
        if (c instanceof HTMLElement) {
            if (i == n) return c;
            i++;
        }
        c = c.nextElementSibling;
    }
    return undefined;
}

function _removeChildrenOf(element, keepFilter = undefined) {
    let keepers = [];

    while (element.firstElementChild) {
        let le = element.lastElementChild;
        if (keepFilter && keepFilter(le)) keepers.push(le);
        element.removeChild(le);
    }

    if (keepers.length > 0) {
        for (e of keepers.reverse()) {
            element.appendChild(e);
        }
    }
}


// FIXME - this assumes the popup dimensions are much smaller than the window dimensions

function _computePopupLeft(pageX, w) {
    return (pageX + w > window.innerWidth) ? pageX - w : pageX;
}

function _computePopupTop(pageY, h) {
    return (pageY + h > window.innerHeight) ? pageY - h : pageY;
}

function _peekTop(array) {
    let len = array.length;
    return (len > 0) ? array.at(len - 1) : undefined;
}

function _hasNoChildElements(element) {
    return element.children.length == 0;
}

function _createElement(tagName, clsList = undefined, txtContent = undefined) {
    let e = document.createElement(tagName);
    if (clsList) e.classList = clsList;
    if (txtContent) e.textContent = txtContent;
    return e;
}

function _moveChildElements(oldParent, newParent) {
    while (oldParent.childNodes.length > 0) {
        newParent.appendChild(oldParent.childNodes[0]);
    }
}

function _hoistChildElement(e) {
    let parent = e.parentElement;
    if (parent.nextElementSibling) {
        parent.parentElement.insertBefore(e, parent.nextElementSibling);
    } else {
        parent.parentElement.appendChild(e);
    }
}