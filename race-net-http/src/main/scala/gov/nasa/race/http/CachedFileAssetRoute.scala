/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.http

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, path}
import akka.http.scaladsl.server.Route
import com.typesafe.config.Config
import gov.nasa.race.common.CachedByteFile
import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.ifSome
import gov.nasa.race.util.ClassUtils.linearizedSuperTypesOf
import gov.nasa.race.util.{ClassUtils, FileUtils, SeqUtils, StringUtils}

import java.io.File
import scala.util.matching.Regex

/**
  * policy object to lookup file/resource data from ordered sequence of directories and classes
  */
class CfaResolver(val fnameMatcher: Regex, var isStatic: Boolean, var dirs: Seq[String], var classes: Seq[Class[_]]) {

  def lookupFile (fileName: String): Option[CachedByteFile] = {
    dirs.foreach { dir=>
      val file = new File(dir,fileName)
      if (file.isFile) return Some( new CachedByteFile(file))
    }
    None
  }

  def lookupResource (name: String): Option[Array[Byte]] = {
    classes.foreach { cls =>
      ifSome(ClassUtils.getResourceAsBytes(cls,name)) { bs=> return Some(bs) }
    }
    None
  }

  def show(): Unit = {
    println(s"""CfaResolver:{"$fnameMatcher", isStatic: $isStatic, dirs:${dirs.mkString("[",",","]")}, cls:${classes.mkString("[",",","]")}}""")
  }
}

/**
  * a RaceRouteInfo that has an (extensible) list of sources for file assets
  *
  * this implementation assumes that all resolvers are added before we get the first request, i.e. resolvers cannot
  * be dynamically updated during runtime.
  */
trait CachedFileAssetRoute extends RaceRouteInfo {

  protected var cfaResolvers: Seq[CfaResolver] = Seq(getThisResolver)

  //--- the caches
  protected var cachedFiles: Map[String, (CachedByteFile,CfaResolver)] = Map.empty
  protected var cachedResources: Map[String,(Array[Byte],CfaResolver)] = Map.empty

  //--- initialization
  config.getOptionalString("resource-map").foreach( addResourceFileAssetResolverMap(_,false))

  // default is a catch-all resolver that only looks at resources for all our RaceRouteInfo mixins

  protected def getThisResolver: CfaResolver = new CfaResolver(StringUtils.AnyRE,true, selfResolveDirs, selfResolveResources)

  // override if we also resolve through the file system
  protected def selfResolveDirs: Seq[String] = Seq.empty

  // override if we don't resolve resources along the class linearization order for CachedFileAssetRoutes
  protected def selfResolveResources: Seq[Class[_]] = linearizedSuperTypesOf( this, classOf[CachedFileAssetRoute])

  def addFileAssetResolver (fnameMatcher: Regex, cls: Class[_]=this.getClass, isStatic: Boolean=true, optDir: Option[String]=None): Unit = {
    cfaResolvers.find( r=> r.fnameMatcher == fnameMatcher) match {
      case Some(res) =>
        res.isStatic = isStatic
        ifSome(optDir) { dir => res.dirs = dir +: res.dirs }
        res.classes = cls +: res.classes

      case None =>
        val res = new CfaResolver(fnameMatcher, isStatic, SeqUtils.optionToSeq(optDir), linearizedSuperTypesOf(this, cls))
        cfaResolvers = res +: cfaResolvers
    }
  }

  /**
   * support for loading CfaResolvers from external file list
   * this is for debugging/development purposes in external apps that don't know if/where RACE files are
   */
  def addResourceFileAssetResolverMap (pathName: String, isStatic: Boolean): Unit = {
    val mapFile = new File(pathName)
    if (mapFile.isFile) {
      FileUtils.fileContentsAsLineArray(mapFile).foreach { pn=>
        val f = new File(pn)
        if (f.isFile) {
          val dirName = f.getParent
          val fName = f.getName
          addFileAssetResolver(new Regex(fName), this.getClass, isStatic, Some(dirName))
        }
      }
    }
  }

  def clearCachedFileAsset (fileName: String): Unit = {
    cachedFiles = cachedFiles - fileName
    cachedResources = cachedResources - fileName
  }

  def clear(): Unit = {
    cachedFiles = Map.empty
    cachedResources = Map.empty
  }

  private var _firstRequest = true
  def showResolvers(): Unit = cfaResolvers.foreach(_.show())

  def completeWithFileAsset (p: Path, pathName: String): Route = {
    val bs = getFileAssetContent(pathName)
    if (bs.nonEmpty) {
      complete( ResponseData.forPathName(pathName, bs))
    } else {
      complete(StatusCodes.NotFound, p.toString())
    }
  }

  def getFileAssetContent(fileName: String): Array[Byte] = {
    //if (_firstRequest) { _firstRequest = false; showResolvers() }

    ifSome(cachedFiles.get(fileName)) { e => return e._1.getContent() }
    ifSome(cachedResources.get(fileName)) { e =>
      // todo - should we re-check if there is a file asset (only for isStatic == false)
      return e._1
    }

    // note we always have an any matcher for class resources at the bottom of the chain
    cfaResolvers.find( cfa => cfa.fnameMatcher.matches(fileName)) match {
      case Some(res) =>
        res.lookupFile(fileName) match {
          case Some(cbf) =>
            cachedFiles = cachedFiles + (fileName -> (cbf,res))
            cbf.getContent()

          case None =>
            res.lookupResource(fileName) match {
              case Some(bs) =>
                cachedResources = cachedResources + (fileName -> (bs,res))
                bs
              case None => Array.empty // no file and no resource- this is the nominal NOT_FOUND path
            }
        }

      case None => Array.empty // we should not get here
    }
  }

  // this completes if 'fileName' is the unmatched path
  def fileAssetPath(fileName: String): Route = {
    path(fileName) {
      complete( ResponseData.forExtension( FileUtils.getExtension(fileName), getFileAssetContent(fileName)))
    }
  }

  //--- support for configurable assets (usually identified by a common path prefix), mapping names into filenames

  def getSymbolicAssetMap(key: String, config: Config, defaults: =>Seq[(String,String)]): Map[String,String] = {
    Map.from( config.getKeyValuePairsOrElse( key, defaults))
  }

  def getSymbolicAssetContent(key: String, map: Map[String,String]): Option[HttpEntity.Strict] = {
    map.get(key).map( fileName => getFileAssetContent(fileName))
  }

  def completeWithSymbolicAsset(key: String, map: Map[String,String]): Route = {
    getSymbolicAssetContent(key, map) match {
      case Some(content) => complete(content)
      case None => complete(StatusCodes.NotFound, key)
    }
  }
}
