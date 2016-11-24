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

import java.awt.event.{ActionEvent, ActionListener}
import javax.swing.Timer
import gov.nasa.race._
import gov.nasa.race.swing.Reactors._
import scala.concurrent.duration.FiniteDuration
import scala.swing.Component

/**
 * timer extension with callback
 */

class SwingTimer (val dur: FiniteDuration, val repeat: Boolean=true)
                                   extends Timer(dur.toMillis.toInt, null) {
  setRepeats(repeat)

  def bindTo (c: Component) = {
    val peer = c.peer
    peer.setAncestorReactions {
      case Reactors.AncestorAdded =>
        start
        ifNotNull(peer.topLevel) { toplevel =>
          toplevel.setWindowReactions {
            case Reactors.WindowClosing => stop()
            case Reactors.WindowIconified => stop()
            case Reactors.WindowDeiconified => restart()
          }
        }
      case Reactors.AncestorRemoved => stop()
    }
  }

  def whenExpired (action: =>Any) = {
    addActionListener( new ActionListener {
      override def actionPerformed (e: ActionEvent): Unit = {
        action
      }
    })
  }
}
