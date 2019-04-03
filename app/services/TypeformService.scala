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


  private case class FieldProperties(
                                     description: Option[String]
                                    /* ... */
                                    )
  private case class Field(id: String,
                           ref: Option[String],
                           title: Option[String],
                           _type: String,
                           properties: Option[FieldProperties]
                           /*
                              validations,
                              attachment
                            */
                           )
  private case class Form(id: String,
                          title: String,
                          language: Option[String],
                          fields: List[Field],
                          hidden: List[String]
                          /* welvcome_screens,
                             thankyou_screens,
                             logic,
                             theme,
                              workspace,
                              _links,
                              settings
                           */
                          )
  private case class Choice(label: String,
                            other: Option[String])
  private case class Choices(labels: List[String],
                             other: Option[String])
  private case class Answer(field: Field,
                            _type: String,
                            choice: Option[Choice],
                            choices: Option[Choices],
                            date: Option[DateTime],
                            email: Option[String],
                            file_url: Option[String],
                            number: Option[Int],
                            boolean: Option[Boolean],
                            text: Option[String],
                            url: Option[String]
                            /* payment */
                           )
  private case class Metadata(platform: String,
                              user_agent: String,
                              referer: String,
                              network_id: String)
  private case class Definition(fields: Field)
  private case class Response(landing_id: String, landed_at: DateTime, token: String, submitted_at: DateTime, metadata: Metadata, hidden: Map[String, Option[String]], definition: Option[Definition], answers: List[Answer] /* Calculated */)
  private case class Result(total_items: Int, page_count: Int, items: List[Response] /* _links */)


  private implicit val optionMap = Reads[Option[String]]{
    case JsString(s) => JsSuccess(Some(s))
    case JsNull => JsSuccess(None)
    case _ => JsError("Not a optionnal String")
  }

  def typeReads[T](r: Reads[T]): Reads[T] = {
    JsPath.json.update((JsPath \ '_type).json.copyFrom((JsPath \ 'type).json.pick[JsString])) andThen r
  }

  //private implicit val hiddenMap = Reads.mapReads[Option[String]]
  private implicit val dateReads = Reads.jodaDateReads("yyyy-MM-dd'T'HH:mm:ssZ")
  private implicit val readsFieldProperties = Json.reads[FieldProperties]
  private implicit val readsField = typeReads(Json.reads[Field])
  private implicit val readsForm = Json.reads[Form]
  private implicit val readsChoice = Json.reads[Choice]
  private implicit val readsChoices = Json.reads[Choices]
  private implicit val readsAnswer = typeReads(Json.reads[Answer])
  private implicit val readsMetadata = Json.reads[Metadata]
  private implicit val readsDefinition = Json.reads[Definition]
  private implicit val readsResponse = Json.reads[Response]
  private implicit val resultReads = Json.reads[Result]

  private def getForm(id: String, key: String) = {
    val request = ws.url(s"https://api.typeform.com/forms/$id")
        .withHeaders("authorization" -> s"bearer $key")
    Logger.info(s"Get typeform data ${request.url}")
    request.get().map { response =>
      response.json.validate[Form] match {
        case success: JsSuccess[Form] =>
          Logger.info(s"TypeformService: success retrieve form $id")
          val form = success.get
          Some(form)
        case error: JsError =>
          val errorString = JsError.toJson(error).toString()
          Logger.error(s"TypeformService: json errors for $id $errorString")
          None
      }
    }
  }

  private def getFormAnswer(id: String, key: String, completed: Boolean, pageSize: Int = 20, orderBy: String = "submitted_at,desc") = {
    val request = ws.url(s"https://api.typeform.com/forms/$id/responses")
      .withQueryString(
        "completed" -> s"$completed",
        "sort" -> orderBy,
        "page_size" -> s"$pageSize")
      .withHeaders("authorization" -> s"bearer $key")
    Logger.info(s"Get typeform data ${request.url}")
    request.get().map { response =>
      response.json.validate[Result] match {
        case success: JsSuccess[Result] =>
          Logger.info(s"TypeformService: sucessful retrieve form $id answer")
          val form = success.get
          Some(form)
        case error: JsError =>
          val errorString = JsError.toJson(error).toString()
          Logger.error(s"TypeformService: json errors for $id $errorString")
          None
      }
    }
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


  private def getApplicationForForm(id: String) = {
    for {
      formResult <- getForm(id, key)
      answersResult <- getFormAnswer(id, key, true, 300)
    } yield (formResult, answersResult) match {
      case (Some(form), Some(result)) =>
        result.items
          .filter(filterPerDomains)
          .map(mapResponseToApplication(form.fields))
      case _ =>
        Logger.error(s"TypeformService: no valid result for $id")
        List[Application]()
    }
  }

  private def refreshTask = {
    val responses = Future.reduce(typeformIds.map(getApplicationForForm))(_ ++ _)
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
    val domain = response.hidden.get("domain").flatten.getOrElse("nodomain")
    domains.contains(domain)
  }

  def mapResponseToApplication(formFields: List[Field])(response: Response) = {
    val hiddenType = response.hidden("type").get

    val _type = hiddenType.stripPrefix("projet de ").stripSuffix(" fleuris").capitalize
    val lat = response.hidden("lat").get.toDouble
    val lon = response.hidden("lon").get.toDouble
    val coordinates = Coordinates(lat, lon)
    val city = response.hidden("city").get.toLowerCase()
    val typeformId = response.token
    val date = response.landed_at

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
      val fieldFindResult = formFields.find(_.id == answer.field.id)
      val fieldTitle = fieldFindResult.map(
        _.title.get.replaceAll("<[^>]*>", "").replaceAll("\\{\\{hidden:address\\}\\}", hiddenAddress).replaceAll("\\{\\{hidden:type\\}\\}", hiddenType)
      ).get
      if(answer._type == "file_url")
        files += answer.file_url.get
      else if(answer._type == "email")
        email = answer.email.get
      else if(answer._type == "text") {
        val text = answer.text.get.toLowerCase
        if(fieldTitle.contains("adresse de votre")) {
          address = text
        } else if(fieldTitle.endsWith("prénom")) {
          applicantFirstname = text
        } else if(fieldTitle.endsWith(" nom")) {
          applicantLastname = text
        } else if(fieldTitle.contains("téléphone")) {
          applicantPhone = Some(text)
        } else if(fieldTitle.endsWith("adresse postale")) {
          applicantAddress = Some(text)
        }
      }
      else if(answer._type == "boolean") {
        val answerField = if(answer.boolean.get) {
          "Oui"
        } else {
          "Non"
        }
        fields += fieldTitle -> answerField
      }
      else {                                 //TODO: other
        val text: String = answer.text.orElse(answer.choice.map(_.label))
          .orElse(answer.choices.map(_.labels.mkString("\n")))
          .orElse(answer.email)
          .orElse(answer.number.map(_.toString))
          .orElse(answer.url).get
        fields += fieldTitle -> text
      }
    }

    val source = "typeform"
    val applicationId = Hash.md5(s"$source$typeformId")
    models.Application(applicationId, city, "Nouvelle", applicantFirstname, applicantLastname, email, applicantAddress, _type, address, date, coordinates, source, typeformId, applicantPhone, fields.toMap, files.toList)
  }
}
