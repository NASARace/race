# Known Problems

## OpenCL on macOS >= 10.14
Since Apple deprecated OpenCL in macOS 10.14 it has become increasingly unstable on some 2019 15" MacBookPro
machines, resulting in spurious native errors (CL_DEVICE_NOT_AVAILABLE, "No CVMS service" and others). There is no
known workaround yet - OpenCL on Apple hardware seems unreliable

## tcp socket failures in wireless networks
In high data volume import applications such as config/air/swim-all-sbs-ww.conf import threads sometimes seem to
hang due to underlying socket streams being closed while the socket is still connected. This happened under macOS
10.15.7 and OpenJDK build 16+36-2231. While actors that directly handle the socket (such as SbsImportActor) should
now try to reconnect, actors that use 3rd party libraries such as ActiveMQ to obtain input seem to not detect this case
and stop to import. It seems this only happens when using wireless network connections (not Ethernet)

## problems with WorldWind/JOGL native libraries on Linux
Some Linux distributions have JDK packages that cause linker (ld.so) errors when loading
the native libraries required by WorldWind. The remedy is to use https://github.com/pcmehlitz/WorldWindJava-pcm.git
versions >= 2.1.0.202, which include snapshots of compatible native libraries as unmanaged dependencies

## incompatible class file errors during RACE startup
While RACE could still work on Java 1.8, it uses a lot of 3rd party libraries (automatically
downloaded from Maven Central by SBT) which at this point have been built on newer Java versions
and hence cause incompatible class file errors during RACE load time. It is therefore recommended
to always use the latest stable Java/JDK release.

## Windows console output
The standard Windows 10 console (command prompt) does not enable ANSI terminal sequences (color
and control), which is used by RACEs `ConsoleMain` driver (menus). As a workaround, use the
open sourced [ansicon](https://github.com/adoxa/ansicon) to enable ANSI sequences.

## RACE not starting because of RaceViewerActor timeout
On machines with slow graphics and network configurations that use a RaceViewerActor (WorldWind)
might not start because the actor does run into initialization timeout, especially if the map
cache is not populated. As a quick fix, start RACE with

    ./race -Drace.timeout=30 ...
    
If this succeeds, you can specifically set a `create-timeout` in the actor config of the
RaceActorViewer (each actor can be parameterized with `create-timeout`, `init-timeout` and
`start-timeout`, referring to respective actor lifetime phases)

[OpenJDK]: https://jdk.java.net
