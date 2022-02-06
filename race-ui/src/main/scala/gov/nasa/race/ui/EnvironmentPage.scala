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

package gov.nasa.race.ui

import java.io.File

import com.jcraft.jsch.{JSch, Session, UserInfo}
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.swing.GBPanel.{Anchor, Fill}
import gov.nasa.race.swing.Style._
import gov.nasa.race.swing.{CollapsiblePanel, GBPanel, _}
import gov.nasa.race.util.CryptUtils._
import gov.nasa.race.util.NetUtils._

import scala.swing._
import scala.swing.event.ButtonClicked
import scala.util.{Failure, Success, Try}


/**
  * RaceConsole page with runtime environment settings
  */
class EnvironmentPage (raceConsole: GUIMain, pageConfig: Config) extends CollapsiblePanel {

  class PortmapEntry (var gateway: String, var forwards: String) {
    var session: Session = _
  }

  class GwSwingUserInfo(val pw: PasswordField) extends SwingUserInfoAdapter {
    override def toplevel = raceConsole
    override def promptPassword (prompt: String): Boolean = {
      pw.password != null && pw.password.length > 0
    }
    override def getPassword = {
      if (pw.password != null && pw.password.length > 0) {
        val s = pw.password
        pw.peer.setText(null)
        new String(s)
      } else null
    }
  }

  final val forwardRE = """(\d+)\:(.+?)\:(\d+)""".r  // captures non-optional port,hostname,port

  //--- port mapping via ssh
  val jsch = new JSch
  var portmapEntries = pageConfig.getOptionalConfigList("portmap").map(createPortmapEntry)
  var sshTimeout = pageConfig.getIntOrElse("ssh_timeout", 5000)
  val portmapPanel = createPortmapPanel

  //--- config vault
  val cryptPanel = createCryptPanel
  private var cvFile: Option[File] = None

  val networkPanel = createNetworkPanel
  val sysPropPanel = createSysPropPanel

  add("system properties", sysPropPanel, "user defined system properties")
  add("port mapping", portmapPanel,"list of port mapping gateways")
  add("network", networkPanel, "RACE network settings")
  add("encrypted config", cryptPanel, "encrypted RACE configuration")

  //--- general utilities
  def alert (msg: String): Boolean = { raceConsole.alertMessage(msg); false }
  def info (msg: String) = raceConsole.infoMessage(msg)
  def fail (msg: String) = throw new RuntimeException(msg)

