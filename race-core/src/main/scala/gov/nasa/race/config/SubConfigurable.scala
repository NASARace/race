/*
 * Copyright (c) 2021, United States Government, as represented by the
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
package gov.nasa.race.config

import com.typesafe.config.{Config, ConfigException}
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.util.ClassLoaderUtils

import scala.reflect.{ClassTag, classTag}

/**
  * something that has configurable components
  */
trait SubConfigurable extends Configurable {

  def clAnchor: Any = this // override this if there is a specific object to identify the ClassLoader to use

  def newInstance[T: ClassTag](clsName: String,
                               argTypes: Array[Class[_]]=null, args: Array[Object]=null): Option[T] = {
    ClassLoaderUtils.newInstance( clAnchor, clsName, argTypes, args)
  }

  // NOTE: apparently Scala 2.12.x does not infer the type parameter from the call context, which can lead
  // to runtime exceptions. Specify the generic type explicitly!

  def configurable[T: ClassTag](conf: Config): Option[T] = {
    val clsName = conf.getString("class")
    newInstance[T](clsName, Array(classOf[Config]), Array(conf))
  }

  /**
    * try to instantiate the requested type from a sub-config that includes a 'class' setting
    * if there is no sub-config but a string, use that as class name
    * if there is no sub-config or string, fallback to arg-less instantiation of the requested type
    *
    * note - see above comments reg. inferred type parameter
    */
  def configurable[T: ClassTag](key: String): Option[T] = {
    try {
      config.getOptionalConfig(key) match {
        case Some(conf) =>
          newInstance(conf.getString("class"),Array(classOf[Config]),Array(conf))

        case None => // no entry for this clAnchor
          // arg-less ctor is just a fallback - this will fail if the requested type is abstract
          Some(ClassLoaderUtils.newInstanceOf(classTag[T].runtimeClass))
      }
    } catch {
      case _: ConfigException.WrongType => // we have an entry but it is not a Config
        // try if the entry is a string and use it as class name to instantiate, pass in actor config as arg
        // (this supports old-style, non-sub-config format)
        newInstance(config.getString(key), Array(classOf[Config]), Array(config))

      case _: Throwable => None
    }
  }
  /** use this if the instantiation is mandatory */
  def getConfigurable[T: ClassTag](key: String): T = configurable(key).get
  def getConfigurableOrElse[T: ClassTag](key: String)(f: => T): T = configurable(key).getOrElse(f)

  def getConfigurables[T: ClassTag](key: String): Array[T] = {
    config.getConfigArray(key).map( conf=> newInstance(conf.getString("class"),Array(classOf[Config]),Array(conf)).get)
  }

  //-- for instantiation from sub-configs that are not Configurables themselves (e.g. for embedded objects)
  def getConfigurable[T: ClassTag](conf: Config): T = configurable(conf).get
  def getConfigurableOrElse[T: ClassTag](conf: Config)(f: => T): T = configurable(conf).getOrElse(f)
}
