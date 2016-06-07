# Non-Functional Requirements for RACE

This document lists technical requirements that are specific to RACE design and
implementation.

## Uniform Programming Model

__(R-100.1)__ RACE SHALL support a uniform design and interface for user provided
components

Rationale: RACE is a framework that has to facilitate efficient development of
concrete simulations incorporating both adapters to existing subsystems and
new simulation components. To that end, RACE has to provide a uniform programming
model with documented reference design and related APIs for all user provided
components


## Deterministic Behavior

### System Initialization
__(R-100.2.1)__ RACE SHALL have deterministic initialization behavior.

__(R-100.2.1.1)__ RACE SHALL not start simulation before all actors are initialized.

### Race conditions
__(R-100.2.2)__ RACE SHALL be free of data race conditions.

RACE is a inherently concurrent and parallel system. However, concurrency management
such as creating and assigning threads and enforcing synchronized access of shared
memory should be confined to the framework (library) layer and should not be
exposed to or used by the user provided components (actors)

__(R-100.2.2.1)__ RACE actors SHALL not expose internal state to other actors.

While Akka based actors written in Scala promote a design that is not exposing
mutable actor internal state, this can be violated when:

  * using explicit actor constructors

      system.actorOf(Props(new MyActor(..exposedData..),..)

  * passing arguments into reflection based actor construction

      system.actorOf(Props(classOf[MyActor],exposedData),..)

  * sending messages that contain references to internal state

      someActorRef ! SomeMessage(..exposedData..)


## Scalability

__(R-100.3)__ RACE SHALL be scalable.

__(R-100.3.1)__ RACE SHALL be horizontally scalable.

__(R-100.3.1.1)__ RACE SHALL support location transparency for actors.


## System Response Times

__(R-100.4)__ RACE shall fulfill system response time requirements. 

### Starvation

__(R-100.4.1)__ RACE components SHALL not cause starvation.

### User Interface

__(R-100.4.2)__ RACE user interfaces SHALL respond within a given amount of time.

This is a soft-realtime requirement

## Fault Management 

__(R-100.5)__ RACE SHALL detect failures.

__(R-100.5.1)__ RACE SHALL detect loss of network connection.

__(R-100.5.2)__ RACE SHALL detect component exceptions.

## Security

__(R-100.6)__ RACE SHALL support encryption of secure data in configuration files.

## Run-time monitoring

__(R-100.7)__ RACE SHALL collect and calculate metrics for running actors.

__(R-100.7.1)__ RACE SHALL calculate message rates.



  

