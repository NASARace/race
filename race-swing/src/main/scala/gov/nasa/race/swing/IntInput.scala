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

package gov.nasa.race.swing

import java.awt.Toolkit
import javax.swing.text.{AbstractDocument, AttributeSet, DocumentFilter}

import scala.swing.TextField

class DigitFilter extends DocumentFilter {
  override def insertString (fp: DocumentFilter.FilterBypass, off: Int, s: String, attrs: AttributeSet): Unit = {
    for (i <- 0 until s.length) {
      if (!Character.isDigit(s.charAt(i))) {
        Toolkit.getDefaultToolkit().beep
        return
      }
    }
    super.insertString(fp, off, s, attrs)
  }

  override def replace (fp: DocumentFilter.FilterBypass, off: Int, len: Int, s: String, attrs: AttributeSet): Unit = {
    for (i <- 0 until s.length) {
      if (!Character.isDigit(s.charAt(i))) {
        Toolkit.getDefaultToolkit().beep
        return
      }
    }
    super.replace(fp, off, len, s, attrs)
  }
}

/**
  * a TextField for Int values
  */
class IntInput (cols: Int = 10) extends TextField(cols) {
  peer.getDocument.asInstanceOf[AbstractDocument].setDocumentFilter(new DigitFilter)

  def value_= (newValue: Int) = text = newValue.toString
  def value: Int = text.toInt
}
