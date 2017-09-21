package services

import java.util.Locale
import javax.inject.{Inject, Singleton}

import actions.RequestWithAgent
import akka.actor.ActorSystem
import controllers.routes
import models._
import play.api.Logger
import play.api.libs.mailer.MailerClient
import models.Email
import org.joda.time.DateTime
import play.api.mvc.AnyContent
import utils.UUID

@Singleton
class NotificationsService @Inject()(system: ActorSystem,
                                     configuration: play.api.Configuration,
                                     mailerClient: MailerClient,
                                     emailTemplateService: EmailTemplateService,
                                     agentService: AgentService,
                                     settingService: SettingService,
                                     emailSentService: EmailSentService) {


    private val host = configuration.underlying.getString("app.host")
    private val https = configuration.underlying.getString("app.https") == "true"

    private def sendMail(email: Email) {
      val playEmail = play.api.libs.mailer.Email(
       email.subject,
       email.sentFrom,
       email.sentTo,
       bodyText = Some(email.bodyText),
       replyTo = email.replyTo
      )
      mailerClient.send(playEmail)
      Logger.info(s"Email sent to ${email.sentTo.mkString(", ")}")
      emailSentService.insert(email)
    }

    def newApplication(application: Application): Boolean = {
      emailTemplateService.get(application.city, "RECEPTION_EMAIL").fold {
        Logger.error(s"No RECEPTION_EMAIL email template for city ${application.city}")
        return false
      } { emailTemplate =>
        val applicantEmail = generateApplicantEmail(emailTemplate)(application)
        val agents = agentService.all(application.city).filter(_.instructor)
        val instructorEmails = agents.map(generateInstructorEmail(applicantEmail, application))

        sendMail(applicantEmail)

        instructorEmails.foreach(sendMail)
        return true
      }
    }

    private def generateInstructorEmail(emailApplicant: Email, application: models.Application)(agent: Agent): Email = {
       val url = s"${routes.ApplicationController.show(application.id).absoluteURL(https, host)}?city=${application.city}&key=${agent.key}"

       val body = s"""
          |Bonjour ${agent.name},
          |
          |Une nouvelle demande de végétalisation est disponible dans l'administration.
          |Vous recevez ce mail car vous êtes un des agents instructeurs.
          |Vous pouvez voir la demande en ouvrant la page suivante :
          |$url
          |
          |Voici le message reçu par le demandeur:
          |========
          |Sujet: ${emailApplicant.subject}
          |De: ${emailApplicant.sentFrom}
          |À: ${emailApplicant.sentTo.mkString(", ")}
          |
          |> ${emailApplicant.bodyText.replaceAll("\n", "\n> ")}
          |========
          |
          |Plante Et Moi""".stripMargin

      Email(
        UUID.randomUUID,
        application.id,
        Some(agent.id),
        application.city,
        DateTime.now(),
        "NEW_APPLICATION_INSTRUCTOR",
        s"Nouvelle demande de végétalisation: ${application._type}, ${application.address}",
        "Plante Et moi <administration@plante-et-moi.fr>",
        Array(s"${agent.name} <${agent.email}>"),
        bodyText = body
      )
    }

    private def generateApplicantEmail(emailTemplate: EmailTemplate)(application: models.Application): Email = {
      val applicationString =
        s"""- Date de la demande:
           |${application.creationDate.toString("dd MMM YYYY", new Locale("fr"))}
           |
           |- Nom:
           |${application.applicantLastname}
           |
           |- Prénom:
           |${application.applicantFirstname}
           |
           |- Email:
           |${application.applicantEmail}
           |
           |- Type:
           |${application._type}
           |
           |- Address du projet:
           |${application.address}
           |
           |- Numéro de téléphone:
           |${application.applicantPhone.getOrElse("pas de numéro de téléphone")}
           |
           ${application.fields.map{ case (key, value) => s"|- $key:\n $value\n" }.mkString}
           |- Nombre de fichiers joint à la demande: ${application.files.length}
         """.stripMargin
      val body = emailTemplate.body
        .replaceAll("<application.id>", application.id)
        .replaceAll("<application>", applicationString)

      Email(
        UUID.randomUUID,
        application.id,
        None,
        application.city,
        DateTime.now(),
        "NEW_APPLICATION_APPLICANT",
        emailTemplate.title,
        emailTemplate.from,
        Array(s"${application.applicantName} <${application.applicantEmail}>"),
        bodyText = body,
        replyTo = emailTemplate.replyTo
      )
    }

    def applicationUpdated(application: Application, what: String, messageType: String, who: Agent): Boolean = {
      val agents = agentService.all(application.city).filter(_.instructor)
      val instructorEmails = agents.filter(_.id != who.id).map(generateUpdateEmailToAgent(application, who, what, messageType))

      instructorEmails.foreach(sendMail)
      return true
    }

    private def generateUpdateEmailToAgent(application: models.Application, who: Agent, what: String, messageType: String)(agent: Agent): Email = {
      val url = s"${routes.ApplicationController.show(application.id).absoluteURL(https, host)}?city=${application.city}&key=${agent.key}"

      val body = s"""Bonjour ${agent.name},
                    |
                    |${who.name} a $what pour la demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                    |Vous pouvez voir la demande ici :
                    |${url}
                    |
                    |""".stripMargin
      Email(
        UUID.randomUUID,
        application.id,
        Some(agent.id),
        application.city,
        DateTime.now(),
        messageType,
        s"${who.name} a $what (Plante Et Moi)",
        "Plante et Moi <administration@plante-et-moi.fr>",
        Array(s"${agent.name} <${agent.email}>"),
        bodyText = body
      )
    }
}
