# About RACE

As the name *Runtime for Airspace Concept Evaluation* implies, RACE was created as a framework to
simplify building airspace simulations that are

 - extensible (you can build your own applications with it)
 - scalable (support distributed RACE instances with varying levels of concurrency)
 - heterogenous (can interface to external systems)
 - portable (run anywhere)

RACE itself is also meant to be **accessible**, it is open sourced under Apache v2 license, only 
depends on open sourced 3rd party components, and leverages existing technologies, formats and tools
wherever possible.

RACE is **not** a monolythic system or standalone tool. Its main executable expects a configuration
text file (normally using [HOCON][hocon] syntax) that specifies which components your simulation
consists of, and how these components communicate.

The underlying application model is a reactive, distributed, event based system. The programming model
to process such events is the well known [Actor Programming Model][actors], which uses dedicated
objects (*actors*) that only communicate through asynchronous messages and don't share internal state.
This has a profound impact on how RACE can make use of massive concurrency without burdening the 
developer with explicit synchronization, which is otherwise notoriously hard to design and test.

RACE uses the [Akka][akka] library as a basis for its actor infrastructure, and hence promotes the
use of [Scala][scala] as the programming language to develop such actors (although Java can be used
too - see [why Scala](dev/why-scala.md) for details).

The main aspects that RACE adds on top of plain Akka are

 - runtime configuration of actors
 - deterministic construction, initialization, start and termination of actor systems
 - seamless location transparency (actors can run locally or remote without requiring code changes)
 - time management (simulation clock)
 - coexisting, seamless local and distributed publish/subscribe communication between actors

To achieve this, RACE employs its own *RaceActor* classes, which are the building blocks of
its applications. While the standard RACE distribution comes with a number of ready-to-use
RaceActors for tasks such as importing external data, this is the major extension axis of RACE:
coming up with your RaceActors and letting RACE run them in the context of larger simulations.


[actors]: https://en.wikipedia.org/wiki/Actor_model
[akka]: http://akka.io/
[hocon]: https://github.com/typesafehub/config/blob/master/HOCON.md
[scala]: http://www.scala-lang.org/