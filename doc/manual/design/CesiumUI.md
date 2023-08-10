# Cesium UI

RACE uses the Cesium javascript platform for 3-D rendering of data displays, with documentation available at: https://cesium.com/learn/cesiumjs/ref-doc/index.html

To create a user interface to display data in a RACE application, a javascript module and a scala service must built and properly configured (see *Services* for more information). The file structure should resemble:


```
├── race
│   ├── race_cesium
│   │   ├── src/main
│   │   │   ├── scala
│   │   │   │   ├──gov/nasa/race/cesium
│   │   │   │   │   ├── MyCesiumService.scala
│   │   │   ├── resources
│   │   │   │   ├──gov/nasa/race/cesium
│   │   │   │   │   ├── MyIcon.svg
│   │   │   │   │   ├── ui_cesium_myExample.js
│   ├── race_earth
│   │   ├── src/main
│   │   │   ├── scala
│   │   │   │   ├──gov/nasa/race/earth
│   │   │   │   │   ├── MyImportActor.scala
```

The core components of a UI module include:
- the window where users can select data
- the icon for the window
- the data layer rendering 
- interactions between selections and data options/rendering
- handlers for new data

## Creating a Window

Windows are created using the `ui.Window(displayText, trackingVariable, displayIcon)` function, where expandable panels, row containers, checkboxes, and datalists are defined. Typical usage involves defining a function, then calling the function during initialization. Icons are also created during initialization by defining a function to create them:

    
    function createWindow() {
        return ui.Window("Example Window", "example", "MyIcon.svg(
            ... // define panels here
            )
        )
    }

    function createIcon() {
        console.log("created smoke icon");
        return ui.Icon("MyIcon.svg", (e)=> ui.toggleWindow(e,'example'));
    }
    
    function initWindow() {
        createIcon();
        createWindow();
    }

    initWindow()
    

To create a panel, define it within the window and add any row containers. The panels add items, such as checkboxes, along columns, so row containers separate items into rows:

     function createWindow() {
        return ui.Window("Example Window", "example", "MyIcon.svg) (
            ui.Panel("Example Data Selection)(
                ui.RowContainer()(
                    ui.CheckBox("follow latest", toggleFollowLatest, "example.followLatest")
                )
            )
        )
    }

To create a list of data entries which users can select:
1. define a function for the list interface:

    ```
    function initEntryView() {
        let view = ui.getList("example.entries");
        if (view) {
            ui.setListItemDisplayColumns(view, ["header"], [
                { name: "type", tip: "type of entry", width: "5rem", attrs: [], map: e => e.type },
                { name: "date", width: "8rem", attrs: ["fixed", "alignRight"], map: e => util.toLocalMDHMString(e.date)},
            ]);
        }
        return view;
    }
    ```

2. save the list to a variable:

    ```var entryView = initEntryView();```

3. add the list to the window panel:

    ```
    function createWindow() {
        return ui.Window("Example Window", "example", "MyIcon.svg) (
            ui.Panel("Example Data Selection)(
                ui.List("example.entries", 6, selectEntry)
            )
        )
    }
    ```

4. define the functionality on selection:

    ```
    function selectSmokeCloudEntry(event) { // does this need update display panel and set visible?
        selectedEntry = ui.getSelectedListItem(entryView);
        if (selectedEntry) selectedEntry.SetVisible(true);
    }
    ```


To create a checkbox:

1. define the boolean variable that will hold its state

    ``` var followLatest = true; ```

2. set the UI checkbox id to track the state

    ``` ui.setCheckBox("example.followLatest", followLatest);```

3. add the checkbox to the UI window
    ```
    ...
    ui.CheckBox("follow latest", toggleFollowLatest, "example.followLatest"),
    ...
    ```

4. define the function behavior for when the checkbox is checked

    ```
    function toggleFollowLatest(event) {
        followLatest = ui.isCheckBoxSelected(event.target);
        if (followLatest && ui.getSelectedListItemIndex(entryView) != 0) {
            ui.selectFirstListItem(entryView);
        }
    }
    ```
## Data Entry Definition

When using data in the javascript module, data entries will be created from websocket messages according to a class definition

1. initialize variables to hold data:
    ```
    const dataEntries = new Map(); // unique-key -> DataEntries
    var displayEntries = [];
    ```
2. define the data entry class. The core components of the class require functions for set visibile and loading data from the websocket. In the case of GeoJSON contour data, this could look like:

    ```
    class DataEntry {
        ...
        SetVisible (showIt) {
            if (showIt != this.show) {
                this.show = showIt;
                if (showIt) {
                    if (!this.dataSource) {
                        this.loadContoursFromUrl(); 
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

        async loadContoursFromUrl() { // handles new data source
            let renderOpts = this.getRenderOpts(); 
            let response = await fetch(this.url);
            let data = await response.json();

            Cesium.GeoJsonDataSource.load(data, renderOpts).then(  
                ds => {
                    this.dataSource = ds;
                    this.postProcessDataSource();
                    uiCesium.addDataSource(ds);
                    uiCesium.requestRender();
                    //setTimeout( () => uiCesium.requestRender(), 300);
                } 
            );
        }
        ...
    }
    ```

3. Render ops should also be define in the class using the rendering components passed through the config:

    ```
    const defaultContourRender = config.datalayer.contourRender;


    class DataEntry {
        ...
        constructor(dataLayer) {
            // come from server message
            this.render = {...defaultContourRender};
        }

        getRenderOpts() {
            return {
                stroke: this.render.strokeColor,
                strokeWidth: this.render.strokeWidth,
                fill: this.render.fillColors[0],
            };
        }
        ...
    }
    ```

## Data Updates from Websocket

If there is a list of data entries, these will need to be updated accordingly when new data comes in through the websocket. Assuming the UI is configured for the data entry list as in the example above, data entries can be updated by:

1. adding a function to handle websocket messages - note that these are coming from the *service*

    ```function handleWsExampleMessages(msgType, msg) {
        switch (msgType) {
            case "dataLayer":
                handleDataLayerMessage(msg.dataLayer);
                return true;
            default:
                return false;
            }
    }
    ```
2. defining a function to handle the specific data message

    ```    
    function handleDataLayerMessage(dataLayer) {
        let data = DataEntry.create(dataLayer);
        dataEntries.set(dataLayer.url, data);
        if (isSelected(data)) updateEntryView();
        if (followLatest) {
                ui.selectFirstListItem(entryView)
        }
    }
    ```
3. defining a function to update the UI lising the data entries

    ```
    function updateEntryView() {
        displayEntries = util.filterMapValues(dataEntries, data=> isSelected(data));
        displayEntries.sort(DataEntry.compareFiltered);
        ui.setListItems(entryView, displayEntries);
    }
    ```
    