  //--- port mapping support
  def createPortmapPanel = new GBPanel {
    val c = new Constraints(gridy = 0, fill = Fill.Horizontal, anchor = Anchor.West, ipadx=10, insets = (2,2,2,2))
    portmapEntries.foreach(addRow)

    def addRow (pme: PortmapEntry) = {
      val gw = new TextField(pme.gateway).styled()
      val fwd = new TextField(pme.forwards).styled()
      val pw = new PasswordField(10).styled()
      val activate = new CheckBox("active").styled()
      val onOff = new OnOffIndicator(false)

      layout(new Label(" gateway:").styled('labelFor)) = c.weightx(0)
      layout(gw) = c.weightx(0.5f)
      layout(new Label(" forwards:").styled('labelFor)) = c.weightx(0)
      layout(fwd) = c.weightx(0.5f)
      layout(new Label(" pw:").styled('labelFor)) = c.weightx(0)
      layout(pw) = c
      layout(activate) = c
      layout(onOff) = c
      c.gridy(c.gridy+1)

      listenTo(activate)
      reactions += {
        case ButtonClicked(`activate`) =>
          if (activate.selected) {
            Try(startSession(gw.text,fwd.text,new GwSwingUserInfo(pw))) match {
              case Success(session) =>
                pme.session = session
                info(s"ssh portmap session started on host: ${session.getHost}")
                onOff.on
              case Failure(e) =>
                alert(e.getMessage)
                activate.selected = false
            }
          } else {
            Try(stopSession(gw.text, pme.session)) match {
              case Success(session) =>
                info(s"session on gateway ${session.getHost} closed")
                pme.session = null
                onOff.off
              case Failure(e) => alert(e.toString)
            }
          }
      }
    }
  } styled()

  def createPortmapEntry (peConf: Config) = {
    new PortmapEntry(peConf.getString("gateway"),peConf.getString("forward"))
  }

  def parseForwardSpec (forwardSpec: String): Array[(Int,String,Int)] = {
    forwardSpec.split("[ ,;]+").map( s => s match {
      case forwardRE(lport,rhost,rport) => (lport.toInt,rhost,rport.toInt)
      case _ => fail(s"invalid forward spec: $s")
    })
  }

  def startSession (gwSpec: String, forwardSpec: String, ui: UserInfo): Session = {
    if (gwSpec == null) fail("please enter gateway spec: [user@]host[:port]")
    if (forwardSpec == null || forwardSpec.isEmpty) fail("please enter forward spec: lport:rhost:rport")
    if (!ui.promptPassword("")) fail("please enter password for gateway")

    gwSpec match {
      case UrlRE(_,usr,gwHost,gPort,_,_) =>
        val user = nonNullOrElse(usr, System.getProperty("user.name"))
        val gwPort = toIntOrElse(gPort, 22)
        val session = jsch.getSession(user,gwHost)
        session.setPassword(ui.getPassword)
        session.setUserInfo(ui)
        val portForwards = parseForwardSpec(forwardSpec) // parse *before* we try to connect
        session.connect(sshTimeout)
        portForwards.foreach { e => session.setPortForwardingL(e._1, e._2, e._3) }
        session

      case _ => fail("invalid gateway spec")
    }
  }

  def stopSession (gwSpec: String, session: Session) = {
    if (session != null) {
      if (session.isConnected) {
        session.disconnect()
        session
      } else fail(s"not connected to gateway: ${session.getHost}")
    } else fail(s"no active session for gateway: $gwSpec")
  }

  //--- encrypted config panel
  def createCryptPanel = {
    val expandCb = new CheckBox("expand").styled()
    val ppEntry = new PasswordField(20).styled()
    val loadCb = new CheckBox("load").styled()

    val configSelection = new FileSelectionPanel("config:",
               pageConfig.getOptionalString("crypt-dir"), Some(".crypt"))(f =>
      if (f.isFile) {
        loadCb.enabled = true
        cvFile = Some(f)
      }
    ).styled()

    val header = new GBPanel() {
      val c = new Constraints(gridy = 0, fill = Fill.Horizontal, anchor = Anchor.West, insets = (2, 2, 2, 2))
      layout(configSelection) = c.weightx(0.8)
      layout(expandCb) = c.weightx(0)
      layout(new Label(" passphrase:").styled('labelFor)) = c
      layout(ppEntry) = c.weightx(0.2)
      layout(loadCb) = c.weightx(0)
    } styled()

    val editorPanel = new RSTextPanel {
      editor.syntaxEditingStyle = "text/c"
      editor.codeFoldingEnabled = true
      preferredSize = (400, 100)
    } styled()

    listenTo(loadCb)
    reactions += {
      case ButtonClicked(`loadCb`) =>
        def abortSelection (msg: String) = { alert(msg); loadCb.selected = false}
        if (loadCb.selected) {
          val cs = ppEntry.password
          if (cs == null || cs.length == 0) {
            abortSelection("please enter passphrase for config vault")
          } else {
            cvFile match {
              case Some(file) =>
                Try(if(expandCb.selected) decryptConfig(file,cs) else decryptFile(file,cs)) match {
                  case Success(Some(t)) =>
                    editorPanel.text = t
                  case Success(None) => abortSelection("nothing to decrypt")
                  case Failure(e) => abortSelection("error decrypting file, check pass phrase")
                }
              case None => abortSelection("please select encrypted config file")
            }
          }
        }
    }

    new BorderPanel() {
      layout(header) = BorderPanel.Position.North
      layout(editorPanel) = BorderPanel.Position.Center
    } styled()
  }

  //--- system properties panel
  def createSysPropPanel =  new RSTextPanel {
    editor.syntaxEditingStyle = "text/properties"
    editor.codeFoldingEnabled = true
    preferredSize = (400, 100)
  } styled()

  //--- host/ifc panel
  def createNetworkPanel = new BorderPanel().styled()

}
