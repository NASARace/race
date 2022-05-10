/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.util

import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import scala.collection.mutable.ArrayBuffer

/**
  * java.lang.Class related utility functions
  */
object ClassUtils {

  def getResourceAsUtf8String(cls: Class[_], name: String): Option[String] = {
    val is = cls.getResourceAsStream(name)
    if (is != null) {
      Some( new String(is.readAllBytes(), "UTF-8"))
    } else None
  }

  def getResourceAsBytes(cls: Class[_], name: String): Option[Array[Byte]] = {
    val is = cls.getResourceAsStream(name)
    if (is != null) {
      Some( is.readAllBytes())
    } else None
  }

  def getMethod(cls: Class[_], name: String, argTypes: Class[_]*): Option[Method] = {
    try {
      Some(cls.getMethod(name,argTypes:_*))
    } catch {
      case x:NoSuchMethodException => None
    }
  }

  // linearize a type hierarchy. Note this is an approximation since Scala does not have to create a separate
  // class object for every mixin trait, but since we mostly use this to look up resources it will do
  // this is DFS bottom up order
  def linearizedSuperTypesOf(obj: AnyRef, commonType: Class[_]=classOf[AnyRef]): Seq[Class[_]] = {

    def linearize (cls: Class[_], lin: ArrayBuffer[Class[_]]): Unit = {
      if (commonType.isAssignableFrom(cls)){
        val sc: Class[_] = cls.getSuperclass
        if (sc != null && commonType.isAssignableFrom(sc)) linearize(sc,lin)

        cls.getInterfaces.foreach { c=>
          if (commonType.isAssignableFrom(c)) linearize(c,lin)
        }

        if (!lin.contains(cls)) lin += cls
      }
    }

    val lin = ArrayBuffer.empty[Class[_]]
    linearize(obj.getClass, lin)
    lin.reverse.toSeq
  }
}
