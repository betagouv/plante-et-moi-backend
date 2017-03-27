package controllers

import javax.inject._

import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import models._
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import play.api.libs.mailer.MailerClient
import actions.LoginAction

import scala.concurrent.Future
import play.api.libs.mailer._
import services.{ApplicationService, TypeformService}

@Singleton
class ApplicationController @Inject() (ws: WSClient,
                                       configuration: play.api.Configuration,
                                       reviewService: ReviewService,
                                       mailerClient: MailerClient,
                                       agentService: AgentService,
                                       loginAction: LoginAction,
                                       applicationService: ApplicationService,
                                       typeformService: TypeformService) extends Controller {

  def projects(city: String) = applicationService.findByCity(city).map { application =>
      (application, reviewService.findByApplicationId(application.id))
    }

  def getImage(url: String) = loginAction.async { implicit request =>
    var request = ws.url(url.replaceFirst(":443", ""))
    if(url.contains("api.typeform.com")) {
      request = request.withQueryString("key" -> typeformService.key)
    }
    request.get().map { fileResult =>
      if(fileResult.status < 300) {
        NotFound("")
      } else {
        val contentType = fileResult.header("Content-Type").getOrElse("text/plain")
        val filename = url.split('/').last
        Ok(fileResult.bodyAsBytes).withHeaders("Content-Disposition" -> s"attachment; filename=$filename").as(contentType)
      }
    }
  }

  def all = loginAction { implicit request =>
    val responses = projects(request.currentCity)
    val numberOrReviewNeeded = agentService.all(request.currentCity).count(_.canReview)
    Ok(views.html.allApplications(responses, request.currentAgent, numberOrReviewNeeded))
  }

  def map = loginAction { implicit request =>
    val responses = projects(request.currentCity)
    Ok(views.html.mapApplications(request.currentCity, responses, request.currentAgent))
  }

  def my = loginAction { implicit request =>
    val agent = request.currentAgent
    val responses = projects(request.currentCity)
    val afterFilter = responses.filter { response =>
      response._1.status == "En cours" &&
        !response._2.exists { _.agentId == agent.id }
    }
    Ok(views.html.myApplications(afterFilter, request.currentAgent))
  }

  def show(id: String) = loginAction { implicit request =>
    val agent = request.currentAgent
    applicationById(id, request.currentCity) match {
        case None =>
          NotFound("")
        case Some(application) =>
          val reviews = reviewService.findByApplicationId(id)
              .map { review =>
                review -> agentService.all(request.currentCity).find(_.id == review.agentId).get
              }
          Ok(views.html.application(application._1, agent, reviews))
    }
  }

  private def applicationById(id: String, city: String) =
    projects(city).find { _._1.id == id }


  def changeCity(newCity: String) = Action { implicit request =>
    Redirect(routes.ApplicationController.login()).withSession("city" -> newCity.toLowerCase)
  }

  def disconnectAgent() = Action { implicit request =>
    Redirect(routes.ApplicationController.login()).withSession(request.session - "agentId")
  }

  def login() = Action { implicit request =>
    request.session.get("city").map(_.toLowerCase()).fold {
      BadRequest("Pas de ville sélectionné")
    } { city =>
      Ok(views.html.login(agentService.all(city), city))
    }
  }

  case class ReviewData(favorable: Boolean, comment: String)
  val reviewForm = Form(
    mapping(
      "favorable" -> boolean,
      "comment" -> text
    )(ReviewData.apply)(ReviewData.unapply)
  )

  def addReview(applicationId: String) = loginAction.async { implicit request =>
    (reviewForm.bindFromRequest.value, applicationById(applicationId, request.currentCity)) match {
      case (Some(reviewData), Some((application, reviews))) =>
        val agent = request.currentAgent
        val review = Review(applicationId, agent.id, DateTime.now(), reviewData.favorable, reviewData.comment)
        Future(reviewService.insertOrUpdate(review)).map { _ =>
          if(agent.finalReview) {
            val status = review.favorable match {
              case true => "Favorable"
              case false => "Défavorable"
            }
            applicationService.updateStatus(applicationId, status)
            agentService.all(request.currentCity).filter { _.instructor }.foreach(sendCompletedApplicationEmailToAgent(application, request, agent))
          } else {
            val numberOrReviewNeededBeforeFinal = agentService.all(request.currentCity).count { agent => agent.canReview && !agent.finalReview }
            val bonus = reviews.exists(_.agentId == agent.id) match {
              case true => 0
              case false => 1
            }
            val numberOfReview = reviews.length + bonus
            if(numberOrReviewNeededBeforeFinal == numberOfReview) {
              agentService.all(request.currentCity).filter { agent => agent.finalReview }.foreach(sendNewApplicationEmailToAgent(application, request))
            }
          }
          Redirect(routes.ApplicationController.my()).flashing("success" -> "Votre avis a bien été pris en compte.")
        }
      case _ =>
        Future.successful(BadRequest(""))
    }
  }

  def updateStatus(id: String, status: String) = loginAction { implicit request =>
    applicationById(id, request.currentCity) match {
      case None =>
        NotFound("")
      case Some((application, _)) =>
        var message = "Le status de la demande a été mis à jour"
        if(status == "En cours" && application.status != "En cours") {
          agentService.all(request.currentCity).filter { agent => agent.canReview && !agent.finalReview }.foreach(sendNewApplicationEmailToAgent(application, request))
          message = "Le status de la demande a été mis à jour, un mail a été envoyé aux agents pour obtenir leurs avis."
        }
        applicationService.updateStatus(application.id, status)
        Redirect(routes.ApplicationController.all()).flashing("success" -> message)
    }
  }

  private def sendNewApplicationEmailToAgent(application: models.Application, request: RequestHeader)(agent: Agent) = {
    val url = s"${routes.ApplicationController.show(application.id).absoluteURL()(request)}?key=${agent.key}"
    val title = agent.finalReview match {
      case true => s"Demande d'avis final permis de végétalisation: ${application.address}"
      case false => s"Demande d'avis permis de végétalisation: ${application.address}"
    }
    val email = Email(
      title,
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${agent.name} <${agent.email}>"),
      bodyText = Some(s"""Bonjour ${agent.name},
                    |
                    |Nous avons besoin de votre avis pour une demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                    |Vous pouvez voir la demande et laisser mon avis en ouvrant la page suivante:
                    |${url}
                    |
                    |Merci de votre aide,
                    |Si vous avez des questions, n'hésitez pas à nous contacter en répondant à ce mail""".stripMargin)
    )
    mailerClient.send(email)
  }

  private def sendCompletedApplicationEmailToAgent(application: models.Application, request: RequestHeader, finalAgent: Agent)(agent: Agent) = {
    val url = s"${routes.ApplicationController.show(application.id).absoluteURL()(request)}?key=${agent.key}"
    val email = Email(
      s"Avis final donné demande de végétalisation: ${application.address}",
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${agent.name} <${agent.email}>"),
      bodyText = Some(s"""Bonjour ${agent.name},
                         |
                         |L'avis final a été donné par ${finalAgent.name} pour la demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                         |Vous pouvez voir la demande ici:
                         |${url}
                         |
                         |""".stripMargin)
    )
    mailerClient.send(email)
  }
}