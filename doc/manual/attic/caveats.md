# Caveats

## RACE

## SBT

### test library dependencies
To set a library  dependency for `test`, don't use

    libraryDependencies in Test += someLib
    
This is never used within the `test` task. Instead, use Ivy configuration mappings as in

    libraryDependencies += someLib % "test"
    
See [this post](https://groups.google.com/forum/?fromgroups#!topic/simple-build-tool/kb439MydLSY)
for explanations. In general, use of SBT configurations isn't intuitive (to say the least)