RACE Documentation Overview
===========================

Work- and Dataflow
------------------
RACE documentation is created from text files that are distributed with this repository, and translated into
HTML and PDF by means of the Laika_ SBT_ plugin. Once translated, all content can be viewed locally in a web browser,
or on the internet through http://nasarace.github.io/race.

This follows the RACE philosophy that all content should be kept under the same version control system in order to be
consistent, and all tools should be integrated into the build system.

.. image:: ../images/docu.svg
    :class: center scale70
    :alt: RACE documentation

The currently supported source formats are  Markdown_ and reStructuredText_ for textual content, and SVG_ for diagrams.
Sources are kept under the ``doc/`` subdirectory, separated into::

    doc/
        images/  : (shared) images and diagrams (SVG files)
        manual/  : user manual (web node)
        slides/  : presentations (HTML based slide shows)

To generate output, the RACE build system includes the following SBT commands

- ``mkManual`` - translates the manual (web node) pages
- ``mkSlides`` - translates the slides
- ``mkDoc`` - translates both manual and slides

These commands can be executed either from within a interactive SBT command prompt or from a operating system shell
(e.g. ``> sbt mkManual``).

Output is generated under the ``target/doc/`` directory::

    target/
        doc/
            images/
            <manual-html-pages>..
            slides/
                <slide-html-pages>..

Note that manual (node) sources are lifted up to ``target/doc``, which mostly serves the purpose of being able to
link to slides, and slides being able to share the same images.

To view locally, use your favorite browsers "open file" command and point it to the respective *.html page, e.g.
``race/target/doc/about.html`` for the start page of the online manual. Note there is no need to commit before
viewing translation results, editorial changes can be kept entirely out of the commit history.
As of this writing, Google Chrome works best to view RACE documentation, especially for printing slides.

The translated documentation is published through `GitHub Pages`_, i.e. the ``target/doc/`` content is copied into a
orphan ``gh-pages`` branch of the RACE repository that is automatically served by GitHub on
http://nasarace.github.io/race.


Translation Directives
----------------------
The translation process is controlled by three special files that are looked up within the directory hierarchy of each
page source

- ``template.html`` - is a Laika specific HTML document with ``{{..}}`` delimited placeholders that are filled in
  by Laika. While templates can be directory- (i.e. section and document type) specific, those are usually just static
  files in the root directory of the respective document type (e.g. "manual")
- ``race-<type>.css`` - are CSS files that can be used to control presentation aspects. Again, those are usually just
  kept in root directories
- ``directory.conf`` - are meta-content files that define how Laika processes the contents of the directory that
  contains the respective file. Each documentation subdirectory should have such a directory.conf file

A typical ``directory.conf`` contains three elements

- ``title`` - which defines how the directory is presented in the navigation bar
- ``navigationOrder`` - controls in which order files and subdirectories within this directory are processed
- ``rootPath`` - is a RACE specific setting which is used to resolve image (SVG) path names. This should point
  to the root directory of the respective documentation type (e.g. ``manual``)

For instance, a ``doc/manual/installation/directory.conf`` file might look like this::

    laika.title = "Installing RACE"

    laika.navigationOrder = [
      prerequisites.md
      download.md
      build.md
    ]

    laika.rootPath = ".."

Please refer to the Laika_ documentation for details about the translation process, which supports extensions at
various different levels.


Presentation Material
---------------------
RACE also uses markup text files and Laika_ to generate slide presentations in HTML. Each presentation is kept in a
single source file, the level 1 header is the presentation title, each level 2 header represents a single slide::

    # My Sample Presentation
    some sub-title

    ## First Slide
    * talking point 1
    * talking point 2

    <img src="images/someDiagram.svg" class="center scale60">

    ## Second Slide
    ...


To add a TOC (list of slide links) to the presentation the first slide should include a *navigationTree*::

    ## Slides
    @:navigationTree { entries = [ { target = "#" } ] }

Given that slide formatting should be kept simple, Markdown_ (*.md) is usually a more readable and compact source format
for slides than reStructuredText_ but it comes with less formatting options. Laika is configured in RACE to support
direct (raw) HTML for both input formats.

Slide shows are translated into single HTML pages that make use of a minimal ``race-slides.js`` javascript file
which implements slide navigation functions. Currently supported commands are

- <enter> - next slide
- <shift-enter> - previous slide
- 'f' - enter full screen (presentation) mode (exit is browser specific, usually <esc>)
- <digit> - go to page 0..9 ('0' being title page)
- <ctrl-digit><digit> - go to pages > 10

Images are best included by means of direct ``<img ..>`` HTML tags, which can make use of style classes such as
``center`` and ``scale60`` (defined in ``race-slides.css``) to control scaling and horizontal alignment.

RACEs presentation support favors simplicity, compact representation (single text file) and availability (view in
browser) over sophisticated layouts and slide transitions. Specialized themes can be implemented by providing
custom template and CSS files.

Since slide layout is based on browser *view height*, and browsers vary in terms of including
decorations such as menubars, slides are best viewed in fullscreen mode.

Online Demos from Slides
------------------------
The ``race-tools`` sub-project includes two command line tools to run interactive demos directly from presentation slides:

(1) **serveDoc** is a simple stand-alone webserver that provides content under ``target/doc`` as ``http://localhost:8080``
(as defaults, both can be set via command line args)

(2) **webrun** is a specialized server that waits for POST requests of ``http://localhost:303x/run``, the actual port
303x refering to a console number 'x' that is provided as a command line argument. Webrun then executes the POST body
as a OS command and sends back the result to the requester

Slides can make use of these servers by including "run" class elements such as::

    ...
    ## Run Demo Slide
    ...
    <div class="run">1: ./race -Darchive=../data/all-080717-1744 config/air/swim-all-sbs-replay-ww.conf</div>
    ...
The element text includes the console number ("``1:``") followed by the program and arguments to execute ("``./race ...``").

With this, the workflow becomes:

1. start ``script/servedoc`` from a command prompt
2. start ``script/webrun --console <n>`` from a command prompt for each required demo console
3. open a browser on ``http://localhost:8080/<presentation>``
4. click on the "run" link in the slide, which will execute the command in the respective webrun console

Please note that slides have to be served by ``servedoc`` (or another server) in order to avoid problem with browser
specific CORS restrictions.

Both tools are small Rust_ programs to minimize the memory footprint during demonstrations, which means they need the
Rust toolchain to be installed in order to build them (e.g. via rustup_). After Rust installation, the tools can
be built from within SBT by executing::

   [race]> project race-tools
   [race-tools]> cargoBuildRelease webrun
   ...
   [race-tools]> cargoBuildRelease servedoc


.. _SBT: http://www.scala-sbt.org/
.. _Laika: https://planet42.github.io/Laika/
.. _SVG: https://www.w3.org/Graphics/SVG/
.. _Markdown: https://daringfireball.net/projects/markdown/
.. _reStructuredText:  http://docutils.sourceforge.net/rst.html
.. _GitHub Pages: https://help.github.com/articles/configuring-a-publishing-source-for-github-pages/
.. _Rust: https://www.rust-lang.org/
.. _rustup: https://www.rust-lang.org/tools/install