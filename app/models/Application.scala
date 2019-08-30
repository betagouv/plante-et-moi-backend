package models

import org.joda.time.DateTime

import scala.util.control.NonFatal

case class Application(id: String,
                       city: String,
                       status: String,
                       sourceApplicantFirstname: String,
                       sourceApplicantLastname: String,
                       sourceApplicantEmail: String,
                       sourceApplicantAddress: Option[String] = None,
                       _type: String,
                       address: String,
                       creationDate: DateTime,
                       coordinates: Coordinates,
                       source: String,
                       sourceId: String,
                       sourceApplicantPhone: Option[String] = None,
                       fields: Map[String, String] = Map(),
                       originalFiles: List[String] = List(),
                       newFiles: List[String] = List(),
                       reviewerAgentIds: List[String] = List(),
                       decisionSendedDate: Option[DateTime] = None,
                       newApplicantFirstname: Option[String] = None,
                       newApplicantLastname: Option[String] = None,
                       newApplicantEmail: Option[String] = None,
                       newApplicantAddress: Option[String] = None,
                       newApplicantPhone: Option[String] = None
                      ) {

   lazy val applicantFirstname = newApplicantFirstname.getOrElse(sourceApplicantFirstname)
   lazy val applicantLastname = newApplicantLastname.getOrElse(sourceApplicantLastname)
   lazy val applicantEmail = newApplicantEmail.getOrElse(sourceApplicantEmail)
   lazy val applicantAddress = newApplicantAddress.orElse(sourceApplicantAddress)
   lazy val applicantPhone = newApplicantPhone.orElse(sourceApplicantPhone)

   val applicantName = s"${applicantFirstname.capitalize} ${applicantLastname.toUpperCase}"
   lazy val sourceApplicantName = s"${sourceApplicantFirstname.capitalize} ${sourceApplicantLastname.toUpperCase}"

   private def imageFilter(fileName: String) = List(".jpg",".jpeg",".png").exists(fileName.toLowerCase().contains(_))

   lazy val files = originalFiles ++ newFiles
   lazy val imagesFiles = files.filter(imageFilter)
   lazy val notImageFiles = files.filter(!imageFilter(_))

   def numberOfReviewNeeded(agents: Traversable[Agent]) = reviewerAgentIds.flatMap(id => agents.filter(_.id == id)).groupBy(_.qualite).size

   def reviewerAgents(agents: List[Agent]) = reviewerAgentIds.flatMap(agentId => agents.find(_.id == agentId).headOption)

   def searchData(agents: List[Agent]) = {
      val stripChars = "\"<>'";
      val agentsString = reviewerAgents(agents).map(a => s"${a.name} (${a.qualite.capitalize}").mkString(" ")
      s"""${applicantFirstname.filterNot(stripChars contains _)} ${applicantLastname.filterNot(stripChars contains _)} ${applicantEmail.filterNot(stripChars contains _)} ${applicantAddress.getOrElse("").filterNot(stripChars contains _)} ${_type.filterNot(stripChars contains _)} ${fields.values.map(_.filterNot(stripChars contains _)).mkString(" ")} $status $agentsString"""
   }

   lazy val decisionToSend = List("Favorable", "Défavorable").contains(status) && decisionSendedDate.isEmpty && creationDate.isAfter(1546297200000L)  // after 01/01/2019
}

object Application {
   def fromMap(values: Map[String,String]): Option[Application] = try{
      for { id <- values.get("Id")
           status <- values.get("Etat")
           sourceApplicantFirstname <- values.get("Prénom Demandeur")
           sourceApplicantLastname <- values.get("Nom Demandeur")
           sourceApplicantEmail <- values.get("Email Demandeur")
           sourceApplicantAddress <- values.get("Adresse Demandeur")
           _type <- values.get("Type")
           address <- values.get("Adresse Projet")
           sourceApplicantPhone <- values.get("Téléphone Demandeur")
           city = values.getOrElse("Ville", "NoCity")
           latitude = values.get("Latitude").map(_.toDouble).getOrElse(0d)
           longitude = values.get("Longitude").map(_.toDouble).getOrElse(0d)
           } yield Application(id,
            city,
            status,
            sourceApplicantFirstname,
            sourceApplicantLastname,
            sourceApplicantEmail,
            if(sourceApplicantAddress.isEmpty) None else Some(sourceApplicantAddress),
            _type,
            address,
            DateTime.now(), // ignored
            Coordinates(latitude, longitude),
            "import",
            s"import_$id",
            if(sourceApplicantPhone.isEmpty) None else Some(sourceApplicantPhone)
         )
    } catch{
      case NonFatal(ex) => None
   }
}