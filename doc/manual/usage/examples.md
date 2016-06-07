# RACE examples
Before running the examples, make sure all three involved RACE executables have
been built and stages (by means of SBT)

    > sbt
    ...
    [race]> stage
    ...
    [race]> project swimServer
    [swimServer]> stage
    ...
    [swimServer]> project swimClient
    [swimClient]> stage
    ...
    [swimClient]> exit
    >

The following examples are included in the RACE distribution:

## 1. One process basic configuration

    > script/race config/local/aircraft.conf

This example consists of two actors running in the same process: one aircraft
model that produces flight position messages, and one actor reading those
messages and printing them to the screen.

To terminate, hit `<cr>` and select the menu option "3" (exit)


## 2. One process geospatial viewer (WorldWind)

    > script/race config/local/aircraft-ww.conf

This example replaces the screen printout actor with a [WorldWind](wwd) instance
that is interfaced by means of an actor endpoint, rendering the flight position
updates by means of OpenGL.

To terminate, close the WorldWind window, and then proceed with RACE termination
as in example 1


## 3. JMS import
This example involves two processes that should be started from different
terminal windows. This example uses an actor that connects to a JMS server with
a configured URI, subscribes to a configured JMS topic, and publishes received
JMS message payloads to a specified RACE channel. The second actor subscribes to
this channel and prints its messages on the screen

Within the first window, execute

    > script/swimserver

Once the server is up, switch to the second window and execute

    > script/race config/imports/jms.conf

This starts RACE, connecting to the JMS broker. One RACE is up, switch back to
the first window and send a test message by entering menu option "1". The RACE
window should show the received test message.

To terminate RACE follow the steps of example 1. You can leave the swimServer
running as it is also used in the next example.


## 4. JMS import and export
This is a three process example that demonstrates how RACE can itself act as a
SWIM (JMS) server. It adds two new actors:
  * an embedded JMS broker (running a [ActiveMQ](amq) instance within RACE) that
    implements the external facing, SWIM personality of RACE
  * a JMS export actor that listens to a RACE channel and send all received messages
    through a direct (non-network, in-memory) connector to the ActiveMQ broker

In the first window start the swimServer as in the previous example

In the second window, start RACE with

    > script/race config/exports/jms.conf

In the third window, start a (RACE unaware) JMS client, specifying the port on
which RACE publishes its JMS messages:

    > script/swimclient -p 61617

If the `swimClient` would be started without `-p` it would connect to the
swimServer instead of RACE.

Now send a test message from the `swimServer` window (menu option "1") - it
should be received by RACE, re-published through the embedded JMS broker, and
then received by the `swimClient`

To shut down, exit the `swimClient`, then RACE, and finally the `swimServer`

## 5. Remote actor lookup
This example shows how actors can be configured to run in separate
processes/machines without requiring any changes to their code. It is a
distributed version of the first example in which the configuration of the
aircraft model (including its type) is only known to the satellite.

In the first window, start RACE running an aircraft model in satellite mode:

    > script/race -s config/remote-lookup/satellite.conf

Please note the `-s` option of RACE. Once the RACE satellite is up, start the
RACE master from a second window

    > script/race config/remote-lookup/master.conf

This should look up and then connect to the remotely running aircraft model, and
then produce the same output as example 1.

To terminate, first exit the RACE satellite and then the RACE master.

It should be noted that while the messages contain the same `FlightPos` objects
as in the first example, the system automatically marshals/unmarshals those
objects when sending them over the network - neither the producer (aircraft
actor running in the satellite) nor the consumer (probe actor running in the
master) need to be aware of remoting. This represents full **location
transparency**.


## 6. Remote actor startup
This is a variation of the previous example in which the remote actor is not
explicitly launched but automatically started through a satellite launch daemon
upon request by the RACE master, using master configuration data.

In the first window, start the satellite launch daemon

    > script/race -s

In the second window, start the RACE master

    > script/race config/remote-start/master.conf

This should automatically start the aircraft model in the satellite, which then sends
its flight position messages to the master, which displays the messages using the
probe actor.

Please follow the same termination sequence as before.


[wwd]: http://worldwind.arc.nasa.gov/java/
[amq]: http://activemq.apache.org/