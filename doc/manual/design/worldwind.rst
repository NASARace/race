WorldWind Viewer
================
One of the primary use cases of RACE is to visualize large geospatial, dynamic data sets, such as
live flight positions within the whole NAS. Respective applications vary widely in terms of:

- input sources
- context aware rendering of display relevant data
- interactions

Rendering intensive graphical user interfaces tend to require a large amount of code that is often
platform specific. In addition, related code is difficult to develop because user interface
frameworks are often not thread safe, and thus need explicit, error prone and hard to test
synchronization between asynchronous data acquisition- and user interface (event dispatcher) threads.

RACE includes substantial infrastructure to mitigate these challenges. Centerpiece of this
infrastructure is `NASA WorldWind`_ - an open sourced Java- and `OpenGL`_-based geo viewer
that runs on all major platforms and can be embedded into various applications.

Layers and Panels - RACEs Extension Points
------------------------------------------
The primary `WorldWind concept`_ in the context of RACE is the RenderableLayer_, which represents
a display relevant data set that can be separately controlled in terms of visibility, rendering details and
updates. RACE uses ``RaceLayers`` to map its bus channels to WorldWind layers. While a ``RaceLayer``
is a GUI object that executes within the user interface thread, it has an associated
``RaceLayerActor`` which is responsible for data acquisition by means of channel subscription.
Since ``RaceLayerActors`` execute within Akka threads, they use a dedicated queue within the
associated ``RaceLayer`` to perform the thread-safe handover of display data.

The second general concept is the *UI panel*, which represents a part of the user interface outside
of, but potentially interacting with, WorldWind. RACE comes with panels for various tasks such as
controlling view positions, selecting layers and displaying information about selected objects.

UI panels are collapsible and stacked in a column to the left of the toplevel window, whereas
WorldWind occupies the large canvas to the right of the panel column.

.. image:: ../images/race-viewer.svg
    :class: center scale80
    :alt: WorldWind Viewer

WorldWind is incorporated into RACE applications by means of a ``RaceViewerActor``, which is just
a normal ``RaceActor`` within RACE configurations. However, ``RaceViewerActors`` themselves can
be extensively configured with both ``RaceLayers`` and (less often) UI panels::

    universe {
        ...
        actors = [
            ...
            { name = "geoViewer"
              class = "gov.nasa.race.ww.RaceViewerActor"
              ...
              layers = [
                  { name = "livePos"
                    class = "gov.nasa.race.ww.air.FlightPosLayer"
                    read-from = "/live/fpos"
                    description = "SWIM sfdps flight positions" ...
                  },
               ...

Just as ``RaceLayerActors``, the ``RaceViewerActor`` has a UI dual in form of the ``RaceView``, which
is both the aggregation and the mediator_ between the configured panels and the WorldWind window.


Synchronized Viewers
--------------------
The data that is acquired through RACE channels does not have to represent external entities such
as aircraft, and does not need to be displayed in ``RaceLayers``. The whole RACE viewer
infrastructure lends itself naturally to use RACE channels on the global bus for the purpose of
synchronizing RaceViewers across the network.

To that end, the viewer infrastructure includes a ``SyncPanel``/``SyncActor`` pair, which can publish
viewer changes such as eye position and layer selection to a global channel, and conversely subscribe
to this channel to update the local display with remote viewer changes. This is a powerful basis
to implement applications such as situation rooms because it

- supports control of synchronization aspects at the local node
- requires minimal data transfer for updates
- is robust in terms of re-synchronization

Moreover, by using different synchronization channels it is easy to create and switch between different display groups.

Viewer synchronization is *not* hardwired or restricted to a fixed set of viewer parameters such as eye position.

Since application specific data and rendering is provided through new ``RaceLayers``, those layers are also responsible
for adding related synchronization commands. For instance, the generic ``FlightLayer`` implementation includes a number
of commands for selected flight objects, such as hiding/showing flight paths. This is achieved by overriding the
``changeObject(objectId: String, action: String)`` method of the ``RaceLayer`` class - layers are free to add their own
object ids and action keys.

The ``SyncPanel`` implementation that is distributed with RACE supports synchronization control for

- eye positions
- operation on the selected layer
- operations on the selected layer object

Synchronization resonance (bouncing back external synchronization commands) is prevented by means of blackout periods.
Synchronization events are only sent out if they don't exceed a configurable threshold duration since the last local
user input.

If applications need more control they can provide their own panel and configure it instead of the standard sync panel.

.. _NASA WorldWind: https://worldwind.arc.nasa.gov/
.. _WorldWind concept: https://worldwind.arc.nasa.gov/index.html?root=java
.. _OpenGL: https://www.opengl.org/
.. _RenderableLayer: http://builds.worldwind.arc.nasa.gov/worldwind-releases/daily/docs/api/gov/nasa/worldwind/layers/RenderableLayer.html
.. _mediator: https://en.wikipedia.org/wiki/Mediator_pattern