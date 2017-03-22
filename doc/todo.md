# TODO List

### web based StatsReporter

### RAS instantiation error should be explicit
currently uses the same timeout for actors (Master) and whole system (RAS), i.e. we run into
a separate timeout for the RAS

### RaceLogger should support LogConsole
since it is required for per-actor logging, it should be capable of writing to a socket
stream

### make Dispatcher, RaceViewerActor ParentRaceActors
we want to get rid of the redundant child management in RaceActor
