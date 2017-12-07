Time Series Analysis
====================
One of the primary use cases of RACE is to analyze streams of timed events such as flight
position messages received via SWIM_ XML messages. Typically, such messages bundle updates for any
number of flights, each flight entry contains its own event time stamp, and we have a total order
over received messages (they are read sequentially through the same input channel).

There are two levels to analyze - message and flight. Each level can be analyzed with respect
to statistics (such as average and peak rate) and anomalies (syntactic and semantic consistency).

Analysis would be useless without reporting results, which involves output format- and destination-
specific processing (XML files, HTML, console output etc.).

Since this applies to at least half a dozen different NAS related input sources, RACE provides
substantial infrastructure to support the generic parts of analysis and reporting, and to provide a
framework for implementation of message type and output format specific components.

.. image:: ../images/ts-analysis.svg
    :class: center scale60
    :alt: Time Series Analysis

Extracting flight objects from bundling XML messages is typically done by means of a
``TranslatorActor`` that is parameterized with a ``XmlParser`` object for the particular XML
message types, which is not the focus of this page. The relevant aspect here is that in both
cases (messages and flights) we end up with irregular time series of items to analyze.

.. image:: ../images/ts-collect-report.svg
    :class: center scale80
    :alt: TSStatsCollector and ReportActor


Analysis
--------

Time Series Statistics
~~~~~~~~~~~~~~~~~~~~~~


Generic Anomalies
~~~~~~~~~~~~~~~~~


.. image:: ../images/ts-anomaly-content.svg
    :class: center scale60
    :alt: Order Based Time Series Anomalies

.. image:: ../images/ts-anomaly-temp.svg
    :class: center scale60
    :alt: Duration Based Time Series Anomalies


Reporting
---------


.. _SWIM: https://www.faa.gov/nextgen/programs/swim/