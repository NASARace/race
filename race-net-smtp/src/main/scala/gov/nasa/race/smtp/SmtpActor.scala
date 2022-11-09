/*
 * Copyright (c) 2022, United States Government, as represented by the
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
package gov.nasa.race.smtp

import gov.nasa.race.config.ConfigUtils.ConfigWrapper
import gov.nasa.race.core.RaceActor
import gov.nasa.race.yieldInitialized
import jakarta.mail.internet.{InternetAddress, MimeBodyPart, MimeMessage, MimeMultipart}
import jakarta.mail.{Address, Authenticator, Message, Part, PasswordAuthentication, Transport, Session => SmtpSession}

import java.io.File
import java.util.Properties

/**
 * RaceActor mixin for SMTP access
 * TODO - needs refinement
 */
trait SmtpActor extends RaceActor {

  protected val smtpSession: SmtpSession = createSmtpSession()

  protected def createSmtpSession(): SmtpSession = {
    val props = new Properties()
    props.put("mail.smtp.auth",  "true")
    props.put("mail.smtp.starttls.enable", "true")
    props.put("mail.smtp.host", config.getVaultableString("smtp.host"))
    props.put("mail.smtp.port", config.getVaultableInt("smtp.port"))

    val auth = new Authenticator() {
      override def getPasswordAuthentication(): PasswordAuthentication = {
        new PasswordAuthentication(
          config.getVaultableString("smtp.username"),
          config.getVaultableString("smtp.password")
        );
      }
    }

    SmtpSession.getInstance(props, auth)
  }

  protected def sendEmail( fromAddr: String, toAddrs: Seq[String], ccAddrs: Seq[String], bccAddrs: Seq[String],
                           subject: String, text: String): Unit = {
    try {
      val msg = new MimeMessage(smtpSession)

      msg.setFrom( new InternetAddress(fromAddr))

      if (toAddrs.nonEmpty) msg.setRecipients(Message.RecipientType.TO, toAddrs.map( new InternetAddress(_)).toArray[Address])
      if (ccAddrs.nonEmpty) msg.setRecipients(Message.RecipientType.CC, ccAddrs.map( new InternetAddress(_)).toArray[Address])
      if (bccAddrs.nonEmpty) msg.setRecipients(Message.RecipientType.BCC, bccAddrs.map( new InternetAddress(_)).toArray[Address])

      msg.setSubject(subject)
      msg.setText(text)

      Transport.send(msg)
    } catch {
      case x: Throwable => warning(s"failed to send email: $x")
    }
  }

  protected def sendEmail (fromAddr: String, toAddrs: Seq[String], ccAddrs: Seq[String], bccAddrs: Seq[String],
                           subject: String, parts: Seq[MimeBodyPart]): Unit = {
    try {
      val msg = new MimeMessage(smtpSession)

      msg.setFrom( new InternetAddress(fromAddr))

      if (toAddrs.nonEmpty) msg.setRecipients(Message.RecipientType.TO, toAddrs.map( new InternetAddress(_)).toArray[Address])
      if (ccAddrs.nonEmpty) msg.setRecipients(Message.RecipientType.CC, ccAddrs.map( new InternetAddress(_)).toArray[Address])
      if (bccAddrs.nonEmpty) msg.setRecipients(Message.RecipientType.BCC, bccAddrs.map( new InternetAddress(_)).toArray[Address])

      msg.setSubject(subject)

      val mp = new MimeMultipart()
      parts.foreach(mp.addBodyPart)
      msg.setContent(mp)

      Transport.send(msg)
    } catch {
      case x: Throwable => warning(s"failed to send email: $x")
    }
  }

  protected def textPart (text: String): MimeBodyPart = {
    yieldInitialized (new MimeBodyPart()) { mbp=>
      mbp.setContent(text, "text/plain")
    }
  }

  protected def fileAttachmentPart (file: File, disposition: String = Part.ATTACHMENT): MimeBodyPart = {
    yieldInitialized( new MimeBodyPart()) { mbp=>
      mbp.attachFile(file)
      mbp.setDisposition(disposition)
    }
  }

  protected def inlinedFileAttachmentPart (file: File): MimeBodyPart = fileAttachmentPart(file, Part.INLINE)
}
