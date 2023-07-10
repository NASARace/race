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
package gov.nasa.race.http

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import gov.nasa.race.util.{FileUtils, NetUtils}
import scalatags.Text
import scalatags.Text.all.{script, _}

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, Set => MutSet}

/**
  * RaceRouteInfo that serves a single page application, i.e. manages a single (possibly dynamic) Document
  *
  * main responsibility of this trait is to dynamically build the document from our various sub-type traits
  * TODO - this should be able to use UserAgent header, requestUri and location to serve specialized content
  */
trait DocumentRoute extends RaceRouteInfo {

  protected def completeDocumentRequest: Route = {
    val doc = getDocument()
    complete( HttpEntity(ContentTypes.`text/html(UTF-8)`, doc))
  }

  def documentRoute: Route = {
    get {
      path(requestPrefixMatcher) {
        completeDocumentRequest
      }
    }
  }

  override def route: Route = documentRoute ~ super.route

  /** include only the first occurrence of each element */
  protected def filterUnique( frags: Seq[Text.TypedTag[String]]): Seq[Text.TypedTag[String]] = {
    // normally the Scala compiler will do the job for us if fragments are associated with mix-in traits,
    // but it is possible that two different traits depend on the same fragment
    // note that we have to foldLeft to preserve linearization order
    val seen = MutSet.empty[Text.TypedTag[String]]
    frags.foldLeft(ArrayBuffer.empty[Text.TypedTag[String]])( (list,tt) => {
      if (seen.contains(tt)) {
        list
      } else {
        seen.add(tt)
        list.append(tt)
      }
    }).toSeq
  }

   /*
    * the content creators to be provided by the concrete type
    * TODO - do we need to pass in requestUri and remoteAddr to allow for location and user agent specific documents?
    */
  protected def getDocument(): String = {
    "<!DOCTYPE html>" +
    html(
      scalatags.Text.all.head(
        // entries have to be unique (don't load scripts or resources twice)
        filterUnique(getPreambleHeaderFragments),
        filterUnique(getHeaderFragments),
        filterUnique(getPostambleHeaderFragments)
      ),
      body()(
        // here we do allow repetitions
        getPreambleBodyFragments,
        getBodyFragments,
        getPostambleBodyFragments,

        if (jsModules.nonEmpty) {
          script( tpe:="module")(postExecJsModule)
        } else ""
      )
    ).render
  }

  //--- support for collecting JsModules over all fragments of a document

  protected val jsModules: mutable.LinkedHashSet[String] = mutable.LinkedHashSet.empty

  protected def clearJsModules(): Unit = jsModules.clear()

  // called from micro-service traits
  override protected def addJsModule(jsModule: String): Text.TypedTag[String] = {
    val jsModulePath = modPath(jsModule)
    jsModules.add(jsModulePath)
    script(src:=jsModulePath, tpe:="module")
  }

  protected def postExecJsModule: String = {
    // using static imports it should not matter if we intersperse imports and postExec calls but for
    // the sake of clarity we keep the (blocking) imports on top
    val baseNames = ArrayBuffer.empty[String]
    val sb = new StringBuilder()
    sb.append("\n")
    jsModules.foreach { jsMod =>
      val baseName = NetUtils.getBaseName(jsMod)
      baseNames += baseName
      sb.append(s"import * as $baseName from '$jsMod';\n")
    }
    baseNames.foreach { baseName =>
      sb.append(s"if ($baseName.postExec) $baseName.postExec();\n")
    }
    sb.append("console.log('js modules initialized.');\n")

    sb.toString
  }

}

/**
  * a DocumentRaceRoute that adds a main module and css
  *
  * this can be used for initialization that cannot be done in the order of our linearized traits, and for css
  * that should override default values
  */
trait MainDocumentRoute extends DocumentRoute {
  val mainModule: String
  val mainCss: String

  def mainModuleContent: Array[Byte]
  def mainCssContent: Array[Byte]

  def mainResourceRoute: Route = {
    get {
      path(mainCss) {
        complete( ResponseData.forExtension("css", mainCssContent))
      } ~ path(mainModule) {
        complete( ResponseData.forExtension("js", mainModuleContent))
      }
    }
  }

  override def route: Route = mainResourceRoute ~ super.route

  override def getHeaderFragments: Seq[Text.TypedTag[String]] = super.getHeaderFragments ++ mainResources

  def mainResources: Seq[Text.TypedTag[String]] = {
    Seq(
      link(rel:="stylesheet", tpe:="text/css", href:=mainCss),
      script(src:=mainModule, tpe:="module")
    )
  }
}