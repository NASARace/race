# Services

RACE applications, including ODIN applications, are constructed from a set of MicroServices which are specified using a *Service*.

At a high level, a *Service* appears like an actor that subscribes to messages from a designated import actor, generates document content (e.g., JS configuration), then sends the data from the import actor to the browser via a websocket connection. However, *Services* could be subject to data races betweein incoming http/websocket requests and RACE bus data acquisition.

More specifically, *Services* are traits specifying a MicroService consisting of related:
  - document content (e.g., internal and external JS modules), with one special component being a dynamically generated config.js which generates js module default values from and application's (e.g., ODIN) *.conf
  - DataClient handling (e.g., messages received from the RACE bus through an adapter actor), which builds/maintains the data sent to new clients
  - websocket handling (e.g., messages sent to/received from corresponding JS module running on connected browsers - mostly used for data push)
 

The file defining the service should be placed in the following structure:
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
│   │   │   │   │   ├── DataAvailable.scala
```


The core code components of a service are:

1. Define default rendering options - these are considered document content that will be passed to a JS module

    ex: 
    ```
    class DefaultRendering (conf: Config) {
    val strokeWidth = conf.getDoubleOrElse("stroke-width", 1.5)
    val strokeColor = conf.getStringOrElse( "stroke-color", "grey")
    val fillColors = conf.getNonEmptyStringsOrElse( "fill-color", Array("#ffffff", "#bfbfbf","#808080", "#404040", "#000000"))

    def jsFillColors: String = {
        StringUtils.mkString( fillColors, "[",",","]")( clr=> s"Cesium.Color.fromCssColorString('$clr')")
    }

    def toJsObject = s"""{ strokeWidth: $strokeWidth, strokeColor: Cesium.Color.fromCssColorString('$strokeColor'), fillColors:$jsFillColors }"""
    }
    ```

2. Define an object that contains the relevant resources, import the object's resources - these are related to document content and specify JS modules used to render data

    ex:
    ```
    object MyService {
        val jsModule = "ui_cesium_myExample.js"
        val icon = "MyIcon.svg"
        }
    import MyService._
    ```

3. Define the service trait by mixing in other necessary traits. Within the service, define:
    
    ex:
    ```
    trait MyDataService extends CesiumService with FileServerRoute with PushWSRaceRoute with CachedFileAssetRoute with PipedRaceDataClient with JsonProducer
    ```
    a. If the data is dynamic, define a case class for the recieved data - note that this class constructor takes an instance of the imported data's class. This component is necessary for DataClient handling.

    ex: 

    ```
    case class DataLayer (data: DataAvailable) { 
    // note that DataAvailable will have to be defined in a separate scala class
    // this serves as the resource name and also a unique key 
        val urlName = f"${data.date.format_yMd_Hms_z}" // add any other unique identifier info
        val json = data.toJsonWithUrl(s"my-data/$urlName") // defined in DataAvailable class
    }
    ```
    
    b. Variable for storing the recieved data. This component is necessary for DataClient handling.

    ex: 

    ```
    protected val dataLayers: mutable.LinkedHashMap[String,DataLayer] = mutable.LinkedHashMap.empty // urlName -> DataLayer

    ```

    c. Protocol for recieving the data. This component is necessary for DataClient handling.

    Note that receiving data could be subject to a data race with sending data over the websocket.

    ex:

    ```
    override def receiveData: Receive = receiveMyData orElse super.receiveData

    def receiveMyData: Receive = {
        case BusEvent(_,dataAvail:DataAvailable,_) =>
        val data = DataLayer(dataAvail)
        addDataLayer(data)
        push( TextMessage(data.json))
    }

    def addDataLayer(data: DataLayer): Unit = synchronized { dataLayers += (data.urlName -> data) }
    def currentDataLayerValues: Seq[DataLayer] = synchronized { dataLayers.values.toSeq }

    ```

    d. Route definition - the route serves items to make them available to the JS module. This component is necessary for DataClient handling.

    ex:

    ```
    def myRoute: Route = {
        get {
        pathPrefix("my-data" ~ Slash) {
            extractUnmatchedPath { p =>
            val pathName = p.toString()
            dataLayers.get(pathName) match {
                case Some(data) => completeWithFileContent(data.dataAvail.file) 
                case None => complete(StatusCodes.NotFound, pathName)
            }
            }
        } ~
            pathPrefix("specifc-data" ~ Slash) { 
            extractUnmatchedPath { p =>
                val pathName = s"specifc-data/$p"
                complete( ResponseData.forPathName(pathName, getFileAssetContent(pathName)))
            }
            } ~
            fileAssetPath(jsModule) ~
            fileAssetPath(icon)
        }
    }

    override def route: Route = myRoute ~ super.route
    ```

    e. Websocket definition - this is used to push data from the service to the client JS module

    ex:
    ```
    protected override def initializeConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = {
        super.initializeConnection(ctx, queue)
        initializeDataConnection(ctx,queue)
    }

    def initializeDataConnection (ctx: WSContext, queue: SourceQueueWithComplete[Message]): Unit = synchronized {
        val remoteAddr = ctx.remoteAddress
        currentDataLayerValues.foreach( sl => pushTo(remoteAddr, queue, TextMessage(sl.json)))
    }

    ```
    f. Document content (header fragments) - this component sends necessary document content to the JS module

    ex:

    ```
    override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ Seq(
        extModule("ui_cesium_myExample.js")
    )
    ```

    g. Client config - including default rendering settings. This document content component generates a config.js file dynamically, which is then used to instantiate the corresponding JS module.

    ex:
    ```
    override def getConfig (requestUri: Uri, remoteAddr: InetSocketAddress): String = super.getConfig(requestUri,remoteAddr) + dataLayerConfig(requestUri,remoteAddr)

    def dataLayerConfig(requestUri: Uri, remoteAddr: InetSocketAddress): String = {
        val cfg = config.getConfig("datalayer")
        val defaultRendering = new DefaultRendering(cfg.getConfigOrElse("contour.render", NoConfig))

        s"""
        export const datalayer = {
        contourRender: ${defaultRendering.toJsObject},
        followLatest: ${cfg.getBooleanOrElse("follow-latest", true)}
        };"""
    }
    ```

4. Single page application definition. Here different MicroServices can be mixed in to one application with all the services available. When an App object is created,all it's MicroService traits are consulted in type linearization order in a chain-of-responsibility pattern to collect all required document parts and install all required handlers. 

    ex:
    ```
    class CesiumApp(val parent: ParentActor, val config: Config) extends DocumentRoute with MyDataService with ImageryLayerService
    ```


