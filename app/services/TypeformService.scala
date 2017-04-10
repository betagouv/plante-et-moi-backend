package services

import java.util.Locale
import javax.inject._

import akka.actor._
import models.{Application, Coordinates, EmailTemplate, EmailTemplateService}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.libs.ws.WSClient

import scala.collection.mutable.ListBuffer
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.mailer.{Email, MailerClient}
import utils.Hash

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

@Singleton
class TypeformService @Inject()(system: ActorSystem, configuration: play.api.Configuration, ws: WSClient, applicationService: ApplicationService, notificationsService: NotificationsService) {

  private case class Question(id: String, question: String, field_id: Int)
  private case class Stats(responses: Map[String,Int])
  private case class Metadata(browser: String,
                              platform: String,
                              date_land: DateTime,
                              date_submit: DateTime,
                              user_agent: String,
                              referer: String,
                              network_id: String)
  private case class Response(completed: String, token: String, metadata: Metadata, hidden: Map[String, Option[String]], answers: Map[String, String])
  private case class Result(http_status: Int, stats: Stats, questions: List[Question], responses: List[Response])


  private implicit val optionMap = Reads[Option[String]]{
    case JsString(s) => JsSuccess(Some(s))
    case JsNull => JsSuccess(None)
    case _ => JsError("Not a optionnal String")
  }
  //private implicit val hiddenMap = Reads.mapReads[Option[String]]
  private implicit val dateReads = Reads.jodaDateReads("yyyy-MM-dd HH:mm:ss")
  private implicit val metadataReads = Json.reads[Metadata]
  private implicit val questionReads = Json.reads[Question]
  private implicit val statsReads = Json.reads[Stats]
  private implicit val responseReads = Json.reads[Response]
  private implicit val resultReads = Json.reads[Result]


  def getForm(id: String, key: String, completed: Boolean, limit: Int = 20, orderBy: String = "date_submit,desc") = {
    val request = ws.url(s"https://api.typeform.com/v1/form/$id")
      .withQueryString("key" -> key,
        "completed" -> s"$completed",
        "order_by" -> orderBy,
        "limit" -> s"$limit")
    Logger.info(s"Get typeform data ${request.url}")
    request.get()
  }

  private lazy val typeformIds = configuration.underlying.getString("typeform.ids").split(",")
  lazy val key = configuration.underlying.getString("typeform.key")
  private lazy val domains = configuration.underlying.getString("typeform.domains").split(",")

  private val refresh = configuration.getMilliseconds("typeform.refresh") match {
    case Some(t) => t millis
    case None => 2 minutes
  }
  private val delay = 0 minutes

  private val scheduledTask = system.scheduler.schedule(delay, refresh)(refreshTask)

  private def refreshTask = {
    val responses = Future.reduce(typeformIds.map{ id =>
      getForm(id, key, true, 100).map { response =>
        val result = response.json.validate[Result]
        var applications = List[Application]()
        result match {
          case success: JsSuccess[Result] =>
            Logger.info(s"TypeformService: convert data for $id")
            val result = success.get
            applications = result.responses
                .filter(filterPerDomains)
                .map(mapResponseToApplication(result.questions))
          case error: JsError =>
            val errorString = JsError.toJson(error).toString()
            Logger.error(s"TypeformService: json errors for $id $errorString")
        }
        applications
      }
    })(_ ++ _)
    responses.onComplete {
      case Failure(ex) =>
          Logger.error("Error occured when retrieve data from typeform", ex)
      case Success(applications) =>
        applications.groupBy(_.city).foreach { cityApplications =>
          manageApplicationForCity(cityApplications._1, cityApplications._2)
        }
    }
  }

  private def manageApplicationForCity(city: String, applications: List[Application]): Unit = {
    applications foreach { application =>
      val app = applicationService.findByApplicationId(application.id)
      if (app.isEmpty) {
        Logger.info(s"Import application for ${application.address}")
        val succesNotification = notificationsService.newApplication(application)
        if (succesNotification) {
          applicationService.insert(application)
        }
      }
    }
  }

  private def filterPerDomains(response: Response): Boolean = {
    val domain = response.hidden("domain").getOrElse("nodomain")
    domains.contains(domain)
  }

  def mapResponseToApplication(questions: List[Question])(response: Response) = {
    val hiddenType = response.hidden("type").get

    val _type = hiddenType.stripPrefix("projet de ").stripSuffix(" fleuris").capitalize
    val lat = response.hidden("lat").get.toDouble
    val lon = response.hidden("lon").get.toDouble
    val coordinates = Coordinates(lat, lon)
    val city = response.hidden("city").get.toLowerCase()
    val typeformId = response.token
    val date = response.metadata.date_submit

    val hiddenAddress = response.hidden("address").get

    var address = hiddenAddress
    var email = "inconnue@example.com"
    var applicantFirstname = "John"
    var applicantLastname = "Doe"
    var applicantPhone: Option[String] = None
    var applicantAddress: Option[String] = None

    var fields = mutable.Map[String,String]()
    var files = ListBuffer[String]()
    response.answers.foreach { answer =>
      val question = questions.find(_.id == answer._1).map(
        _.question.replaceAll("<[^>]*>", "").replaceAll("\\{\\{hidden_address\\}\\}", hiddenAddress).replaceAll("\\{\\{hidden_type\\}\\}", hiddenType)
      )
      (answer._1, question) match {
        case (id, _) if id.startsWith("fileupload_") =>
          files += answer._2.split('?')(0)
        case (id, _) if id.startsWith("email_") =>
          email = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.toLowerCase.contains("adresse de votre") =>
          address = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.toLowerCase.endsWith("prénom") =>
          applicantFirstname = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.toLowerCase.endsWith(" nom") =>
          applicantLastname = answer._2
        case (id, Some(question)) if id.startsWith("textfield_") && question.toLowerCase.contains("téléphone") =>
          applicantPhone = Some(answer._2)
        case (id, Some(question)) if id.startsWith("textfield_") && question.toLowerCase.endsWith("adresse postale") =>
          applicantAddress = Some(answer._2)
        case (id, Some(question)) if id.startsWith("yesno_") =>
          val answerString = answer._2 match {
            case "1" => "Oui"
            case "0" => "Non"
            case _ => "???"
          }
          fields += question -> answerString
        case (_, Some(question)) =>
          val previous = fields.get(question).map(old => s"$old\n${answer._2}").getOrElse(answer._2)
          fields += question -> previous
        case _ =>
      }
    }
    var source = "typeform"
    var applicationId = Hash.md5(s"$source$typeformId")
    models.Application(applicationId, city, "Nouvelle", applicantFirstname, applicantLastname, email, applicantAddress, _type, address, date, coordinates, source, typeformId, applicantPhone, fields.toMap, files.toList)
  }
}