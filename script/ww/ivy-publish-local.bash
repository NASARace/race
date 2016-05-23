#!/bin/bash

#
# publish worldwind.jar to the local Ivy repository (~/.ivy2/local/ by default) so that other
# projects can use it from within dependency management build systems without having to copy
# the worldwind and jogl jars
#
#

REV=2.0.0-`svnversion -n`

ant
ivy -publish local -revision "$REV" -status integration -publishpattern "[artifact].[ext]" -overwrite
rm -f "ivy-$REV.xml"