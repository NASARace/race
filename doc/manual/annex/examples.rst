RACE Examples
=============
RACE comes with a variety of sample configuration files, but many of them do require access to online data that
is not publicly available. This section provides a progression of examples to familiarize yourself with basic
RACE functions. In addition to running the examples, please have a look at the respective configuration files
to understand what the example is supposed to do.

While some examples can be executed from within SBT by means of the ``run <config-file>`` task, a number of the examples
make use of multiple communicating RACE instances. It is therefore recommended to build RACE by running (once)::

    > sbt stage

Referring to `How to Run RACE`_ section, each example is executed from the command line by providing a
configuration file as the command line argument, e.g. from within the RACE root directory::

    > ./race <path-to-config-file>

Some examples will require additional arguments.

Basic Examples
--------------
**(1) config/local/aircraft.conf**  is a basic example that only consists of two actors: a simple aircraft
example that publishes ``FlightPos`` messages, and a ``ProbeActor`` that prints messages it receives from subscribed
channels::

    > ./race config/local/aircraft.conf

This example mostly serves the purpose of verifying that RACE was built correctly. The RACE console menu can be obtained
by hitting <enter>, use option '2' to see configured actors, and option '9' to terminate RACE

**(2) config/local/aircraft-ww.conf** the next progression is to display the modeled aircraft positions by means of
RACEs viewer infrastructure, which uses a embedded WorldWind_ instance.

Use the mouse wheel or touchpad gestures to zoom in on the modeled plane. Select the ``flights`` layer in the ``layers``
panel, click on the ``query`` button to get a list with displayed aircraft (just one in this example), select the
aircraft in the list view and check some of the display options. Double clicking on the line will expand the ``selected
object`` panel that shows live updates on positions and provides an additional view option to automatically center the
WorldWind display on the airplane position.

The ``selected object`` panel can also be brought up by double clicking on the plane symbol within the WorldWind view.
The eject button can be used to dismiss the panel. Click on the green/red arrows next to the panel titles to
collapse/expand the respective panel. Again, terminate by entering option '9' from the console window that was used to
start RACE.

Distributed Operation
---------------------
The next two examples show how RACE instances can communicate by means of remote actors, and require two console
windows to start

**(3) config/remote-lookup** is an example of how to run RACE with a remote actor that was started upfront (there also
is a ``remote-start`` example that uses a generic RACE configuration to launch external actors). This is basically the
same as the ``config/local/aircraft.conf`` example, only that the aircraft model actor is running in a separate process.
Note this is fully achieved by means of configuration and uses exactly the same actor code.

In this example we use the ``--info`` command line option of RACE to obtain additional logging output. From the first
console, start the satellite instance::

    > ./race --info config/remote-lookup/satellite.conf

Once the RACE prompt appears, start another RACE instance from a second console::

    > ./race --info config/remote-lookup/master.conf

Terminate in reverse order (master, then satellite) from respective console windows. Look at the logging output in both
console windows to see the initialization, start and termination sequences.


**(4) remote-sync** is an example that shows how RACE channels can be used to synchronize several RACE viewers. From a first
console, execute::

    > ./race config/remote-sync/satellite1-viewer.conf

Once the WorldWind window appears, locate the ``sync`` panel and click on the ``active`` option. Now start the second
RACE instance from another console::

    > ./race config/remote-sync/master-viewer.conf

As a first effect, you should see the modeled aircraft position appearing as a red dot in both views. Now click on the
``active`` checkbox of the ``sync`` panel of the new RACE viewer, which will synchronize both views. Zoom in, rotate
the globe or select the aircraft from any view and you should see the other view automatically following.

Select the ``airport`` layer in the ``layers`` panel, and then select one of the items in the ``goto airports``
combo box of the ``selected layer`` list - both views should simultaneously pan&zoom in on the selected airport.

Again, terminate the master and then the satellite.

Live Data Import
----------------
So far, none of the examples has used real data, but if you happen to have access to an ADS-B receiver - or maybe even
a SWIM server feed - you can use the following configuration files to access that data.

**(5) config/imports/sbs-fpos.ww.conf** allows to read raw ADS-B data from port 30003 of the machine that runs RACE.
Please see instructions on the PiAware_ website for how to build an inexpensive ADS-B receiver. If the decoder
(e.g. dump1090_) is not running on the same machine, port forward via ssh before starting RACE, e.g. by executing::

    > ssh  -L 30003:localhost:30003 <user>@<receiver-host>

Then start RACE from a second console with::

    > ./race config/imports/sbs-fpos-ww.conf

You should see the live ADS-B data from your receiver appearing in the WorldWind view. Select the ``ads-b`` layer,
click on the ``update`` checkbox, click the ``query`` button and all flights should be appear in the layerinfo list.
Select ``inherit`` and then ``path``, and all flights (including new ones) should have their respective flight paths
displayed.

**(6) config/imports/swim-all-sbs-ww.conf** is a much bigger example that imports live data from a SWIM server. Since
this is non-public information such a server most likely requires access credentials, which means you need to use
encrypted configurations and provide a vault pass phrase when starting RACE (please see the `Runtime Configuration`_
and `Using Encrypted Configurations`_ sections for details).::

    > ./race --vault <path-to-encrypted-config> config/imports/swim-all-sbs-ww.conf

Please also note that the example configuration uses a portmapper actor to access the SWIM server through a gateway,
which requires additional interactive user authentication for the gateway. This mostly serves as an example of how
to do large scale, realtime data import from secure sources.


Data Replay
-----------
In case you don't have access to an ADS-B receiver or a SWIM feed, you can obtain recorded data from the
`race-data`_ project on GitHub.

PLEASE MAKE SURE YOU HAVE THE `GIT-LFS`_ EXTENSION INSTALLED BEFORE CLONING `race-data`_

Since `race-data`_ only contains (compressed) recorded data and configuration files, there is no need to build anything.
Assuming you installed race-data inside the same directory as RACE itself, you can start the recording like this::

    > ./race ../race-data/sbs-KNUQ-070516-1417/sbs-replay-ww.conf

Please note that most recordings have about 30sec delay time before the replay starts. This replay is faithful. It
uses the same format as example (5), only from a different input source (file instead of receiver), which shows how
easily actors can be re-used in a different context.

At the time of this writing, `race-data`_ only contains publicly available ADS-B recordings (each about 1h of live data
from the San Francisco Bay Area). As the same mechanism can be applied to full US airspace recordings at about a
5MB/min, we hope to include additional, larger data sets in the future.


.. _PiAware: http://flightaware.com/adsb/piaware/
.. _dump1090: https://github.com/MalcolmRobb/dump1090
.. _race-data: https://github.com/nasarace/race-data
.. _GIT-LFS: https://git-lfs.github.com/
.. _WorldWind: https://github.com/NASAWorldWind