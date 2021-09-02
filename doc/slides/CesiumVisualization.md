# Browser based Visualization

## Slides
@:navigationTree { entries = [ { target = "#" } ] }

## In-Process: race-ww
* ⬆ no serialization/deserialization
* ⬆ dense GUI support (race-ww, race-swing)
* ⬇︎ foundation has eroded (WorldWind, JOGL, OpenGL)
* ⬇ WorldWind needs major rewrite to make use of contemporary GPUs

<img src="../images/ww-visualization.svg" class="center scale60"/>
<div class="run">1: ./race -Darchive=../data/all-080717-1744 config/air/swim-all-sbs-replay-ww.conf</div>

## WorldWind Viewer Infrastructure
* RACE provides

    - async thread-safe data import for *Layers*
    - extensible UI framework of (Swing) *Panels*

<img src="../images/race-ww.svg" class="center scale60">


## Browser Visualization: race-net-http
* ⬆ no end-user install (RACE used as web server)
* ⬆ 3rd party libs future proof (akka-http, Cesium)
* ⬇ requires network IO plus serialization/deserialization per app object update

<img src="../images/cesium-visualization.svg" class="center scale60"/>
<div class="run">1: ./race -Darchive=../data/all-080717-1744/sbs.txt.gz config/net/cesium-track-server.conf</div><a class="srv" href="http://localhost:8080/tracks"></a>


## RACE HttpServer
* department (low/medium traffic) server
* specifically for dynamic content (track updates)

<img src="../images/http-server.svg" class="center scale75"/>