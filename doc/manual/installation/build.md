# Building RACE

To build RACE, open a terminal window and switch to the directory RACE was
cloned to, then execute [SBT][sbt] from inside of this directory:

    > cd race
    > sbt
    ...
    [info] Loading project definition from ...
    [info] Set current project to race ...
    [race]>

## Compile RACE

The first time SBT is started, it will download a number of external dependencies (plugins, libraries)
that are used by SBT itself. Subsequent starts should be much faster.

Once the SBT prompt appears, start compilation by entering the `compile` command:

    [race]> compile
    [info] Updating ...
    [info] Resolving ...
    ...
    [info] Compiling ...
    [success] Total time: 42 s, completed Jul 16, 2015 6:48:37 PM
    [race]>

Again, this will take longer the first time RACE is built since it will resolve
all external libraries that are required to compile and run RACE.

Once RACE is compiled, you can verify your build from within the SBT console by running

    [race]> run config/local/aircraft-ww.conf
    
This should start RACE and then pop up a WorldWind window showing a single airplane that is updated every
5 sec. Stop RACE by typing '9' + <enter> at the menu prompt in the console, which should bring you
back into SBT.

As a last step, you can build stand-alone scripts to start RACE from outside of
SBT by executing the `stage` command

    [race]> stage
    [info] Packaging ...
    [info] Done packaging.
    [success] Total time: 13 s, completed Jul 16, 2015 9:50:22 PM
    [race]>

The script is created in the `target/universal/stage/bin` directory. The main executable is linked
to `./race`, which is located in the RACE root directory. Please make sure the link has the right
file permissions to be executable.


## Build Documentation
Optionally, SBT can also be used to create RACE documentation (such as this page) in HTML format.
After executing

    [race]> mkDoc
    [info] Reading files from doc/manual
    [info] Parsing 30 markup documents, 1 template, 5 configurations ...
    [info] Rendering 30 HTML documents, copying 35 static files ...
    ...
    
the HTML pages are available under `<racedir>/target/doc` and can be viewed with any browser. This
also creates HTML presentation slides which are kept in `<racedir>/target/doc/slides`.    



[sbt]: http://www.scala-sbt.org/0.13/tutorial/index.html