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

package gov.nasa.race.util

import java.io.File
import java.net.{URL, URLClassLoader}

import FileUtils._

import scala.reflect._

/**
 * utilities to manage class loading
 */
object ClassLoaderUtils {

  private lazy val systemCL = ClassLoader.getSystemClassLoader
  private lazy val mAddURL = systemCL.getClass.getMethod("addURL", classOf[URL])

  def addToGlobalClasspath (url: URL) = {
    mAddURL.setAccessible(true)
    mAddURL.invoke(systemCL, url)
  }

  def getURLs (urlSpecs: String): Array[URL] = {
    if (urlSpecs.charAt(0) == '@') { // urls from file
      val file = new File(urlSpecs.substring(1))
      fileContentsAsUTF8String(file) match {
        case Some(s) => s.split("[,;\n ]+").map(new URL(_))
        case None => Array.empty[URL]
      }

    } else { // local dirs and jars
      urlSpecs.split("[,;:]+").map { s =>
        if (s.endsWith(".jar")) {
          new URL(s"jar://$s")
        } else {
          new URL(s"file://$s")
        }
      }
    }
  }

  def extendGlobalClasspath (cpSpec: String) = getURLs(cpSpec).foreach(addToGlobalClasspath)

  private var clMap = Map[Any,ClassLoader]()

  def setRaceClassloader (key: Any, optCpSpec: Option[String]): ClassLoader = {
    optCpSpec match {
      case Some(urlSpecs) =>
        val urls = getURLs(urlSpecs)
        if (!urls.isEmpty) {
          val cl = new URLClassLoader(urls)
          clMap += (key -> cl)
          return cl
        }
      case None =>
    }
    return getClass.getClassLoader
  }

  def loadClass[T] (clAnchor: Any, name: String, clsType: Class[T]): Class[_ <:T] = {
    val cl = clMap.getOrElse(clAnchor, getClass.getClassLoader)
    val clsName = if (name.startsWith(".")) "gov.nasa.race" + name else name
    cl.loadClass(clsName).asSubclass(clsType)
  }

  // <2do> should we try to match on argument supertypes and/or subsets?
  def newInstance[T: ClassTag](clAnchor: Any, clsName: String,
                               argTypes: Array[Class[_]], args: Array[_ <:AnyRef]): Option[T] = {
    try {
      val cls = loadClass(clAnchor,clsName,classTag[T].runtimeClass)
      if (args == null || args.isEmpty){
        // if no extra args, fall back to default ctor
        Some(newInstanceOf[T](cls))
      } else {
        try {
          val ctor = cls.getConstructor(argTypes: _*)
          Some(ctor.newInstance(args: _*).asInstanceOf[T])
        } catch {
          // if no ctor for args is found, fall back to default ctor
          case _: NoSuchMethodException => Some(newInstanceOf[T](cls))
        }
      }
    } catch {
      case t: Throwable =>
        t.printStackTrace
        None
    }
  }

  // Class.newInstance is deprecated in Java > 8
  def newInstanceOf[T](cls: Class[_]): T = cls.getDeclaredConstructor().newInstance().asInstanceOf[T]

  //--- class verification support

  def codeSource[T: ClassTag]: Option[URL] = {
    val cls = classTag[T].runtimeClass
    val codeSrc = cls.getProtectionDomain.getCodeSource
    if (codeSrc != null) Some(codeSrc.getLocation) else None
  }

  def sha1OfJar[T: ClassTag]: Option[String] = {
    codeSource[T].flatMap { url =>
      if (url.getProtocol == "file"){
        FileUtils.sha1CheckSum(new File(url.getPath))
      } else {
        return None
      }
    }
  }

  // note this could be still caught, but since verifyCodeSource() should preclude any
  // use of a critical class, we should never be able to get past the guard
  private final class CodeSourceException(details: String) extends SecurityException(details)

  private var codeSourceMap: Map[String,JarSpec] = null

  /**
    * this is to be called ONCE from the main entry, before loading any user code.
    * Any subsequent call should cause a SecurityException.
    * Use a JarClassLoader ("java -jar ..") to ensure the main entry is not overridden
    * Note that entries do not cause the respective classes to be loaded here
    */
  def initializeCodeSourceMap (entries: Seq[(String,JarSpec)]) = {
    if (codeSourceMap != null) throw new SecurityException("attempt to overwrite codeSourceMap")

    // <2do> we could check here if this is called from Main, but it is questionable what added
    // security that gives if we don't trust the Main itself to call us right in the first place.
    // What really is important is that it can't be called repetitively, from un-trusted locations
    //if (new Exception().getStackTrace.length != 3) throw new SecurityException("codeSourceMap has to be set from main()")
    codeSourceMap = Map(entries:_*)
  }

  /**
    * this is a guard that should precede any use of a security sensitive type. If it doesn't
    * check out against the previously (Main-) initialized codeSourceMap, we should never get
    * past the ensuing SecurityException
    * Note - this should only be called once since sha-1 and signature verification can be expensive
    *
    * @tparam T type to be verified
    */
  def verifyCodeSource[T: ClassTag]: Unit = {
    if (codeSourceMap != null) {
      val cls = classTag[T].runtimeClass
      val e = codeSourceMap.get(cls.getName)
      if (e.isDefined) {
        val codeSrc = cls.getProtectionDomain.getCodeSource
        if (codeSrc != null) {
          val jarSpec = e.get
          val url = codeSrc.getLocation
          if (url.getProtocol == "file" && url.getFile().endsWith(jarSpec.jarFile)){
            FileUtils.sha1CheckSum(new File(url.getFile)) match {
              case Some(jarSpec.sha1) => // all Ok
              case other => throw new CodeSourceException(s"class $cls loaded from jar with wrong sha1: $other")
            }
          } else throw new CodeSourceException(s"class $cls not loaded from ${jarSpec.jarFile}")
        } else throw new CodeSourceException(s"class $cls loaded by bootstrap class loader")
      } else throw new CodeSourceException(s"class $cls not in known set of verifiable classes")
    }

    // if there is no codeSourceMap set, we let it pass - it is Main's responsibility to set one
  }
}

// <2do> we could also add a (optional) signature
case class JarSpec (jarFile: String, sha1: String)
