if (window) {
    // used as an anchor for global properties available from HTML documents
    window.app = {};

    // functions we directly make available to the global namespace
    window.app.toggleWindow = toggleWindow;
    window.app.popupMenu = popupMenu;
    //.. and more
}

//--- public functions

export function initialize() {
    _initializeIcons();
    _initializeWindows();
    _initializePanels();
    _initializeFields();
    _initializeChoices();
    _initializeCheckboxes();
    _initializeRadios();
    _initializeLists();
    _initializeTimeWidgets();
    _initializeSliderWidgets();
    _initializeMenus(); /* has to be last in case widgets add menus */

    return true;
}

//--- fullscreen

var isFullScreen = false;

export function enterFullScreen() {
    if (!isFullScreen) {
        isFullScreen = true;

        var e = document.documentElement;
        if (e.requestFullscreen) {
            e.requestFullscreen();
        } else if (e.webkitRequestFullscreen) { /* Safari */
            e.webkitRequestFullscreen();
        }
    }
}

export function exitFullScreen() {
    if (isFullScreen) {
        isFullScreen = false;
        if (document.exitFullscreen) {
            document.exitFullscreen();
        } else if (document.webkitExitFullscreen) { /* Safari */
            document.webkitExitFullscreen();
        }
    }
}

export function toggleFullScreen() {
    if (isFullScreen) exitFullScreen();
    else enterFullScreen();
}

//--- windows

function _initializeWindows() {
    for (let e of document.getElementsByClassName("ui_window")) {
        initWindow(e);
    }
}

