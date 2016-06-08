## RACE - Building Distributed Actor Systems

The *Runtime for Airspace Concept Evaluation* (RACE) is a software architecture and framework to
build configurable, highly concurrent and distributed message based systems. Among other things,
RACE can be used to rapidly build simulations that span several machines (including synchronized
displays), interface existing hardware simulators and other live data feeds, and incorporate
sophisticated visualization components such as NASA’s [WorldWind](https://goworldwind.org/) viewer.

RACE is implemented as a distributed [actor system](https://en.wikipedia.org/wiki/Actor_model) built
on top of the [Akka](http://akka.io/) framework, primarily uses [Scala](http://www.scala-lang.org/)
as programming language and runs within Java Virtual Machines.


### Learn More

Please refer to our [RACE website](http://NASARace.github.io/race) for more details about:

  - [prerequisites](http://NASARace.github.io/race/installation/prerequisites.html)
  - [how to build RACE](http://NASARace.github.io/race/installation/build.html)
  - [running RACE](http://NASARace.github.io/race/usage/running.html)
  - [RACE configuration](http://NASARace.github.io/race/usage/configuration.html)
  - [RACE design](http://NASARace.github.io/race/design/overview.html)


### License

RACE was developed at the [NASA Ames Research Center](https://www.nasa.gov/centers/ames/home/index.html)
and is distributed under the Apache v2 license, quoted below:

    Copyright (c) 2016, United States Government, as represented by the 
    Administrator of the National Aeronautics and Space Administration. 
    All rights reserved.
    
    The RACE - Runtime for Airspace Concept Evaluation platform is licensed 
    under the Apache License, Version 2.0 (the "License"); you may not use 
    this file except in compliance with the License. You may obtain a copy 
    of the License at http://www.apache.org/licenses/LICENSE-2.0.
    
    Unless required by applicable law or agreed to in writing, software 
    distributed under the License is distributed on an "AS IS" BASIS, 
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
    See the License for the specific language governing permissions and 
    limitations under the License.