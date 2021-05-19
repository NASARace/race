# How to Run RACE

There are various ways to run RACE, depending on if you are a developer or a user, and whether RACE
is supposed to be used stand-alone or in a networked environment. The two major modes are 

 - local - running on the user machine
 - remote - running on remote machine with installed RACE

For the remainder of this page we assume that you have built RACE by running `sbt stage` once.

## Local RACE

The preferred way to run RACE locally is to start its `gov.nasa.race.ConsoleMain` application. 
Outside of SBT, this can be done by executing the `./race <configfile>` script from the command 
line, e.g.

    > ./race config/local/aircraft.conf
  
The main argument is the [configuration file][Race Configuration] to run, which defines which actors
to run, and how these actors are connected.

Executing `./race --help` will list the supported command line options, of which the following ones
are particularly of interest

`--delay` - initialize, but wait for an explicit user command to start the simulation run

`--vault <path> [--keystore <path> [--alias <keyname>]]` - this allows the use of configuration
files with [encrypted values][Using Encrypted Configurations] (e.g. for values such as passwords). Without a
`--keystore` specification, the user will be prompted for a vault password. If there is a
keystore/alias argument, the user will be prompted for the keystore password and the vault will
be decrypted with the key (alias) that is stored in it. If any of the passwords are not valid,
RACE will terminate right away

Once RACE is running, it should present a menu on the command line that can be used to control its
execution:

    ...
    enter command [1:show universes, 2:show actors, 3:show channels, 4:send message, 5:set loglevel, 8:start, 9:exit]
    ...
    
If RACE was executed with the `--delay` option, you can start the simulation by entering '5'
You can terminate RACE at any time by entering '9'. Hitting the `<enter>` key will re-display the menu


## Remote RACE

To run RACE on a remote machine, use the `gov.nasa.race.remote.ConsoleRemoteLauncher` or the 
`gov.nasa.race.remote.RemoteLauncherServer`. This is further explained in 
[launching RACE remotely][RemoteLauncher]