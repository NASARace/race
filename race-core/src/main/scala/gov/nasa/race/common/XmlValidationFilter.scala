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

import java.io.{CharArrayReader, File, Reader, StringReader}
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.transform.Source
import javax.xml.transform.stax.StAXSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigurableFilter
import org.xml.sax.{ErrorHandler, SAXParseException}

/**
  * a filter that passes messages which are validated against a configured schema
  */
class XmlValidationFilter (val schemaSources: Array[Source], val config: Config) extends ConfigurableFilter {

  protected val inputFactory = XMLInputFactory.newInstance
  protected val schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
  protected val schema = schemaFactory.newSchema(schemaSources)
  protected val validator = schema.newValidator

  // TODO - the standard Stax validator always prints this annoying ERROR to System.err, and the
  // only workaround seems to be to set our own ErrorHandler that does not re-throw exceptions
  var lastError: Option[String] = None
  protected val xh = new ErrorHandler {
    def error (x: SAXParseException) = lastError = Some(x.getMessage)
    def fatalError (x: SAXParseException) = lastError = Some(x.getMessage)
    def warning  (x: SAXParseException) = {}
  }
  validator.setErrorHandler(xh)

  def this(schemaFile: File) = this(Array[Source](new StreamSource(schemaFile)),null)
  def this(schemaPath: String) = this(Array[Source](new StreamSource(new File(schemaPath))),null)
  def this(schemaPaths: Array[String]) = this(schemaPaths.map(p=>new StreamSource(new File(p))),null)
  def this(conf: Config) = this(Array[Source](new StreamSource(new File(conf.getString("schemas")))),conf)

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
