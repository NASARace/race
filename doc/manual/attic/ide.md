# Loading RACE into IDEs

## Eclipse
RACE is configured to include the [sbteclipse SBT plugin][sbteclipse] to create
the required configuration files to load it into the [Eclipse IDE][eclipse]. The
following procedure has been tested with Eclipse 4.5.0 (Mars):

  1. create Eclipse config files from a command prompt (please note the `skip-parents=false`
     option when running the `eclipse` task):

    > cd race
    > sbt ...
    [race]> eclipse skip-parents=false ...
    [race]> exit

  2. start Eclipse
  3. make sure the workspace directory is ouside the RACE directory (e.g. if
     RACE was installed in `~/projects/smartnas/race`, choose `~/projects/smartnas`
     as workspace)
  4. make sure the [ScalaIDE for Eclipse plugin][scalaide] is installed. At the
     time of this writing, the respective Eclipse update node is:
     "http://download.scala-ide.org/sdk/lithium/e44/scala211/stable/node"
     restart Eclipse after you installed the plugin
  5. import RACE into Eclipse:

   * "File->Import->General->Existing Projects into Workspace"
   * choose RACE root directory (e.g. `~/projects/smartnas/race`)
   * MAKE SURE TO SELECT "Search for nested projects" OPTION
   * click finish

Please note that the various Eclipse config files are __not__ checked into the
repository, they just reside on your machine and hence you have to rerun the `eclipse`
task within SBT each time you do a fresh clone of the RACE repository.

Eclipse 4.5 does not require any additional plugin to highlight or preview
markdown (*.md) text files.


## IntelliJ IDEA
The [IntelliJ IDE][intellij] needs to have the "Scala"" plugin installed:
"IntelliJ IDEA->Preferences->Plugins->Install Jetbrains Plugin".

Optionally, you can also install the "Markdown support" plugin from Jetbrains during
the same step.

Once the plugins have been installed and the IDE is restarted, simply use
"File->New->Project from Existing Sources" and select the RACE root directory
(e.g. `~/projects/smartnas/race`), choosing "Import project from external model->SBT".

Again, IntelliJ configuration files are excluded from the repository and hence
the project has to be re-imported after a fresh clone of the repository.


[sbteclipse]: https://github.com/typesafehub/sbteclipse
[eclipse]: http://www.eclipse.org/
[scalaide]: http://scala-ide.org
[intellij]: http://www.jetbrains.com/idea/