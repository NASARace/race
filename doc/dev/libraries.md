# Libraries used by RACE

## Standard Scala Libs:

  * [2.11.8 scaladoc](http://www.scala-lang.org/api/current)


## XML Scala libraries: scala.xml
Has been moved from standard scala libs into separate project

  * [javadoc](http://www.scala-lang.org/api/current/scala-xml)

## System Configuration: com.typesafe.config
library to manage system configuration by means of HOCON/JSON files

  * https://github.com/typesafehub/config
  * [javadoc](http://typesafehub.github.io/config/latest/api/)
  * SBT: `libraryDependencies += "com.typesafe" % "config" % "1.3.0"`

Typical import is

    import com.typesafe.config.Config

## Actor System: com.typesafe.akka
The big one, partitioned into about 10 sub-modules, `akka.actor` being the
most important one

  * http://akka.io/
  * [online documentation](http://doc.akka.io/docs/akka/2.4-M2/scala.html)
  * [scaladoc](http://doc.akka.io/api/akka/2.4-M2/#package)
  * SBT: `libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.4-M2"`


## Time Management: org.joda.time and nscala.time
`org.joda.time` is Java library with better support for time based operations
than `java.util.Date`. It is normally not directly imported but used through
`nscala.time`

  * http://www.joda.org/joda-time
  * [javadoc](http://www.joda.org/joda-time/apidocs)
  * [repository](https://github.com/JodaOrg/joda-time)
  * SBT: `libraryDependencies += "joda-time" % "joda-time" % "2.8.1"`

`nscala.time` adds Scala operators and automatically imports `org.joda.time`

  * https://github.com/nscala-time/nscala-time
  * [scaladoc](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/nscala-time/nscala-time_2.11/2.0.0/nscala-time_2.11-2.0.0-javadoc.jar/!/index.html#package)
  * SBT: `libraryDependencies += "com.github.nscala-time" %% "nscala-time" % "2.0.0"`

## Binary Codec Support: scodec
Parser combinators for reading and writing binary data, including streams. Supports
both HList and Pair based parser specs. HList ('shapeless') supports automatic codec
generation for case classes

  * http://scodec.org/
  * https://github.com/scodec/scodec

Depending on required features, imports are

    import scodec._       // always
    import scodec.bits._  // always
    import codecs._       // builtin codecs
    import scalaz.\/      // constructing HList codecs


## Dimensional Analysis: squants
Computation with physical units (*not* value classes)

  * http://www.squants.com/
  * [scaladoc](http://www.squants.com/)
  * SBT: `libraryDependencies += "com.squants"  %% "squants"  % "0.6.0-SNAPSHOT"`