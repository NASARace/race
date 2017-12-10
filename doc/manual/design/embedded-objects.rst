Embedded Objects
================
Many actors in RACE user inner objects to adapt otherwise generic actor functions such
as translation, data import or archiving to specific data types, IO formats and destinations. This
multi-layer approach to achieve genericity can be seen in examples such as ``ArchiveActor`` (writer),
``TranslatorActor`` (translator), ``HttpServer`` (routes), ``RaceViewerActor`` (layers) and others.

The pattern of using nested objects and delegation to achieve specialization is so common that it is
supported by RACEs `Runtime Configuration`_ and the ``RaceActor`` API.

Configuration
-------------
RACEs HOCON_ based configuration files use a hierarchical structure with object nesting to specify
and parameterize objects::

    ...
    actors = [ ...
      { //--- general config
        name = "httpServer"
        class = ".http.HttpServer"

        //--- type specific config
        host = "localhost"
        ...

        routes = [
          //--- nested object
          { name = "statsReporter"
            class = ".http.HttpStatsReporter"

            read-from = "/stats"
           ...
          }, ...
        ]
      } ...


This structure can nest to any level, but usually there is just a top level actor (e.g. ``"httpServer"``)
with one or more embedded delegation objects (``"statsReporter"``). Each object needs at least a
``class = <classname>`` spec. A ``name = <object-name>`` is mandatory for top level actors and optional
for most embedded objects. RACE configs are open, i.e. apart from the mandatory class name they can
have any number of parameters only the concrete class has to know about - there is no central registry
that needs to be updated when parameter names are added or removed.


Instantiation API
-----------------
To instantiate embedded objects from configuration files, the ``RaceActor`` trait provides the
following APIs that can be used from inside of actor constructors:

* ``configurable[T](key: String): Option[T]`` - for optional embedded objects
* ``getConfigurable[T](key: String): T`` - for mandatory objects (throws exception if missing)
* ``getConfigurableOrElse[T](key: String)(f: => T): T`` - for mandatory objects using defaults
* ``getConfigurables[T](key: String): Array[T]`` - for lists of embedded objects
* ``configurable[T](conf: Config): Option[T]`` - for deeper level objects using the actor class loader

In all cases, the compiler has to be able to infer the type of the requested object class in order
to runtime check the instantiated object. This can be done by providing the field type in the
actor constructor, but more commonly instantiation is factored out into a separate ``createX(): T``
method so that embedded object types can be hard-wired in more specific  actor sub-classes (see for
instance ``gov.nasa.race.actor.TranslatorActor`` and ``gov.nasa.race.air.actor.SBSTranslatorActor``)::

    class TranslatorActor (val config: Config) extends SubscribingRaceActor ... {

      var translator: Translator[Any,Any] = createTranslator

     // override this to use a hardwired translator
     protected def createTranslator: Translator[Any,Any] = getConfigurable[Translator[Any,Any]]("translator")


.. _HOCON: https://github.com/typesafehub/config/blob/master/HOCON.md
