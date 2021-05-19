# RemoteLauncher
Run RACE Remotely


## Slides
@:navigationTree { entries = [ { target = "#" } ] }


## RemoteLauncher - one sentence purpose
<p class="box">
Start, monitor and terminate remote RACE processes in a secure way from a central location
</p>

Dissection:

* what is a remote RACE process?
* what does it need to start one?
* what are the protected resources?


## (Excursion) What do we run - RemoteMain processes
1. `RemoteMain` driver instantiating `RaceActorSystem` with config
2. `RaceActorSystem` instantiating `Master` actor
3. `Master` actor instantiating configured `RaceActors`

<img src="../images/race-overview-2.svg" class="center scale60">

## (Excursion²) Config is Everything
* configs are HOCON text files (`com.typesafe.config`)
* configs use a declarative language (graph with RaceActors as nodes and channels as edges)

        universe {
          name = "mysimulation"
          ...
          actors = [
            { name = "aircraft"
              class = "gov.nasa.race.actors.SimpleAircraft"
              write-to = "/flightpos"
              heading = 42
              ...
            },
            { name = "probe"
              class = ...
              read-from = "/flightpos"
           ...

## (Excursion²) Config Zoom In
<img src="../images/race-config.svg" class="center scale65">


## (Excursion³) Secret Config Values - Vault
* some config values are secret (uid, passwords)
* "??" prefixed config values are *vault* keys

<img src="../images/race-vault.svg" class="center scale70">


## (Excursion⁴) RaceActors Zoom In
<img src="../images/raceactor.svg" class="center scale85">


## RemoteLauncher - the man in the middle
different concerns:

* front end: UI, config selection
* RemoteLauncher: config files, gateway, remote processes
* RemoteMain: actors

<img src="../images/remotelauncher-context.svg" class="center scale50">


## RemoteLauncher Zoom In

  <img src="../images/remotelauncher-2.svg" class="center scale70">


## Physical Protocol: Requirements
* need to start remote processes in secure way:
    + requires remote launch daemon
    + need to transmit encrypted (non-interactive) user authentication
* need to exchange encrypted data with launched process:
    - control data (RemoteLauncher ↔︎ RemoteMain)
    - sim input (private server → RemoteLauncher gateway → remote actors)
    - sim log output (remote actors → RemoteLauncher)

* minimize number of ports through firewall (admin footprint)


## Physical Protocol: SSH
* all encrypted, trusted daemon + client, known port
* public key authentication (no login shell required)
* tunnel control socket (separated from stdio/logging/data streams)
* tunnel data server sockets

<img src="../images/reverse-portmap.svg" class="center scale50">


## Logical Protocols

  <img src="../images/remotelauncher-proto.svg" class="center scale75">
