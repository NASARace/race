# RACE Configuration

The RACE executable is a generic driver that just instantiates and initializes
actors according to configuration files that are specified as command line
arguments. Those files use the [HOCON][hocon] format, which is a JSON dialect
that improves readability by means of extensions such as string substitutions and
includes.

Each concrete simulation (aka *RACE run*) is fully specified by its configuration file(s). The main
parts of a configuration file is the name of the actor system, and an ordered list of actors:

    name = <universe-name>
    ...
    actors = [
      {
         name = <actor-name>
         ...
      }, ...
    ]
    
The order in which actors are specified does matter since it defines the order in which they are
created, initialized, started and (in reverse order) terminated. Please refer to 
[RaceActor lifecycle][RaceActors] for details.

Each actor needs to have a `name` property that uniquely identifies the actor within this universe.

Actors running within this RACE process, or to be started on (already running) remote RACE processes,
need a `class` specification, such as

    { name = "test-aircraft-1"
      class = "gov.nasa.race.actors.models.SimpleAircraft"
      ...
    }

Actors running within a (remote) RACE process need a `remote` URI, specifying the respective 
protocol, remote universe name, host and port, e.g.

    { ...
      remote = "akka.tcp://satellite-1@localhost:2552"
      ...
    }

Please refer to [how to use remote actors][Remote RaceActors] for further details.


Communication between actors is defined by means of `read-fom` and `write-to` values, which contain
the names of respective channels, e.g.

    { name = "fnear2fpos"
      class = "gov.nasa.race.actors.translators.FlightsNear2FlightPos"

      read-from = "flightsnear/json"
      write-to = "fpos"
      ...

Channel names are strings that should have a path structure, to support (prefix based) channel set 
subscriptions (such as `flights/*`). Both `read-from` and `write-to` values can be either single
strings (`read-from = "test"`) or arrays of strings (`read-from = [ "channel-1", "channel-2", ..]`)

Channel names starting with `/local/..` are not shared between connected RACE systems.

[remoting]: ../
[hocon]: https://github.com/typesafehub/config/blob/master/HOCON.md