export function initWindow(e) {
    if (e.children.length == 0 || !e.children[0].classList.contains("ui_titlebar")) {
        let tb = _createElement("DIV", "ui_titlebar", e.dataset.title);
        let cb = _createElement("BUTTON", "ui_close_button", "⨉");
        cb.onclick = (event) => {
            let w = event.target.closest('.ui_window');
            if (w) closeWindow(w);
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


export function showWindow(o) {
    let e = _elementOf(o);
    if (e) {
        e.style.display = "block";
    }
}

export function closeWindow(o) {
    let e = _elementOf(o);
    if (e) {
        e.style.display = "none";
    }
}

export function toggleWindow(event, o) {
    let e = _elementOf(o);
    if (e) {
        if (!e.style.display || e.style.display == "none") {
            if (!e.style.left) _place(e, event.clientX + 20, event.clientY + 20);
            e.style.display = "block";
        } else {
            e.style.display = "none";
        }
    }
}

function _place(e, x, y) {
    // top right should always be visible so that we can move/hide
    let w = e.offsetWidth;
    let h = e.offsetHeight;
    let sw = window.innerWidth;
    let sh = window.innerHeight;

    if ((x + w) > sw) x = sw - w;
    if ((y + h) > sh) y = sh - h;
    if (y < 0) y = 0;

    e.style.left = x + "px";
    e.style.top = y + "px";
}

//--- panels

function _initializePanels() {
    for (let panel of document.getElementsByClassName("ui_panel")) {
        let prev = panel.previousElementSibling;
        if (!prev || !prev.classList.contains("ui_panel_header")) {
            let panelTitle = panel.dataset.panel;
            let panelHeader = _createElement("DIV", "ui_panel_header", panelTitle);
            if (panel.classList.contains("expanded")) panelHeader.classList.add("expanded");
            else panelHeader.classList.add("collapsed");
            panel.parentElement.insertBefore(panelHeader, panel);
        }
    }

    for (let panelHeader of document.getElementsByClassName("ui_panel_header")) {
        panelHeader.addEventListener("click", togglePanelExpansion);

        let panel = panelHeader.nextElementSibling;
        if (panelHeader.classList.contains("collapsed")) {
            panel.style.maxHeight = 0;
        }
    }
}

export function togglePanelExpansion(event) {
    const panelHeader = event.target;
    const panel = panelHeader.nextElementSibling;

    if (panelHeader.classList.contains("expanded")) { // collapse
        panel._uiCurrentHeight = panel.scrollHeight;

        if (!panel.style.maxHeight) { // we have to give max-height an initial value but without triggering a transition
            panel.style.maxHeight = panel._uiCurrentHeight + "px";
            setTimeout(() => { togglePanelExpansion(event); }, 100);
        } else {
            _swapClass(panelHeader, "expanded", "collapsed");
            _swapClass(panel, "expanded", "collapsed");
            panel.style.maxHeight = 0;
        }

    } else { // expand
        let expandHeight = panel._uiCurrentHeight ? panel._uiCurrentHeight : panel.scrollHeight;

        _swapClass(panelHeader, "collapsed", "expanded");
        _swapClass(panel, "collapsed", "expanded");
        panel.style.maxHeight = expandHeight + "px";
    }

    // should we force a reflow on the parent here?
}

function _resetPanelMaxHeight(ce) {
    let panel = _nearestParentWithClass(ce, "ui_panel");
    if (panel) {
        panel.style.maxHeight = "";
    }
}

//--- icon functions

function _initializeIcons() {
    // add boilerplate childnode:  <svg viewBox="0 0 32 32"> <use class="ui_icon_svg" href="${src}#layer1"></use> </svg>
    for (let icon of document.getElementsByClassName("ui_icon")) {
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

export function setIconOn(event) {
    event.target.classList.add("on");
}

export function setIconOff(event) {
    event.target.classList.remove("on");
}

export function toggleIcon(event) {
    _toggleClass(event.target, "on");
}

//--- fields 

function _initializeFields() {
    // expand wrapper fields
    for (let e of document.getElementsByClassName("ui_field")) {
        if (e.tagName == "DIV") {
            let id = e.dataset.id;
            let labelText = e.dataset.label;

            if (id && labelText) {
                let label = _createElement("DIV", "ui_field_label", labelText);
                e.appendChild(label);

                let field = _createElement("INPUT", e.classList);
                field.setAttribute("type", "text");
                field.id = id;
                if (_hasBorderedParent(e)) {
                    field.style.border = 'none';
                    e.style.margin = '0';
                }
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

export function setField(o, newContent) {
    let e = getField(o);
    if (e) {
        e.value = newContent;
    }
}

export function getFieldValue(o) {
    let e = getField(o);
    if (e) {
        return e.value;
    }
    return undefined;
}

export function getField(o) {
    let e = _elementOf(o);
    if (e && e.classList.contains("ui_field")) {
        if (e.tagName == "INPUT") return e;
        else if (e.tagName == "DIV") return e.lastElementChild;
    }
    throw "not a field";
}

//--- time & date widgets

var _timer = undefined;
const _timerClients = [];

const MILLIS_IN_DAY = 86400000;

document.addEventListener("visibilitychange", function() {
    if (document.visibilityState === 'visible') {
        if (_timerClients.length > 0) startTime();
    } else {
        stopTime();
    }
});

function _addTimerClient(e) {
    _timerClients.push(e);
}

export function startTime() {
    if (!_timer) {
        let t = Date.now();
        for (let e of _timerClients) {
            e._uiUpdateTime(t);
        }

        _timer = setInterval(_timeTick, 1000);
    }
}

function _timeTick() {
    if (_timerClients.length == 0) {
        clearInterval(_timer);
        _timer = undefined;
    } else {
        let t = Date.now();
        for (let client of _timerClients) {
            client._uiUpdateTime(t);
        }
    }
}

export function stopTime() {
    if (_timer) {
        clearInterval(_timer);
        _timer = undefined;
    }
}

function _initializeTimeWidgets() {
    for (let e of document.getElementsByClassName("ui_clock")) { _initializeClock(e); }
    for (let e of document.getElementsByClassName("ui_timer")) { _initializeTimer(e); }
}

function _initializeTimer(e) {
    if (e.tagName == "DIV") {
        let id = e.dataset.id;
        let labelText = e.dataset.label;

        if (labelText) {
            let label = _createElement("DIV", "ui_field_label", labelText);
            e.appendChild(label);
        }

        let tc = _createElement("DIV", "ui_timer_value");
        tc.id = id;
        tc.innerText = "0:00:00";
        e.appendChild(tc);

        tc._uiT0 = 0; // elapsed
        tc._uiTimeScale = 1;
        tc._uiUpdateTime = (t) => { _updateTimer(tc, t); }
        _addTimerClient(tc);
    }
}

function _updateTimer(e, t) {
    if (e._uiT0 == 0) e._uiT0 = t;

    if (_isShowing(e)) {
        let dt = Math.round((t - e._uiT0) * e._uiTimeScale);
        let s = Math.floor(dt / 1000) % 60;
        let m = Math.floor(dt / 60000) % 60;
        let h = Math.floor(dt / 3600000);

        let elapsed = h.toString();
        elapsed += ':';
        if (m < 10) elapsed += '0';
        elapsed += m;
        elapsed += ':';
        if (s < 10) elapsed += '0';
        elapsed += s;

        e.innerText = elapsed;
    }
}

export function getTimer(o) {
    let e = _elementOf(o);
    if (e && e.tagName == "DIV") {
        if (e.classList.contains("ui_timer_value")) return e;
        else if (e.classList.contains("ui_timer")) return _firstChildWithClass("ui_timer_value");
    }
    throw "not a timer";
}

export function resetTimer(o, timeScale) {
    let e = getTimer(o);
    if (e) {
        e._uiT0 = 0;
        if (timeScale) e._uiTimeScale = timeScale;
    }
}

function _initializeClock(e) {
    if (e.tagName == "DIV") {
        let id = e.dataset.id;
        let labelText = e.dataset.label;

        if (labelText) {
            let label = _createElement("DIV", "ui_field_label", labelText);
            e.appendChild(label);
        }

        let tc = _createElement("DIV", "ui_clock_wrapper");
        tc.id = id;

        let dateField = _createElement("DIV", "ui_clock_date");
        let timeField = _createElement("DIV", "ui_clock_time");

        tc.appendChild(dateField);
        tc.appendChild(timeField);

        e.appendChild(tc);

        var tz = e.dataset.tz;
        if (!tz) tz = 'UTC';
        else if (tz == "local") tz = Intl.DateTimeFormat().resolvedOptions().timeZone;

        let dateOpts = {
            timeZone: tz,
            weekday: 'short',
            year: 'numeric',
            month: 'numeric',
            day: 'numeric'
        };
        tc._uiDateFmt = new Intl.DateTimeFormat('en-US', dateOpts);

        let timeOpts = {
            timeZone: tz,
            hour: 'numeric',
            minute: 'numeric',
            second: 'numeric',
            hour12: false,
            timeZoneName: 'short'
        };
        tc._uiTimeFmt = new Intl.DateTimeFormat('en-US', timeOpts);

        tc._uiW0 = 0; // ref wall clock
        tc._uiS0 = 0; // ref sim clock
        tc._uiSday = 0; // last sim clock day displayed
        tc._uiStopped = false;
        tc._uiTimeScale = 1;

        tc._uiUpdateTime = (t) => { _updateClock(tc, t); };
        _addTimerClient(tc);
    }
}


function _updateClock(e, t) {
    if (e._uiS0 == 0) { // first time initialization w/o previous uiSetClock
        e._uiS0 = t;
        e._uiSday = t / MILLIS_IN_DAY;
        e._uiW0 = t;

        let date = new Date(t);
        e.children[0].innerText = e._uiDateFmt.format(date);
        e.children[1].innerText = e._uiTimeFmt.format(date);

    } else {
        if (e._uiW0 == 0) { // first time init with previous uiSetClock
            e._uiW0 = t;
        } else if (_isShowing(e)) {
            let s = e._uiS0 + (t - e._uiW0) * e._uiTimeScale;
            let date = new Date(s);
            let day = s / MILLIS_IN_DAY;
            if (day != e._uiSday) {
                e.children[0].innerText = e._uiDateFmt.format(date);
                e._uiLastDay = day;
            }
            e.children[1].innerText = e._uiTimeFmt.format(date);
        }
    }
}

export function setClock(o, dateSpec, timeScale) {
    let e = getClock(o);
    if (e) {
        let date = new Date(dateSpec);
        if (date) {
            e._uiS0 = date.valueOf();
            e._uiSday = e._uiS0 / MILLIS_IN_DAY;

            e.children[0].innerText = e._uiDateFmt.format(date);
            e.children[1].innerText = e._uiTimeFmt.format(date);

            if (timeScale) {
                e._uiTimeScale = timeScale;
            }
        }
    }
}

export function getClock(o) {
    let e = _elementOf(o);
    if (e && e.tagName == "DIV") {
        if (e.classList.contains("ui_clock_wrapper")) return e;
        else if (e.classList.contains("ui_clock")) return _firstChildWithClass("ui_clock_wrapper");
    }
    throw "not a clock field";
}


//--- slider widgets

const sliderResizeObserver = new ResizeObserver(entries => {
    for (let re of entries) { // this is a ui_slider_track
        let e = re.target;
        let trackRect = e.getBoundingClientRect();
        let rangeRect = e._uiRange.getBoundingClientRect();

        e._uiRangeWidth = rangeRect.width;
        e._uiScale = (e._uiMaxValue - e._uiMinValue) / rangeRect.width;

        _positionLimits(e, trackRect, rangeRect);
        _positionThumb(e);
    }
});

function _initializeSliderWidgets() {
    for (let e of document.getElementsByClassName("ui_slider")) {
        let id = e.dataset.id;
        let labelText = e.dataset.label;
        // default init - likely to be set by subsequent uiSetSliderRange/Value calls
        let minValue = _parseInt(e.dataset.minValue, 0);
        let maxValue = _parseInt(e.dataset.maxValue, 100);
        let v = _parseInt(e.dataset.value, minValue);

        if (maxValue > minValue) {
            if (labelText) {
                let label = _createElement("DIV", "ui_field_label", labelText);
                e.appendChild(label);
            }

            let track = _createElement("DIV", "ui_slider_track");
            track.id = id;
            track._uiMinValue = minValue;
            track._uiMaxValue = maxValue;
            track._uiStep = _parseNumber(e.dataset.inc);
            track._uiValue = _computeSliderValue(track, v);

            let range = _createElement("DIV", "ui_slider_range");
            track._uiRange = range;
            track.appendChild(range);

            let left = _createElement("DIV", "ui_slider_limit", minValue);
            track._uiLeftLimit = left;
            track.appendChild(left);

            let thumb = _createElement("DIV", "ui_slider_thumb", "▲");
            track._uiThumb = thumb;
            thumb.addEventListener("mousedown", startDrag);
            track.appendChild(thumb);

            let num = _createElement("DIV", "ui_slider_num");
            track._uiNum = num;
            track.appendChild(num);

            let right = _createElement("DIV", "ui_slider_limit", maxValue);
            track._uiRightLimit = right;
            track.appendChild(right);

            e.appendChild(track);
            sliderResizeObserver.observe(track);

        } else {
            console.log("illegal range for slider " + id);
        }
    }

    function startDrag(event) {
        let offX = event.offsetX; // cursor offset within thumb
        let track = event.target.parentElement;
        let lastValue = track._uiValue;
        let trackRect = track.getBoundingClientRect();

        track.addEventListener("mousemove", drag);
        document.addEventListener("mouseup", stopDrag);
        event.preventDefault();

        function drag(event) {
            let e = event.currentTarget; // ui_slider_track

            let x = event.clientX - offX - trackRect.x;
            if (x < 0) x = 0;
            else if (x > e._uiRangeWidth) x = e._uiRangeWidth;

            let v = e._uiMinValue + (x * e._uiScale);
            let vv = _computeSliderValue(e, v);
            e._uiValue = vv;
            if (e._uiNum) e._uiNum.innerText = _formattedNum(vv, e._uiNumFormatter);
            _positionThumb(e);

            if (vv != lastValue) {
                let slider = e.parentElement;
                slider.dispatchEvent(new Event('change'));
                lastValue = vv;
            }

            event.preventDefault();
        }

        function stopDrag(event) {
            track.removeEventListener("mousemove", drag);
            document.removeEventListener("mouseup", stopDrag);
            event.preventDefault();
        }
    }
}

function _positionLimits(e, tr, rr) {
    let trackRect = tr ? tr : e.getBoundingClientRect();
    let rangeRect = rr ? rr : e._uiRange.getBoundingClientRect();
    let top = (rangeRect.height + 2) + "px";

    if (e._uiLeftLimit) {
        let style = e._uiLeftLimit.style;
        style.top = top;
        style.left = "4px";
    }

    if (e._uiRightLimit) {
        let rightRect = e._uiRightLimit.getBoundingClientRect();
        let style = e._uiRightLimit.style;
        style.top = top;
        style.left = (trackRect.width - rightRect.width - 4) + "px";
    }
}

function _positionThumb(e) {
    let dx = ((e._uiValue - e._uiMinValue) / e._uiScale);
    e._uiThumb.style.left = dx + "px"; // relative pos

    if (e._uiNum) {
        if (e._uiValue > (e._uiMaxValue + e._uiMinValue) / 2) { // place left of thumb
            let w = e._uiNum.getBoundingClientRect().width;
            e._uiNum.style.left = (dx - w) + "px";
        } else {
            e._uiNum.style.left = (dx + e._uiThumb.offsetWidth) + "px";
        }
    }
}

function _computeSliderValue(e, v) {
    let minValue = e._uiMinValue;
    let inc = e._uiStep;
    if (inc) {
        if (inc == 1) return Math.round(v);
        else return minValue + Math.round((v - minValue) / inc) * inc;
    } else {
        if (v < minValue) return minValue;
        else if (v > e._uiMaxValue) return e._uiMaxValue;
        return v;
    }
}


export function setSliderRange(o, min, max, step, numFormatter) {
    let e = getSlider(o);
    if (e) {
        e._uiMinValue = min;
        e._uiMaxValue = max;
        if (step) e._uiStep = step;

        if (numFormatter) e._uiNumFormatter = numFormatter;
        if (e._uiLeftLimit) e._uiLeftLimit.innerText = _formattedNum(min, e._uiNumFormatter);
        if (e._uiRightLimit) e._uiRightLimit.innerText = _formattedNum(max, e._uiNumFormatter);

        if (_hasDimensions(e)) {
            _positionLimits(e);
            _positionThumb(e);
        }
    }
}


export function setSliderValue(o, v) {
    let e = getSlider(o);
    if (e) {
        e._uiValue = _computeSliderValue(e, v);
        if (e._uiNum) e._uiNum.innerText = _formattedNum(e._uiValue, e._uiNumFormatter);
        if (_hasDimensions(e)) _positionThumb(e);
    }
}

export function getSliderValue(o) {
    let e = getSlider(o);
    if (e) {
        return e._uiValue;
    }
}

export function getSlider(o) {
    let e = _elementOf(o);
    if (e && e.tagName == "DIV") {
        if (e.classList.contains("ui_slider_track")) return e;
        else if (e.classList.contains("ui_slider")) return _firstChildWithClass(e, "ui_slider_track");
    }
    throw "not a slider";
}

//--- choices

function _initializeChoices() {
    for (let e of document.getElementsByClassName("ui_choice")) {
        if (e.tagName == "DIV") {
            let id = e.dataset.id;
            let labelText = e.dataset.label;

            if (labelText) {
                let label = _createElement("DIV", "ui_field_label", labelText);
                e.appendChild(label);
            }

            let field = _createElement("DIV", "ui_choice_value");
            field.id = id;
            field._uiSelIndex = -1;

            e.appendChild(field);
        }
    }
}

export function setChoiceItems(o, items, selIndex = -1) {
    let e = getChoice(o);
    if (e) {
        let prevChoices = _firstChildWithClass(e.parentElement, "ui_popup_menu");
        if (prevChoices) e.parentElement.removeChild(prevChoices);
        e._uiSelIndex = Math.min(selIndex, items.length);

        let choice = e.parentElement;
        var i = 0;
        let menu = _createElement("DIV", "ui_popup_menu");
        for (let item of items) {
            let idx = i;
            let mi = _createElement("DIV", "ui_menuitem", item);
            mi.addEventListener("click", (event) => {
                event.preventDefault();
                e.innerText = mi.innerText;
                if (e._uiSelIndex >= 0) { menu.children[e._uiSelIndex].classList.remove('checked'); }
                e._uiSelIndex = idx;
                mi.classList.add('checked');
                choice.dispatchEvent(new Event('change'));
            });
            if (selIndex == i) {
                mi.classList.add('checked');
                e.innerText = item;
            }
            menu.appendChild(mi);
            i += 1;
        }

        choice.appendChild(menu);
        e.addEventListener("click", (event) => {
            event.stopPropagation();
            popupMenu(event, menu);
            event.preventDefault();
        });
    }
}

export function getSelectedChoiceValue(o) {
    let e = getChoice(o);
    if (e) {
        return e.innerText;
    }
}

export function getChoice(o) {
    let e = _elementOf(o);
    if (e) {
        if (e.classList.contains("ui_choice_value")) return e;
        else if (e.classList.contains("ui_choice")) return _firstChildWithClass(e, 'ui_choice_value');
    }
    throw "not a choice";
}

//--- checkboxes

function _initializeCheckboxes() {
    for (let e of document.getElementsByClassName("ui_checkbox")) {
        let labelText = e.dataset.label;
        if (_hasNoChildElements(e) && labelText) {
            let btn = _createElement("DIV", "ui_checkbox_button");
            btn.addEventListener("click", _clickCheckBox);
            e.appendChild(btn);

            let lbl = _createElement("DIV", "ui_checkbox_label", labelText);
            lbl.addEventListener("click", _clickCheckBox);
            e.appendChild(lbl);

            e.setAttribute("tabindex", "0");
        }
    }
}

function _clickCheckBox(event) {
    let checkbox = getCheckBox(event);
    if (checkbox) {
        _toggleClass(checkbox, "checked");
    }
}

export function toggleCheckBox(o) {
    let checkbox = getCheckBox(o);
    if (checkbox) {
        return _toggleClass(checkbox, "checked");
    }
    throw "not a checkbox";
}

export function setCheckBox(o, check = true) {
    let e = getCheckBox(o);
    if (e) {
        if (check) _addClass(e, "checked");
        else e.classList.remove("checked");
    }
}

export function getCheckBox(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_checkbox")) return e;
        else if (eCls.contains("ui_checkbox_button")) return e.parentElement;
        else if (eCls.contains("ui_checkbox_label")) return e.parentElement;
    }
    return undefined;
}

export function isCheckBoxSelected(o) {
    let e = getCheckBox(o);
    if (e) {
        return e.classList.contains("checked");
    }
    throw "not a checkbox";
}


//--- radios

function _initializeRadios() {
    for (let e of document.getElementsByClassName("ui_radio")) {
        let labelText = e.dataset.label;
        if (_hasNoChildElements(e) && labelText) {
            let btn = _createElement("DIV", "ui_radio_button");
            btn.addEventListener("click", _clickRadio);
            e.appendChild(btn);

            let lbl = _createElement("DIV", "ui_radio_label", labelText);
            lbl.addEventListener("click", _clickRadio);
            e.appendChild(lbl);

            e.setAttribute("tabindex", "0");
        }
    }
}

function _clickRadio(event) {
    let e = getRadio(event);
    if (e) {
        if (!e.classList.contains("selected")) {
            for (let r of e.parentElement.getElementsByClassName("ui_radio")) {
                if (r !== e) {
                    if (r.classList.contains("selected")) r.classList.remove("selected");
                }
            }
            e.classList.add("selected");
        }
    }
}

export function selectRadio(o) {
    let e = getRadio(o);
    if (e) {
        if (!e.classList.contains("selected")) {
            for (let r of e.parentElement.getElementsByClassName("ui_radio")) {
                if (r !== e) {
                    if (r.classList.contains("selected")) r.classList.remove("selected");
                }
            }
            _addClass(e, "selected");
        }
        return true;
    } else {
        return false;
    }
}

export function isRadioSelected(o) {
    let e = getRadio(o);
    if (e) {
        return e.classList.contains("selected");
    }
    throw "not a radio";
}

export function getRadio(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_radio")) return e;
        else if (eCls.contains("ui_radio_button")) return e.parentElement;
        else if (eCls.contains("ui_radio_label")) return e.parentElement;
    }
    return undefined;
}

export function clearRadioGroup(o) {
    let e = _elementOf(o);
    let n = 0;
    while (n == 0 && e) {
        for (let r of e.getElementsByClassName("ui_radio")) {
            r.classList.remove("selected");
            n++;
        }
        e = e.parentElement;
    }
}

//--- lists

function _initializeLists() {
    // this should be in CSS but unfortunately the 'attr()' function so far seems to be very limited
    let itemHeight = _rootVar("--list-item-height");
    let itemPadding = _rootVar("--list-item-padding");

    for (let e of document.getElementsByClassName("ui_list")) {
        let nRows = _intDataAttrValue(e, "rows", 8);
        e.style.maxHeight = `calc(${nRows} * (${itemHeight} + ${itemPadding}))`; // does not include padding, borders or margin
        e.setAttribute("tabindex", "0");
        if (e.firstElementChild && e.firstElementChild.classList.contains("ui_popup_menu")) _hoistChildElement(e.firstElementChild);

        let selectAction = _dataAttrValue(e, "onselect");
        if (selectAction) {
            e.addEventListener("selectionChanged", Function("event", selectAction));
            e._uiSelectAction = selectAction;
        }

        // element state
        e._uiSelectedItemElement = null; // we add a property to keep track of selections
        e._uiMapFunc = item => item.toString(); // item => itemElement text
        e._uiItemMap = new Map(); // item -> itemElement
    }
}

// multi-column item display (this will override single column display)
export function setListItemDisplayColumns(o, listAttrs, colSpecs) {
    let e = getList(o);
    if (e) {
        let defaultWidth = _rootVar("--list-item-column-width", "5rem");
        let re = _createElement("DIV", "ui_list_item");

        colSpecs.forEach(cs => {
            let ce = _createElement("DIV", "ui_list_subitem");

            ce._uiMapFunc = cs.map;
            ce.style.width = cs.width ? cs.width : defaultWidth;

            if (cs.attrs.includes("alignLeft")) ce.style.textAlign = "left";
            else if (cs.attrs.includes("alignRight")) ce.style.textAlign = "right";

            if (cs.attrs.includes("fixed")) ce.style.font = _rootVar("--mono-font");
            re.appendChild(ce);
        });

        if (listAttrs.includes("fit")) e.style.width = "inherit";
        e._uiRowPrototype = re;
    }
}

// single column item display
export function setListItemDisplay(o, styleWidth, attrs, mapFunc) {
    let e = getList(o);
    if (e) {
        if (mapFunc) e._uiMapFunc = mapFunc;
        if (styleWidth) e.style.width = styleWidth;

        if (attrs.includes("alignLeft")) e.style.textAlign = "left";
        else if (attrs.includes("alignRight")) e.style.textAlign = "right";

        if (attrs.includes("fixed")) e.style.font = _rootVar("--mono-font");
    }
}

function _setSubItemsOf(ie, item) {
    for (let i = 0; i < ie.childElementCount; i++) {
        let ce = ie.children[i];
        ce.innerText = ce._uiMapFunc(item);
    }
}

function _setListItem(e, ie, item) {
    if (ie.childElementCount > 0) {
        _setSubItemsOf(ie, item);
    } else {
        ie.innerText = e._uiMapFunc(item);
    }
    ie._uiItem = item;
    e._uiItemMap.set(item, ie);
}

function _cloneRowPrototype(proto) {
    let e = proto.cloneNode(true);
    for (let i = 0; i < e.childElementCount; i++) {
        let ce = e.children[i];
        let pe = proto.children[i];

        ce._uiMapFunc = pe._uiMapFunc;
    }
    return e;
}

function _createListItemElement(e, item, rowProto = undefined) {
    let ie = undefined;

    if (rowProto) {
        ie = _cloneRowPrototype(rowProto);
        _setSubItemsOf(ie, item);
    } else {
        ie = _createElement("DIV", "ui_list_item", e._uiMapFunc(item));
    }
    ie._uiItem = item;
    e._uiItemMap.set(item, ie);
    ie.addEventListener("click", _selectListItem);

    return ie;
}

export function setListItems(o, items) {
    let e = getList(o);
    if (e) {
        // TODO - do we have to store/restore scrollLeft/scrollTop ?
        _setSelectedItemElement(e, null);

        let i = 0;
        let ies = e.children; // the ui_list_item children
        let i1 = ies.length;
        let proto = e._uiRowPrototype;

        items.forEach(item => {
            if (i < i1) { // replace existing element
                _setListItem(e, ies[i], item);
            } else { // append new element
                let ie = _createListItemElement(e, item, proto);
                e.appendChild(ie);
            }
            i++;
        });

        if (e.childElementCount > i) _removeLastNchildrenOf(e, e.childElementCount - i);

        _resetPanelMaxHeight(e);
    }
}

export function setSelectedListItem(o, item) {
    let e = getList(o);
    if (e) {
        let ie = e._uiItemMap.get(item);
        if (ie) {
            _setSelectedItemElement(e, ie);
            ie.scrollIntoView();
        }
    }
}

export function getSelectedListItem(o) {
    let listBox = getList(o);
    if (listBox) {
        let sel = listBox._uiSelectedItemElement;
        if (sel) {
            return sel._uiItem;
        } else return null;

    } else throw "not a list";
}

export function appendListItem(o, item) {
    let e = getList(o);
    if (e) {
        let ie = _createElement("DIV", "ui_list_item", item);
        ie._uiItem = item;
        e.appendChild(ie);
    }
}

export function insertListItem(o, item, idx) {
    let e = getList(o);
    if (e) {
        let proto = e._uiRowPrototype;
        let ie = _createListItemElement(e, item, proto);
        if (idx < e.childElementCount) {
            e.insertBefore(ie, e.children[idx]);
        } else {
            e.appendChild(ie);
        }
    }
}

export function updateListItem(o, item) {
    let e = getList(o);
    if (e) {
        let ie = e._uiItemMap.get(item);
        if (ie) {
            _setListItem(e, ie, item);
        }
    } else throw "not a list";
}

export function removeListItem(o, item) {
    let e = getList(o);
    if (e) {
        let ie = e._uiItemMap.get(item);
        if (ie) {
            if (e._uiSelectedItemElement === ie) _setSelectedItemElement(e, null);
            e.removeChild(ie);
        }
    }
}

export function removeLastNListItems(o, n) {
    let e = getList(o);
    if (e) {
        _removeLastNchildrenOf(e, n);
    }
}

function _setSelectedItemElement(listBox, itemElement) {
    let prevItem = null;
    let nextItem = null;

    let prevItemElem = listBox._uiSelectedItemElement;
    if (prevItemElem !== itemElement) {
        if (prevItemElem) {
            prevItem = prevItemElem._uiItem;
            _removeClass(prevItemElem, "selected");
        }

        if (itemElement) {
            nextItem = itemElement._uiItem;
            listBox._uiSelectedItemElement = itemElement;
            _addClass(itemElement, "selected");
        } else {
            listBox._uiSelectedItemElement = null;
        }

        if (listBox._uiSelectAction) {
            let event = new CustomEvent("selectionChanged", {
                bubbles: true,
                detail: {
                    curSelection: nextItem,
                    prevSelection: prevItem
                }
            });
            listBox.dispatchEvent(event);
        }
    }
}

function _selectListItem(event) {
    let tgt = event.target;
    let itemElement = _nearestElementWithClass(tgt, "ui_list_item");
    if (itemElement) {
        let listBox = _nearestParentWithClass(itemElement, "ui_list");
        if (listBox) _setSelectedItemElement(listBox, itemElement);
    }
}

export function clearSelectedListItem(o) {
    let e = getList(o);
    if (e) {
        _setSelectedItemElement(e, null);
    }
}

export function clearList(o) {
    let e = getList(o);
    if (e) {
        _setSelectedItemElement(e, null);
        _removeChildrenOf(e);
    }
}

export function getList(o) {
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

    for (let e of document.getElementsByClassName("ui_menuitem")) {
        _initMenuItem(e);
    }

    for (let e of document.getElementsByClassName("disabled")) {
        if (e.classList.contains("ui_menuitem")) {
            e._uiSuspendedOnClickHandler = e.onclick;
            e.onclick = null;
        }
    }
}

function _initMenuItem(e) {
    e.addEventListener("mouseenter", _menuItemHandler);
    e.addEventListener("click", (event) => {
        event.stopPropagation();
        for (let i = _uiActivePopupMenus.length - 1; i >= 0; i--) {
            _uiActivePopupMenus[i].style.visibility = "hidden";
        }
        _uiActivePopupMenus = [];
    });
}

function _windowPopupHandler(event) {
    for (let p of _uiActivePopupMenus) {
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

export function popupMenu(event, o) {
    event.preventDefault();
    let popup = getPopupMenu(o);
    if (popup) {
        let left = _computePopupLeft(event.pageX, popup.scrollWidth);
        let top = _computePopupTop(event.pageY, popup.scrollHeight);

        popup.style.left = left + "px";
        popup.style.top = top + "px";
        popup.style.visibility = "visible";

        _uiActivePopupMenus.push(popup);
    }
}


export function getPopupMenu(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_popup_menu")) return e;
        else if (eCls.contains("ui_popup_menu_item")) return e.parentNode;
    }

    throw "not a menu";
}

export function getMenuItem(o) {
    let e = _elementOf(o);
    if (e) {
        let eCls = e.classList;
        if (eCls.contains("ui_menuitem")) return e;
    }
    throw "not a menuitem";
}

export function isMenuItemDisabled(o) {
    let e = getMenuItem(o);
    if (e) {
        return e.classList.contains("disabled");
    }
    throw "not a menuitem";
}

export function setMenuItemDisabled(o, isDisabled = true) {
    let e = getMenuItem(o);
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

export function toggleMenuItemCheck(event) {
    let mi = event.target;
    if (mi && mi.classList.contains("ui_menuitem")) {
        return _toggleClass(mi, "checked");
    } else {
        throw "not a menuitem";
    }
}


//--- general utility functions

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

function _addClass(element, cls) {
    if (!element.classList.contains(cls)) element.classList.add(cls);
}

function _removeClass(element, cls) {
    element.classList.remove(cls);
}

function _containsAnyClass(element, ...cls) {
    let cl = element.classList;
    for (c of cls) {
        if (cl.contains(c)) return true;
    }
    return false;
}

function _rootVar(varName, defaultValue = undefined) {
    let v = getComputedStyle(document.documentElement).getPropertyValue(varName);
    if (v) return v;
    else return defaultValue;
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

function _dataAttrValue(element, varName, defaultValue = "") {
    let data = element.dataset;
    if (data) {
        let v = data[varName];
        if (v) return v;
    }
    return defaultValue;
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

function _removeLastNchildrenOf(element, n) {
    let i = 0
    while (i < n && element.firstChild) {
        let le = element.lastElementChild;
        element.removeChild(le);
        i++;
    }
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
    return (len > 0) ? array[len - 1] : undefined;
}

function _hasNoChildElements(element) {
    return element.children.length == 0;
}

function _createElement(tagName, clsList = undefined, txtContent = undefined) {
    let e = document.createElement(tagName);
    if (clsList) e.classList = clsList;
    if (txtContent) e.innerText = txtContent;
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

function _createDate(dateSpec) {
    if (dateSpec) {
        if (typeof dateSpec === "string") {
            if (dateSpec == "now") return new Date(Date.now());
            else return new Date(Date.parse(dateSpec));
        } else if (typeof dateSpec === "number") {
            return new Date(dateSpec); // epoch millis
        }
    }
    return undefined;
}

function _isShowing(e) {
    if ((e.offsetParent === null) /*|| (e.getClientRects().length == 0)*/ ) return false; // shortcut
    let style = window.getComputedStyle(e);
    if (style.visibility !== 'visible' || style.display === 'none') return false;

    e = e.parentElement;
    while (e) {
        style = window.getComputedStyle(e);
        if (style.visibility !== 'visible' || style.display === 'none') return false;

        // we could also check for style.maxHeight == 0
        if (e.classList.contains('collapsed')) return false;
        e = e.parentElement;
    }

    return true;
}

function _hasBorderedParent(e) {
    let p = e.parentElement;
    if (p) {
        let cl = p.classList;
        return (cl.contains('ui_container') && cl.contains('bordered'));
    }
    return false;
}

function _parseInt(s, defaultValue) {
    if (s && s.length > 0) return parseInt(s);
    else return defaultValue;
}

function _parseNumber(s, defaultValue) {
    if (s && s.length > 0) {
        if (s.contains('.')) return parseFloat(s);
        else return parseInt(s);
    } else {
        return defaultValue;
    }
}

function _hasDimensions(e) {
    return (e.getBoundingClientRect().width > 0);
}

function _formattedNum(v, fmt) {
    return fmt ? fmt.format(v) : v.toString();
}