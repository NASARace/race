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
package gov.nasa.race.common

import java.io._
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.transform.Source
import javax.xml.transform.stax.StAXSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.{SchemaFactory, Validator}

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.util.FileUtils
import org.w3c.dom.ls.{LSInput, LSResourceResolver}
import org.xml.sax.{ErrorHandler, SAXParseException}

import scala.beans.BeanProperty
import scala.collection.{Map,Seq}
import scala.collection.mutable.{HashMap, ListBuffer}

/**
  * a XML validation filter that supports lookup of schemas based on the message 'xmlns' attribute value
  * For each namespace we can have multiple schema files which are all combined
  */
class XmlValidationFilter(val schemaFiles: Seq[File], val config: Config= null) extends ConfigurableFilter {

  def this (schemaFile: File) = this(Seq(schemaFile),null)

  final val NoNamespace = ""
  val tnsExtractor = new XmlAttrExtractor("targetNamespace", e => {e == "xs:schema"} ) // for schemas
  val xmlnsExtractor = new XmlAttrExtractor("xmlns", e => true) // for messages, 'xmlns' attr of top element
  val inputFactory = XMLInputFactory.newInstance

  var lastError: Option[String] = None

  protected val xh = new ErrorHandler {
    def error (x: SAXParseException) = lastError = Some(x.getMessage)
    def fatalError (x: SAXParseException) = lastError = Some(x.getMessage)
    def warning  (x: SAXParseException) = {}
  }

  val validators: Map[String,Validator] = createValidators

  //--- the message filtering

  def pass(o: Any): Boolean = {
    o match {
      case msg: String => validate(msg)
      case Some(msg:String) => validate(msg)
      case None => false
      case _ =>
        lastError = Some(s"unsupported message type: ${o.getClass}")
        false
    }
  }

  def validate (msg: String): Boolean = {
    try {
      lastError = None
      val msgSrc = new StAXSource(inputFactory.createXMLStreamReader(new StringReader(msg)))
      val ns = xmlnsExtractor.parse(msg).getOrElse(NoNamespace)
      validators.get(ns) match {
        case Some(validator) => validator.validate(msgSrc)
        case None => error(s"no schema for namespace '$ns'")
      }
      lastError.isEmpty
    } catch {
      // we shouldn't get any with our error handler
      case t: Throwable =>
        lastError = Some(t.getMessage)
        false
    }
  }

  //--- initialization

  def createValidators: Map[String,Validator] = {
    val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
    //schemaFactory.setResourceResolver(new ClasspathResourceResolver)
    schemaFactory.setErrorHandler(xh) // set this before we create the schema, to catch schema errors

    schemaFiles.foldLeft(HashMap.empty[String,ListBuffer[File]]) { (m,f) =>
      FileUtils.fileContentsAsUTF8String(f) match {
        case Some(s) =>
          val tns = tnsExtractor.parse(s).getOrElse(NoNamespace)
          m.getOrElseUpdate(tns,ListBuffer.empty) += f
        case None => appendError( s"schema file not found: $f")
      }
      m
    }.map { e=>
      val (tns,files) = e
      val schemaSource = combineSchemas(files.toSeq,tns)
      val schema = schemaFactory.newSchema(schemaSource)
      val validator = schema.newValidator
      validator.setErrorHandler(xh)
      tns -> validator
    }
  }

  def combineSchemas (files: Seq[File], tns: String): Source = {
    if (files.length == 1) {
      new StreamSource(new FileInputStream(files(0)))
    } else {
      val sb = new StringBuilder
      sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
      sb.append("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" ")
      if (tns.nonEmpty) sb.append( s"""targetNamespace="$tns" """)
      sb.append('>')
      files foreach { f => sb.append(s"""<xs:include schemaLocation="${f.getAbsolutePath}" />""") }
      sb.append("</xs:schema>")
      new StreamSource(new StringReader(sb.toString))
    }
  }

  def error (msg: String) = lastError = Some(msg)

  def appendError (msg: String) = {
    lastError = lastError match {
      case Some(text) => Some( text + ',' + msg)
      case None => Some(msg)
    }
  }
}

// this should not be required anymore, and would have to be extended to support other resolver sources than classpath

class LSInputImpl (@BeanProperty var characterStream: Reader,
                   @BeanProperty var byteStream: InputStream,
                   @BeanProperty var stringData: String,
                   @BeanProperty var systemId: String,
                   @BeanProperty var publicId: String,
                   @BeanProperty var baseURI: String,
                   @BeanProperty var encoding: String,
                   @BeanProperty var certifiedText: Boolean
                  ) extends LSInput

class ClasspathResourceResolver extends LSResourceResolver {
  override def resolveResource (typ: String, nsURI: String, publicId: String, systemId: String, baseURI: String): LSInput = {
    val is = getClass.getClassLoader.getResourceAsStream(systemId)
    val reader = new InputStreamReader(is)
    new LSInputImpl(reader,null,null,systemId,publicId,baseURI,null,true)
  }
}
