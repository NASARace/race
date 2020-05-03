#!/bin/bash
# script to build macoshelper.jar to avoid cross-module reflection errors when
# using com.apple.ewt.* APIs to work around ScreenDevice bugs for macOS Java 9+ implementations.
# See MacOSHelper.java

rm -f *.class
rm -f *.jar
rm -rf gov

javac --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED --target 9 --source 9 -d . MacOSHelper.java
#jar --create --file macoshelper.jar gov

# NOTE - we now add the *.class explicitly to the race-swing / Compile / unmanagedClasspath
# and to race-swing / Compile / packageBin / mappings
# so that dependent projects no longer have to explicitly include a unmanaged macoshelper.jar

#rm -f *.class
#rm -rf gov

