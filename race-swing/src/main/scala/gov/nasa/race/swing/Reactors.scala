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

import scala.language.reflectiveCalls
import java.awt.{Window=>AWTWindow}
import java.awt.event.{WindowEvent, WindowAdapter}
import java.beans.{PropertyChangeEvent, PropertyChangeListener}
import javax.swing.JComponent
import javax.swing.event.{AncestorEvent, AncestorListener}

/**
 * simplified reactor interface for scala-swing
 */
object Reactors {

  trait AncestorCallback
  case object AncestorAdded extends AncestorCallback
  case object AncestorMoved extends AncestorCallback
  case object AncestorRemoved extends AncestorCallback

  implicit class RichJComponent (val jThis: JComponent) {
    def setAncestorReactions (pf: PartialFunction[AncestorCallback,Any]) = {
      def ignore: PartialFunction[AncestorCallback,Any] = { case _ => }
      jThis.addAncestorListener (new AncestorListener {
        override def ancestorAdded (e: AncestorEvent) = pf.applyOrElse(AncestorAdded, ignore)
        override def ancestorMoved (e: AncestorEvent) = pf.applyOrElse(AncestorMoved, ignore)
        override def ancestorRemoved (e: AncestorEvent) = pf.applyOrElse(AncestorRemoved, ignore)
      })
    }
  }


  trait WindowCallback
  case object WindowClosing extends WindowCallback
  case object WindowIconified extends WindowCallback
  case object WindowDeiconified extends WindowCallback

  implicit class RichWindow (val wThis: AWTWindow) {
    def setWindowReactions (pf: PartialFunction[WindowCallback,Any]) = {
      def ignore: PartialFunction[WindowCallback,Any] = { case _ => }
      wThis.addWindowListener (new WindowAdapter {
        override def windowClosing (e: WindowEvent) = pf.applyOrElse(WindowClosing,ignore)
        override def windowIconified (e: WindowEvent) = pf.applyOrElse(WindowIconified, ignore)
        override def windowDeiconified (e: WindowEvent) = pf.applyOrElse(WindowDeiconified,ignore)
      })
    }
  }

  //--- bean listeners
  case class PropertyChanged (source:AnyRef, propertyName:String, oldValue:AnyRef, newValue: AnyRef)

  implicit class RichBean (val bean: {def addPropertyChangeListener(l:PropertyChangeListener): Unit}) {
    def setPropertyChangeReactions (pf: PartialFunction[PropertyChanged,Any]) = {
      def ignore: PartialFunction[PropertyChanged,Any] = { case _ => }
      bean.addPropertyChangeListener (new PropertyChangeListener {
        override def propertyChange (e:PropertyChangeEvent) = pf.applyOrElse(
          PropertyChanged(e.getSource, e.getPropertyName, e.getOldValue, e.getNewValue), ignore)
      })
    }
  }
}
