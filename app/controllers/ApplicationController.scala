package controllers

import java.nio.file.Files
import java.util.Locale

import javax.inject._
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import models.{Email, _}
import org.joda.time.{DateTime, DateTimeZone}
import play.api.data._
import play.api.data.Forms._
import play.api.libs.mailer.MailerClient
import actions.{LoginAction, RequestWithAgent}
import formats.FormRequireBoolean
import models.Forms.ApplicationEdit
import org.webjars.play.WebJarsUtil
import com.github.tototoshi.csv._

import scala.concurrent.Future
import play.api.libs.mailer._
import services._
import utils.Charset
import utils.{Hash, UUID}

import scala.io.Source
import scala.util.Try

@Singleton
class ApplicationController @Inject() (ws: WSClient,
                                       configuration: play.api.Configuration,
                                       reviewService: ReviewService,
                                       mailerClient: MailerClient,
                                       agentService: AgentService,
                                       loginAction: LoginAction,
                                       applicationService: ApplicationService,
                                       commentService: CommentService,
                                       fileService: FileService,
                                       typeformService: TypeformService,
                                       notificationsService: NotificationsService,
                                       emailTemplateService: EmailTemplateService,
                                       emailSentService: EmailSentService)(implicit val webJarsUtil: WebJarsUtil) extends InjectedController with play.api.i18n.I18nSupport {

  private val timeZone = DateTimeZone.forID("Europe/Paris")

  def projects(city: String, orderedDescending: Boolean = true) = applicationService.findByCity(city, orderedDescending).map { application =>
      (application, reviewService.findByApplicationId(application.id))
    }

  def getImage(url: String) = loginAction.async { implicit request =>
    if(url.contains("typeform"))
      getImageFromTypeform(url)
    else if(url.startsWith("internal://"))
      Future(getImageInternal(url))
    else
      Future(NotFound("Fichier inconnu"))
  }

  private def getImageInternal(url: String): Result = {
    var id = url.split('/').lift(2)
    id.flatMap(fileService.findById) match {
      case Some(file) if !file.data.isEmpty =>
        val contentType = file._type.getOrElse("text/plain")
        val filename = file.name
        Ok(file.data.get).withHeaders("Content-Disposition" -> s"attachment; filename=$filename").as(contentType)
      case _ =>
        NotFound("Fichier inconnu en interne. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
    }
  }


  private lazy val filesCookie = configuration.underlying.getString("typeform.cookieForFiles")
  private def getImageFromTypeform(url: String): Future[Result] = {
    var request = ws.url(url.replaceFirst(":443", ""))
    if(url.contains("typeform.com")) {
      request = request.withCookies(DefaultWSCookie("PHPSESSID", filesCookie))
    }
    request.get().map { fileResult =>
      if(fileResult.status >= 300) {
        NotFound("")
      } else {
        val contentType = fileResult.header("Content-Type").getOrElse("text/plain")
        val filename = url.replace("/download", "").split('/').last
        Ok(fileResult.bodyAsBytes).withHeaders("Content-Disposition" -> s"""attachment; filename="$filename"""").as(contentType)
      }
    }
  }

  def all = loginAction { implicit request =>
    val cityApplications = projects(request.currentCity)
    val agents = agentService.all(request.currentCity)

    Ok(views.html.allApplications(cityApplications, request.currentAgent, agents))
  }

  def allCSV = loginAction { implicit request =>
    val year = request.getQueryString("year").flatMap(yearString => scala.util.control.Exception.allCatch.opt(yearString.toInt))
    val responses = applicationService.findByCity(request.currentCity)
    val filteredResponses = year match {
      case Some(year) =>
        responses.filter(_.creationDate.getYear == year)
      case _ =>
        responses
    }
    val agents = agentService.all(request.currentCity)
    val date = DateTime.now(timeZone).toString("dd-MMM-YYY-HHhmm", new Locale("fr"))
    Ok(views.html.allApplicationsCSV(filteredResponses,agents)).as("text/csv").withHeaders("Content-Disposition" -> s"""attachment; filename="${request.currentCity}-${date}.csv"""" )
  }

  def map = loginAction { implicit request =>
    val responses = projects(request.currentCity)
    Ok(views.html.mapApplications(request.currentCity, responses, request.currentAgent))
  }

  def my = loginAction { implicit request =>
    val agent = request.currentAgent
    val order = request.getQueryString("order").map(_.toString).orElse(request.session.get("order")).getOrElse("DESC")
    val responses = projects(request.currentCity, order == "DESC")
    val agents = agentService.all(request.currentCity)
    val applicationsToReview = responses.filter { response =>
      response._1.status == "En cours" &&
        !response._2.exists { _.agentId == agent.id } &&
        response._1.reviewerAgentIds.contains(agent.id)
    }                                                                
    val newApplications = responses.filter { response =>
      response._1.status == "Nouvelle" && agent.instructor
    }
    val applicationsWithDecisionToTake = responses.filter { response =>
      response._1.status == "En cours" && response._2.length >= response._1.numberOfReviewNeeded(agents) && agent.finalReview
    }
    val applicationsWithDecisionToSend = responses.filter(_._1.decisionToSend)
    Ok(views.html.myApplications(
      applicationsToReview,
      newApplications,
      applicationsWithDecisionToTake,
      applicationsWithDecisionToSend,
      responses,
      order,
      request.currentAgent)).withSession(request.session  + ("order" -> order))
  }

  val applicationEditForm = Form(
    mapping(
      "applicantEmail" -> optional(email),
      "applicantFirstname" -> optional(text),
      "applicantLastname" -> optional(text),
      "applicantAddress" -> optional(text),
      "applicantPhone" -> optional(text)
    )(ApplicationEdit.apply)(ApplicationEdit.unapply)
  )

  def show(id: String) = loginAction { implicit request =>
    val agent = request.currentAgent
    applicationById(id, request.currentCity) match {
        case None =>
          NotFound("")
        case Some(application) =>
          val agents = agentService.all(request.currentCity)
          val reviews = reviewService.findByApplicationId(id)
              .map { review =>
                review -> agents.find(_.id == review.agentId).get
              }
          val comments = commentService.findByApplicationId(id)
                .map { comment =>
                  comment -> agents.find(_.id == comment.agentId).get
                }
          val emailTemplate = (application._1.status match {
            case "Favorable" => Some("FAVORABLE_EMAIL")
            case "Défavorable" => Some("UNFAVORABLE_EMAIL")
            case _ => None
          }).flatMap(emailTemplateService.get(application._1.city))

          val emails = emailSentService.findByApplicationId(application._1.id)
          val form = applicationEditForm.fill(ApplicationEdit.fromApplication(application._1))

          Ok(views.html.application(application._1, agent, reviews, comments, emailTemplate, emails, agents, form))
    }
  }

  def edit(id: String) = loginAction { implicit request =>
    val agent = request.currentAgent
    applicationById(id, request.currentCity) match {
      case None =>
        NotFound("")
      case Some(application) =>

        applicationEditForm.bindFromRequest().fold(
          formWithErrors => {
            val agents = agentService.all(request.currentCity)
            val reviews = reviewService.findByApplicationId(id)
              .map { review =>
                review -> agents.find(_.id == review.agentId).get
              }
            val comments = commentService.findByApplicationId(id)
              .map { comment =>
                comment -> agents.find(_.id == comment.agentId).get
              }
            val emailTemplate = (application._1.status match {
              case "Favorable" => Some("FAVORABLE_EMAIL")
              case "Défavorable" => Some("UNFAVORABLE_EMAIL")
              case _ => None
            }).flatMap(emailTemplateService.get(application._1.city))

            val emails = emailSentService.findByApplicationId(application._1.id)
            Ok(views.html.application(application._1, agent, reviews, comments, emailTemplate, emails, agents, formWithErrors))
          },
          applicationEdit => {
            val applicationEditFix = applicationEdit.fixFrom(application._1)
            val newApplication = application._1.copy(newApplicantEmail = applicationEdit.applicantEmail)
            if(applicationService.update(id, applicationEditFix)) {
              val emailSent = emailSentService.findBySentTo(newApplication.applicantEmail, Email.Type.NEW_APPLICATION_APPLICANT)
              if(emailSent.isEmpty) {
                 notificationsService.newApplication(newApplication, notifyInstructors = false)
              }
              Redirect(routes.ApplicationController.show(id)).flashing("success" -> "Votre modification a bien été pris en compte.")
            } else {
              Redirect(routes.ApplicationController.show(id)).flashing("error" -> "Votre modification n'a pas été pris en compte :(")
            }
        })
    }
  }

  private def applicationById(id: String, city: String) =
    projects(city).find { _._1.id == id }


  def changeCity(newCity: String) = Action { implicit request =>
    Redirect(routes.ApplicationController.getLogin()).withSession("city" -> newCity.toLowerCase)
  }

  def disconnectAgent() = Action { implicit request =>
    Redirect(routes.ApplicationController.getLogin()).withSession(request.session - "agentId")
  }

  def getLogin() = Action { implicit request =>
    request.session.get("city").map(_.toLowerCase()).fold {
      BadRequest("Pas de ville sélectionné. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
    } { city =>
      Ok(views.html.login(city, Left(agentService.all(city))))
    }
  }

  def postLogin() = Action { implicit request =>
    request.session.get("city").map(_.toLowerCase()).fold {
      BadRequest("Pas de ville sélectionné. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
    } { city =>
      val agents = agentService.all(city)
      request.body.asFormUrlEncoded.get.get("id").flatMap(_.headOption).flatMap(id => agents.find(_.id == id)).fold {
        Redirect(routes.ApplicationController.getLogin()).flashing("error" -> "Agent manquant ou inconnu")
      } { agent =>
        sendLoginEmailToAgent(request, city, agent)
        Ok(views.html.login(city, Right(agent)))
      }
    }
  }

  private def sendLoginEmailToAgent(request: Request[AnyContent], city: String, agent: Agent) = {
    val url = s"${routes.ApplicationController.my().absoluteURL()(request)}?city=$city&key=${agent.key}"
    val bodyHtml = s"""Bonjour ${agent.name},<br>
                      |<br>
                      |Vous pouvez voir les demandes de végétalisation en ouvrant l'adresse suivante :<br>
                      |<a href="${url}">${url}</a><br>
                      |<br>
                      |Merci de votre aide,<br>
                      |Si vous avez des questions, n'hésitez pas à nous contacter en répondant à ce mail<br>
                      |Equipe Plante Et Moi""".stripMargin
    val bodyText = bodyHtml.replaceAll("<[^>]*>", "")
    val email = play.api.libs.mailer.Email(
      s"Connexion à Plante Et Moi",
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${agent.name} <${agent.email}>"),
      bodyHtml = Some(bodyHtml),
      bodyText = Some(bodyText)
    )
    mailerClient.send(email)
  }

  case class CommentData(comment: String)
  val commentForm = Form(
    mapping(
      "comment" -> text
    )(CommentData.apply)(CommentData.unapply)
  )

  def addComment(applicationId: String) = loginAction { implicit request =>
    (commentForm.bindFromRequest.value, applicationById(applicationId, request.currentCity)) match {
      case (Some(commentData), Some((application, reviews))) =>
        val comment = Comment(UUID.randomUUID, applicationId, request.currentAgent.id, request.currentCity, DateTime.now(), commentData.comment)
        commentService.insert(comment) // Erreur non géré
        Redirect(routes.ApplicationController.show(applicationId)).flashing("success" -> "Votre commentaire a bien été pris en compte.")
      case _ =>
        BadRequest("Error pour l'ajout du commentaire: la demande n'existe pas ou le contenu du formulaire est incorrect. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
    }
  }

  case class ReviewData(favorable: Boolean, comment: String)
  val reviewForm = Form(
    mapping(
      "favorable" -> FormRequireBoolean.requiredBoolean,
      "comment" -> text
    )(ReviewData.apply)(ReviewData.unapply)
  )

  def addReview(applicationId: String) = loginAction.async { implicit request =>
    if (!request.currentAgent.canReview) {
      Future.successful(Unauthorized("Vous n'avez pas le droit d'ajouter un avis sur cette demande"))
    } else {
      (reviewForm.bindFromRequest.value, applicationById(applicationId, request.currentCity)) match {
        case (Some(reviewData), Some((application, reviews))) =>
          val agent = request.currentAgent
          val agents = agentService.all(request.currentCity)
          val review = Review(applicationId, agent.id, DateTime.now(), reviewData.favorable, reviewData.comment)
          Future(reviewService.insertOrUpdate(review)).map { edited =>
            val numberOrReviewNeededBeforeFinal = application.numberOfReviewNeeded(agents)
            val numberOfReview = reviews.length
            if (!reviews.exists(_.agentId == agent.id) && numberOrReviewNeededBeforeFinal == numberOfReview) {
              agentService.all(request.currentCity).filter { agent => agent.finalReview }.foreach(sendRequestDecisionEmailToAgent(application, request))
            }
            if(edited) {
              notificationsService.applicationUpdated(application, "modifié un avis", "CHANGE_REVIEW", agent)
            } else {
              notificationsService.applicationUpdated(application, "ajouté un avis", "ADD_REVIEW", agent)
            }
            Redirect(routes.ApplicationController.my()).flashing("success" -> "Votre avis a bien été pris en compte.")
          }
        case _ =>
          Future.successful(BadRequest("Error pour l'ajout de l'avis: la demande n'existe pas ou le contenu du formulaire est incorrect. Vous pouvez signaler l'erreur à l'équipe Plante et Moi"))
      }
    }
  }

  def takeDecision(applicationId: String) = loginAction { implicit request =>
    if(!request.currentAgent.finalReview) {
      Unauthorized("Vous n'avez pas le droit de prendre un décision")
    } else {
      (reviewForm.bindFromRequest.value, applicationById(applicationId, request.currentCity)) match {
        case (Some(reviewData), Some((application, reviews))) =>
          val agent = request.currentAgent
          val status = reviewData.favorable match {
            case true => "Favorable"
            case false => "Défavorable"
          }
          val newApplication = application.copy(status = status)
          applicationService.update(newApplication)
          agentService.all(request.currentCity).filter {
            _.instructor
          }.foreach(sendCompletedApplicationEmailToAgent(application, request, agent, status))
          Redirect(routes.ApplicationController.my()).flashing("success" -> "Votre décision a bien été pris en compte.")
        case _ =>
          BadRequest("Error pour la prise de décision, la demande n'existe pas ou le contenu du formulaire est incorrect. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
      }
    }
  }

  def invalidate(applicationId: String) = loginAction { implicit request =>
    if (!request.currentAgent.instructor) {
      Unauthorized("Vous n'avez pas le droit d'invalider une demande")
    } else {
      applicationById(applicationId, request.currentCity) match {
        case Some((application, reviews)) =>
          val agent = request.currentAgent
          val newApplication = application.copy(status = "Invalide")
          applicationService.update(newApplication)
          Redirect(routes.ApplicationController.my()).flashing("success" -> "La demande a été invalidé")
        case _ =>
          BadRequest("Error pour l'invalidation, la demande n'existe pas. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
      }
    }
  }

  def addFile(applicationId: String) = loginAction { request =>
    (request.body.asMultipartFormData.get.file("file"), applicationById(applicationId, request.currentCity)) match {
      case (Some(uploadedFile), Some((application, reviews))) if !uploadedFile.filename.isEmpty =>
        val filename = uploadedFile.filename
        val contentType = uploadedFile.contentType
        val file = File(UUID.randomUUID, applicationId, Some(request.currentAgent.id), request.currentCity, DateTime.now(), filename, contentType, Some(Files.readAllBytes(uploadedFile.ref.file.toPath)))
        fileService.insert(file)
        Redirect(routes.ApplicationController.show(applicationId)).flashing("success" -> "Votre fichier a bien été pris en compte.")
      case _ =>
        BadRequest("Error pour l'ajout du fichier: la demande n'existe pas ou le contenu du formulaire est incorrect. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
    }
  }

  case class AskReviewData(agentIds: List[String])
  val askReviewForm = Form(
    mapping(
      "agents" -> list(text)
    )(AskReviewData.apply)(AskReviewData.unapply)
  )

  def askReview(applicationId: String) = loginAction { implicit request =>
    (askReviewForm.bindFromRequest.value, applicationById(applicationId, request.currentCity)) match {
      case (Some(askReviewData), Some((application, _))) =>

        val selectedAgents = agentService.all(request.currentCity).filter { agent => askReviewData.agentIds.contains(agent.id) }
        selectedAgents.filter { agent => !application.reviewerAgentIds.contains(agent.id) }.foreach(sendRequestReviewEmailToAgent(application, request))

        val newApplication = application.copy(status = "En cours", reviewerAgentIds = selectedAgents.map(_.id))
        applicationService.update(newApplication)

        Redirect(routes.ApplicationController.my()).flashing("success" -> "Le statut de la demande a été mis à jour, un mail a été envoyé aux agents pour obtenir leurs avis.")
      case _ =>
        NotFound("Formulaire incorrect ou application incorrect. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
    }
  }

  private def sendRequestReviewEmailToAgent(application: models.Application, request: RequestWithAgent[AnyContent])(agent: Agent) = {
    val url = s"${routes.ApplicationController.show(application.id).absoluteURL()(request)}?city=${request.currentCity}&key=${agent.key}"
    val email = play.api.libs.mailer.Email(
      s"Demande d'avis permis de végétalisation : ${application.address}",
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${agent.name} <${agent.email}>"),
      bodyText = Some(s"""Bonjour ${agent.name},
                    |
                    |Nous avons besoin de votre avis pour une demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                    |Vous pouvez voir la demande et laisser votre avis en ouvrant la page suivante :
                    |${url}
                    |
                    |Merci de votre aide,
                    |Si vous avez des questions, n'hésitez pas à nous contacter en répondant à ce mail,
                    |Equipe Plante Et Moi""".stripMargin)
    )
    mailerClient.send(email)
  }

  private def sendRequestDecisionEmailToAgent(application: models.Application, request: RequestWithAgent[AnyContent])(agent: Agent) = {
    val url = s"${routes.ApplicationController.show(application.id).absoluteURL()(request)}?city=${request.currentCity}&key=${agent.key}"

    val email = play.api.libs.mailer.Email(
      s"Demande de décision pour permis de végétalisation : ${application.address}",
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${agent.name} <${agent.email}>"),
      bodyText = Some(s"""Bonjour ${agent.name},
                         |
                         |Nous avons besoin que vous preniez une décision pour une demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                         |Vous pouvez voir la demande et prendre la décision en ouvrant la page suivante :
                         |${url}
                         |
                         |Merci de votre aide,
                         |Si vous avez des questions, n'hésitez pas à nous contacter en répondant à ce mail,
                         |Equipe Plante Et Moi""".stripMargin)
    )
    mailerClient.send(email)
  }

  private def sendCompletedApplicationEmailToAgent(application: models.Application, request: RequestWithAgent[AnyContent], finalAgent: Agent, status: String)(agent: Agent) = {
    val url = s"${routes.ApplicationController.show(application.id).absoluteURL()(request)}?city=${request.currentCity}&key=${agent.key}"
    val email = play.api.libs.mailer.Email(
      s"Décision $status pour demande de végétalisation au ${application.address}",
      "Plante et Moi <administration@plante-et-moi.fr>",
      Seq(s"${agent.name} <${agent.email}>"),
      bodyText = Some(s"""Bonjour ${agent.name},
                         |
                         |Une décision $status a été pris par ${finalAgent.name} pour la demande de végétalisation au ${application.address} (c'est un projet de ${application._type}).
                         |Vous pouvez voir la demande ici :
                         |${url}
                         |
                         |""".stripMargin)
    )
    mailerClient.send(email)
  }

  case class EmailDecisionData(body: String)
  val emailDecisionForm = Form(
    mapping(
      "body" -> text
    )(EmailDecisionData.apply)(EmailDecisionData.unapply)
  )

  def sendDecisionEmail(applicationId: String) = loginAction { implicit request =>
    (emailDecisionForm.bindFromRequest.value, applicationById(applicationId, request.currentCity)) match {
      case (Some(emailDecisionData), Some((application, reviews))) =>
        if(notificationsService.sendDecision(application, emailDecisionData.body)) {
          val newApplication = application.copy(decisionSendedDate = Some(DateTime.now(timeZone)))
          applicationService.update(newApplication)
          Redirect(routes.ApplicationController.my()).flashing("success" -> "Votre email de confirmation a bien été envoyé.")
        } else {
          Redirect(routes.ApplicationController.show(applicationId)).flashing("error" -> "Votre email de confirmation n'a pas pu être envoyé.")
        }
      case _ =>
        BadRequest("Error pour l'ajout du commentaire: la demande n'existe pas ou le contenu du formulaire est incorrect. Vous pouvez signaler l'erreur à l'équipe Plante et Moi")
    }
  }


  val applicationsChangesForm = Form(
    tuple(
      "csv" -> nonEmptyText,
      "acceptModification" -> default(boolean, false)
    )
  )

  def importForm() = loginAction { implicit request =>
    if (!request.currentAgent.instructor) {
      Unauthorized("Vous n'avez pas le droit d'importer des modifications")
    } else {
      Ok(views.html.importApplicationsChanges(applicationsChangesForm, request.currentAgent))
    }
  }

  def fixPhoneNumber(phoneNumber: String): String = {
    val phone = "^[1-9].*$".r
    phoneNumber match {
      case phoneNumber if phoneNumber.startsWith("33") && phoneNumber.length > 9 =>
        s"+$phoneNumber"
      case phone() =>
        s"0$phoneNumber"
      case _ =>
        phoneNumber
    }

  }

  def readCSV(csvText: String) = {
    implicit object SemiConFormat extends DefaultCSVFormat {
      override val delimiter = ';'
    }
    val reader = CSVReader.open(Source.fromString(csvText))
    for { applicationMap <- reader.allWithHeaders()
          applicationMapClean = applicationMap.mapValues(Charset.fixUTF8toLatin1)
          applicationOption = Application.fromMap(applicationMapClean)
          applicationOptionWithCleanNumber = applicationOption.map(
            application => application.copy(sourceApplicantPhone = application.sourceApplicantPhone.map(fixPhoneNumber))
          )
    } yield applicationOptionWithCleanNumber.map(Right.apply).getOrElse(Left(applicationMap))
  }


  def importFormPost() = loginAction { implicit request =>
    if (!request.currentAgent.instructor) {
      Unauthorized("Vous n'avez pas le droit d'importer des modifications")
    } else {
      applicationsChangesForm.bindFromRequest.fold(
        formWithErrors => {
          BadRequest(views.html.importApplicationsChanges(formWithErrors, request.currentAgent))
        },
        result => {
          var (csvText, acceptModification) = result
          val applicationsParsed = readCSV(csvText)
          val applicationsImported = applicationsParsed.flatMap(_.toOption)
          val applicationsNotImported = applicationsParsed.map(_.swap).flatMap(_.toOption)
          val applicationFromDB = applicationService.findByApplicationIds(applicationsImported.map(_.id))
          val applicationsImportedInDB = for {
            applicationImported <- applicationsImported
            applicationInDB <- applicationFromDB.find(_.id == applicationImported.id)
            if(applicationInDB.city == request.currentCity)
          } yield (applicationInDB, applicationImported)
          val applicationsImportedInDBIds = applicationsImportedInDB.map(_._1.id)
          val applicationsImportedToCreate = applicationsImported
            .filterNot(application => applicationsImportedInDBIds.contains(application.id))
          if(acceptModification) {
            for((oldApplication, newApplication) <- applicationsImportedInDB) {
              val applicationEdit = ApplicationEdit.fromApplication(newApplication).fixFrom(oldApplication)
              if(List("Favorable", "Défavorable","Invalide").contains(newApplication.status)) {
                applicationService.update(newApplication)
              }
              applicationService.update(newApplication.id, applicationEdit)
            }
            Redirect(routes.ApplicationController.all()).flashing("success" -> "Import terminé")
          } else {
            Ok(views.html.importApplicationsChangesValidation(applicationsImportedInDB, applicationsImportedToCreate, applicationsNotImported, csvText, request.currentAgent))
          }
        })
    }
  }
}
