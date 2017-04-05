package services

import java.util.Locale
import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import controllers.routes
import models._
import play.api.Logger
import play.api.libs.mailer.{Email, MailerClient}

@Singleton
class NotificationsService @Inject()(system: ActorSystem, configuration: play.api.Configuration, mailerClient: MailerClient, emailTemplateService: EmailTemplateService, agentService: AgentService, settingService: SettingService) {

    def newApplication(application: Application): Boolean = {
      emailTemplateService.get(application.city, "RECEPTION_EMAIL").fold {
        Logger.error(s"No RECEPTION_EMAIL email template for city ${application.city}")
        return false
      } { emailTemplate =>
        val applicantEmail = generateApplicantEmail(emailTemplate)(application)
        val agents = agentService.all(application.city).filter(_.instructor)
        val instructorEmails = agents.map(generateInstructorEmail(applicantEmail, application))

        Logger.info(s"Send mail to Applicant ${application.applicantEmail}")
        mailerClient.send(applicantEmail)

        instructorEmails.foreach { instructorEmail =>
          Logger.info(s"Send mail to Instructor ${instructorEmail.to}")
          mailerClient.send(instructorEmail)
        }
        return true
      }
    }

    private def generateInstructorEmail(emailApplicant: Email, application: models.Application)(agent: Agent): Email = {
       val host = settingService.findByKey(application.city)("HOST").get._2.as[String]
       val https = settingService.findByKey(application.city)("HTTPS").get._2.as[Boolean]
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
          |De: ${emailApplicant.from}
          |À: ${emailApplicant.to.mkString(", ")}
          |
          |> ${emailApplicant.bodyText.get.replaceAll("\n", "\n> ")}
          |========
          |
          |Plante Et Moi""".stripMargin

       Email(s"Nouvelle demande de végétalisation: ${application._type}, ${application.address}",
         "Plante Et moi <administration@plante-et-moi.fr>",
         List(s"${agent.name} <${agent.email}>"),
         bodyText = Some(body)
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
        emailTemplate.title,
        emailTemplate.from,
        Seq(s"${application.applicantName} <${application.applicantEmail}>"),
        bodyText = Some(body),
        replyTo = emailTemplate.replyTo
      )
    }
}
