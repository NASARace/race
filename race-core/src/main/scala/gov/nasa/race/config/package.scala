/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race

import com.typesafe.config.{Config, ConfigFactory, ConfigValue}

/**
  * package gov.nasa.race.config contains classes that are used for RACE configuration, based on
  * the strategic com.typesafe.config library
  */
package object config {

  final val NoConfig = ConfigFactory.empty

  def emptyConfig = ConfigFactory.empty

  /**
    * most abstract interface of something that can be configured
    */
  trait Configurable {
    def config: Config
  }

  /**
    * something that has a 'config' object with a 'name' entry
    */
  trait NamedConfigurable extends Configurable {
    // we can't use ConfigConversions, this is toplevel
    def name = try { config.getString("name") } catch { case _: Throwable => getDefaultName }

    // override if there is a more specific default name
    def getDefaultName = getClass.getName
  }

  trait ConfigurableTranslator extends Translator[Any, Any] with NamedConfigurable

  trait ConfigurableTimeTranslator extends TimeTranslator[Any] with NamedConfigurable

  trait ConfigurableFilter extends Filter[Any] with NamedConfigurable

  trait ConfigValueMapper extends Translator[Any, ConfigValue] with NamedConfigurable

}
