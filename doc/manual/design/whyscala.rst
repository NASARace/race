Why Scala
=========

Akka supports actor programming in Scala and Java. Since the resulting binaries run both on a Java virtual machine,
both coexist at runtime. Moreover, since using Java classes from Scala is seamless, choosing the programming language
is not a critical decision. However, there are compelling reasons for RACE development in Scala:

(1) Documentation and Examples - since RACE heavily uses Akka, and Akka is implemented in Scala, most existing
literature and examples use Scala. This extends to online forums such as `stackoverflow`_, which are often the
primary source of information for specific development questions. Using Scala is the most efective way to leverage on
existing information and avoid additional interface layers.

(2) Scala fits well into the actor programming model. The main feature of actors are their ``receive`` message
dispatchers, and Scala's support for Partial Functions and deep pattern matching makes the respective code both
more readable and type safe. In Java, pattern matching has to be replaced with (nested) ``if (..instanceof..)``
statements, with no compiler support to detect missing cases and type mismatches.

(3) Scala addresses existing actor pitfalls. Actors are mostly a way to implement concurrent executions without shared
memory that would require error prone synchronization. However, this separation can be broken by using variable data in
actor constructor arguments, within message objects exchanged between actors, or from global objects. In any of these
cases actors are not guaranteed to prevent data races. Scala's emphasis on immutable data (``val`` instead of ``var``)
mitigates this pitfall, which is particularly true for its elaborate collection library (which defaults to immutable).

Those benefits come at a price - as a programming language, Scala is not nearly as mainstream as Java, although it is by
now well established. Scala's focus on functional programming allows for a very compact and orthogonal code base that
often requires less than half the size of comparable Java, but the level of abstraction that is the basis for this can
be considerably more challenging for novices. Lastly, some of this abstraction does carry over into runtime costs, in
terms of more classes, additional function calls and missing bytecode utilization (e.g. ``iinc``, which can make a
substantial difference for hotspot loops that need to manipulate counter/index variables in non-monotonic ways and
cannot easily be rewritten as tail recursive functions). For that reason, RACE does employ a (very limited) set of
Java classes.


.. _stackoverflow: http://stackoverflow.com/questions/tagged/akka