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

import java.io.File
import javax.swing.filechooser.FileFilter

import Style._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import scala.swing._
import scala.swing.event.{EditDone, SelectionChanged}

object FileSelectionPanel extends SimpleSwingApplication {
  val top = new MainFrame {
    title = "FSP Test"
    contents = new FileSelectionPanel("config:")( f =>
      println(s"@@ selected file: $f")
    ).styled()
  }
}

/**
  * a panel that allows selection of files either through a
  * text ComboBox or a button that triggers a FileChooser dialog
  */
class FileSelectionPanel(labelText: String, optDir: Option[String]=None, optExt: Option[String]=None)
                        (var action: (File)=>Any = f=>{}) extends GBPanel {
  var history = List.empty[String]
  var fileSelection: Option[File] = None
  val label = new Label(labelText).styled("labelFor")
  val textField = new TextField(20).styled("stringField")

  val dir = new File(if (optDir.isDefined) optDir.get else System.getProperty("user.dir"))
  val chooser = new FileChooser(dir)
  if (optExt.isDefined){
    chooser.fileFilter = new FileFilter {
      override def getDescription: String = "HOCON, JSON or Java properties file"
      override def accept(f: File): Boolean = f.getPath.endsWith(optExt.get)
    }
  }

  val openButton = new Button( Action("Open"){
    chooser.showOpenDialog(FileSelectionPanel.this) match {
      case FileChooser.Result.Approve =>
        val f = chooser.selectedFile
        textField.text = f.getPath
        action(f)
      case FileChooser.Result.Cancel => // ignore
    }
  }).styled()

  val c = new Constraints( gridy=0, fill=Fill.Horizontal, anchor=Anchor.West, ipadx=10, insets=(5,5,5,5))
  layout(label) = c.weightx(0)
  layout(textField) = c.weightx(1.0f)
  layout(openButton) = c.weightx(0)

  reactions += {
    case EditDone(`textField`) =>
      val path = textField.text
      val f = new File(path)
      if (f.isFile) {
        fileSelection = Some(f)
        history = path :: history
        action(f)
      } else {
        // report error
        println(s"@@ not a file: $path")
      }
  }

  def onSelect(a: (File)=>Any) = action = a
}
