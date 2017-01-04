Repository Structure
====================

The RACE repository contains three named branches: ``master``, ``release`` and ``gh-pages``:

(1) The ``master`` branch of the repository is the development branch that always tries to link against the most recent
versions of dependencies. Although it is the development branch it should always compile and run.

(2) The ``release`` branch is the basis for the last published artifacts on the Central Repository. At a minimum, this
branch contains a modified ``project/Dependencies.scala`` that only uses explicit version numbers (symbolic
specifications such as "latest.release" are not supported by Maven Central).

(3) The ``gh-pages`` (orphan) branch contains the HTML files generated from ``doc/``. This is the RACE website as published on
http://nasarace.github.io/race/, see `RACE Documentation Overview`_ for details.
