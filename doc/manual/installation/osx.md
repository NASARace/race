# OS X Specifics

## 1. Check Java Version
Run the following command from a terminal window

    > java -version

If the reported version is lower than 1.8, run the following command

    > /usr/libexec/java_home -V

to see which Java versions are installed on the machine. If there is a 1.8 Java,
set `JAVA_HOME` in `~/.profile` accordingly. Close and reopen terminal window,
and re-check Java version.

If no Java 1.8 is installed on the machine, get it from [Oracle's download node][jdk]

## 2. Install SBT
Check if SBT is installed

    > which sbt

If this does not show a valid path, install SBT with [homebrew][brew]. If

    > which brew

does not show a working homebrew installation, proceed with a homebrew user
space installation

    > cd ~/
    > mkdir homebrew && curl -L https://github.com/Homebrew/homebrew/tarball/master | tar xz --strip 1 -C homebrew
    > export PATH=$PATH:$HOME/homebrew/bin
    > mkdir $HOME/Library/Caches

The last step is to avoid that `brew` uses the global`/Libary/Caches` directory
as a fallback, which on some OS X installations is not world writable. Run `brew
--cache` to check which cache `brew` is using, and if it does not pick up the
user cache do a `export HOMEBREW_CACHE=$HOME/Library/Caches`

If brew was installed, make sure the package database is up-to-date by running

    > brew update

Now install SBT by running

    > brew install sbt

## 3. Clone RACE
If Git is not installed on your machine, install it with homebrew by running

    > brew install git

Download RACE sources from the Git repository

    > cd <your-project-root-dir>
    > git clone https://github.com/NASARace/race.git


## 4. Build RACE
Start SBT from within the directory you cloned RACE to, build RACE artifacts by
running it's `stage` command, and exit SBT

    > cd race
    > sbt
    ...
    [race]> stage
    ...
    [race]> exit

## 5. Run RACE
From within the RACE directory, execute the `bin/race` shell script, providing
the configuration file to run as a command line argument. For instance, to run
the WorldWind integration demo, run

    > bin/race config/local/aircraft-ww.conf

Other example configurations can be found in the `config/` directory. To shut
down, the WorldWind demo, close the WorldWind window, then hit the `enter` key
in the terminal window and select the `exit` option


[jdk]: http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html
[brew]: http://brew.sh/

