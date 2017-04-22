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
import javax.xml.validation.SchemaFactory

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigurableFilter
import gov.nasa.race.util.{FileUtils, StringUtils}
import org.xml.sax.{ErrorHandler, SAXParseException}

/**
  * the companion mostly provides builders for different argument types.
  *
  * Note that while SchemaFactory has a newSchema(Array[Source]) builder, it apparently does not work if the aggregated
  * schemas are for the same namespace (there was an old Xerces bug report about using the namespace as a hash). To
  * avoid this problem we synthesize a wrapper schema and instantiate the validatior with it.
  * To make things more complicated, if the aggregated schemas have targetNamespace attributes, those have to be
  * the same, which also applies to the wrapper. Violations are reported as errors by the SchemaFactory
  */
object XmlValidationFilter {
  def apply (file: File) = new XmlValidationFilter( new StreamSource(file))
  def apply (src: String) = new XmlValidationFilter( new StreamSource(new StringReader(src)))
  def apply (files: Seq[File]) = new XmlValidationFilter( combineSchemas(files))

  def combineSchemas (files: Seq[File]): Source = {
    val sb = new StringBuilder
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
    sb.append("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" ")
    ifSome(getTargetNamespace(files.head)) { sb.append }
    sb.append('>')
    files foreach { f=>
      sb.append("<xs:include schemaLocation=\"")
      sb.append(f.getAbsolutePath)
      sb.append("\"/>")
    }
    sb.append("</xs:schema>")
    new StreamSource(new StringReader(sb.toString))
  }

  def getTargetNamespace (file: File): Option[String] = {
    for (
      schemaText <- FileUtils.fileContentsAsUTF8String(file);
      tns <- "targetNamespace=\".+\"".r.findFirstIn(schemaText)
    ) yield tns
  }
}

/**
  * a filter that passes messages which are validated against a configured schema
  */
class XmlValidationFilter (val schemaSource: Source, val config: Config= null) extends ConfigurableFilter {

  var lastError: Option[String] = None

  protected val xh = new ErrorHandler {
    def error (x: SAXParseException) = lastError = Some(x.getMessage)
    def fatalError (x: SAXParseException) = lastError = Some(x.getMessage)
    def warning  (x: SAXParseException) = {}
  }

  protected val inputFactory = XMLInputFactory.newInstance
  protected val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
  schemaFactory.setErrorHandler(xh) // set this before we create the schema, to catch schema errors

  protected val schema = schemaFactory.newSchema(schemaSource)
  protected val validator = schema.newValidator
  // Note - the standard Stax validator always prints this annoying ERROR to System.err, and the
  // only workaround seems to be to set our own ErrorHandler that does not re-throw exceptions
  validator.setErrorHandler(xh)


  def pass(o: Any): Boolean = {
    o match {
      case txt: String => validate(new StringReader(txt))
      case Some(txt:String) => validate(new StringReader(txt))
      case cs: Array[Char] => validate(new CharArrayReader(cs))
      case Some(cs:Array[Char]) => validate(new CharArrayReader(cs))
      case None => false
      case _ =>
        lastError = Some(s"unsupported message type: ${o.getClass}")
        false
    }
  }

  def validate (dataReader: Reader): Boolean = {
    try {
      lastError = None
      validator.validate(new StAXSource(inputFactory.createXMLStreamReader(dataReader)))
      lastError.isEmpty
    } catch {
      // we shouldn't get any with our error handler
      case t: Throwable =>
        lastError = Some(t.getMessage)
        false
    }
  }
}
