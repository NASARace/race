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

import Style._
import gov.nasa.race.common._
import java.awt.{GridBagConstraints,GridBagLayout}
import scala.swing.{Insets, Component, LayoutContainer, Panel}

object GBPanel {
  object Grid extends Enumeration {
    val Relative       = Value(GridBagConstraints.RELATIVE)
    val Remainder      = Value(GridBagConstraints.REMAINDER)
  }
  object Fill extends Enumeration {
    val None           = Value(GridBagConstraints.NONE)
    val Horizontal     = Value(GridBagConstraints.HORIZONTAL)
    val Vertical       = Value(GridBagConstraints.VERTICAL)
    val Both           = Value(GridBagConstraints.BOTH)
  }
  object Anchor extends Enumeration {
    val North          = Value(GridBagConstraints.NORTH)
    val NorthEast      = Value(GridBagConstraints.NORTHEAST)
    val East           = Value(GridBagConstraints.EAST)
    val SouthEast      = Value(GridBagConstraints.SOUTHEAST)
    val South          = Value(GridBagConstraints.SOUTH)
    val SouthWest      = Value(GridBagConstraints.SOUTHWEST)
    val West           = Value(GridBagConstraints.WEST)
    val NorthWest      = Value(GridBagConstraints.NORTHWEST)
    val Center         = Value(GridBagConstraints.CENTER)

    val PageStart      = Value(GridBagConstraints.PAGE_START)
    val PageEnd        = Value(GridBagConstraints.PAGE_END)
    val LineStart      = Value(GridBagConstraints.LINE_START)
    val LineEnd        = Value(GridBagConstraints.LINE_END)
    val FirstLineStart = Value(GridBagConstraints.FIRST_LINE_START)
    val FirstLineEnd   = Value(GridBagConstraints.FIRST_LINE_END)
    val LastLineStart  = Value(GridBagConstraints.LAST_LINE_START)
    val LastLineEnd    = Value(GridBagConstraints.LAST_LINE_END)
  }


}
import GBPanel._

/**
  * a drop in replacement for scala.swing.GridBagPanel with a more friendly constraints syntax
  * that supports a builder pattern so that we can reuse the same constraint object.
  * We also provide the standard default values when creating Constraints objects
  */
class GBPanel extends Panel with LayoutContainer {
  override lazy val peer = new javax.swing.JPanel(new GridBagLayout) with SuperMixin
  private def layoutManager = peer.getLayout.asInstanceOf[GridBagLayout]

  protected def constraintsFor (comp: Component) = new Constraints(layoutManager.getConstraints(comp.peer))

  protected def areValid (c: Constraints): (Boolean, String) = (true, "")
  protected def add (c: Component, l: Constraints) = peer.add(c.peer, l.peer)

  class Constraints (val peer: GridBagConstraints) extends Cloneable {
    def self: GridBagConstraints = peer

    def this (gridx: Int = Grid.Relative.id, gridy: Int = Grid.Relative.id,
              gridwidth: Int = 1, gridheight: Int = 1,
              weightx: Double = 0, weighty: Double = 0,
              anchor: Anchor.Value = Anchor.Center, fill: Fill.Value = Fill.None, insets: Insets = NoInsets,
              ipadx: Int = 0, ipady: Int = 0) =
      this( new GridBagConstraints( gridx, gridy, gridwidth, gridheight,
                                    weightx, weighty, anchor.id, fill.id, insets, ipadx, ipady))

    def this() = this(new GridBagConstraints())

    override def clone: Constraints =  new Constraints(peer.clone().asInstanceOf[GridBagConstraints])

    def apply (x: Int, y: Int): Constraints = grid(x,y)

    // explicit getters and setters
    def gridx: Int = peer.gridx
    def gridx (x:Int): Constraints = { peer.gridx = x; this }
    def gridx (x:Grid.Value): Constraints = { peer.gridx = x.id; this }
    def gridx_= (x: Int): Unit = { peer.gridx = x }
    def gridy: Int = peer.gridy
    def gridy (y: Int): Constraints = { peer.gridy = y; this }
    def gridy (y: Grid.Value): Constraints = { peer.gridy = y.id; this }
    def gridy_= (y: Int): Unit = { peer.gridy = y }
    def grid: (Int, Int) = (gridx, gridy)
    def grid (x: Int, y: Int): Constraints = { gridx = x; gridy = y; this }
    def grid (x: Grid.Value, y: Grid.Value): Constraints = { gridx = x.id; gridy = y.id; this }
    def grid_= (c: (Int, Int)): Unit = { gridx = c._1; gridy = c._2 }
    def gridwidth: Int = peer.gridwidth
    def gridwidth (w: Int): Constraints = { peer.gridwidth = w; this }
    def gridwidth (w: Grid.Value): Constraints = { peer.gridwidth = w.id; this }
    def gridwidth_= (w: Int): Unit = { peer.gridwidth = w }
    def gridwidth_= (w: Grid.Value): Unit = { peer.gridwidth = w.id }
    def gridheight: Int = peer.gridheight
    def gridheight (h: Int): Constraints = { peer.gridheight = h; this }
    def gridheight (h: Grid.Value): Constraints = { peer.gridheight = h.id; this }
    def gridheight_= (h: Int): Unit = { peer.gridheight = h }
    def gridheight_= (h: Grid.Value): Unit = { peer.gridheight = h.id }
    def weightx: Double = peer.weightx
    def weightx (x: Double): Constraints = { peer.weightx = x; this }
    def weightx_= (x: Double): Unit = { peer.weightx = x }
    def weighty: Double = peer.weighty
    def weighty (y: Double): Constraints = { peer.weighty = y; this }
    def weighty_= (y: Double): Unit = { peer.weighty = y }
    def anchor: Anchor.Value = Anchor(peer.anchor)
    def anchor (a: Anchor.Value): Constraints = { peer.anchor = a.id; this }
    def anchor_= (a: Anchor.Value): Unit = { peer.anchor = a.id }
    def fill: Fill.Value = Fill(peer.fill)
    def fill (f: Fill.Value): Constraints = { peer.fill = f.id; this }
    def fill_= (f: Fill.Value): Unit = { peer.fill = f.id }
    def insets: Insets = peer.insets
    def insets (i: Insets): Constraints = { peer.insets = i; this }
    def insets_= (i: Insets): Unit = { peer.insets = i }
    def ipadx: Int = peer.ipadx
    def ipadx (x: Int): Constraints = { peer.ipadx = x; this }
    def ipadx_= (x: Int): Unit = { peer.ipadx = x }
    def ipady: Int = peer.ipady
    def ipady (y: Int): Constraints = { peer.ipady = y; this }
    def ipady_= (y: Int): Unit = { peer.ipady = y }
  }
}
