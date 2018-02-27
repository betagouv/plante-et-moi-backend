package models

import org.joda.time.DateTime

case class Application(id: String,
                       city: String,
                       status: String,
                       applicantFirstname: String,
                       applicantLastname: String,
                       applicantEmail: String,
                       applicantAddress: Option[String] = None,
                       _type: String,
                       address: String,
                       creationDate: DateTime,
                       coordinates: Coordinates,
                       source: String,
                       sourceId: String,
                       applicantPhone: Option[String] = None,
                       fields: Map[String, String] = Map(),
                       originalFiles: List[String] = List(),
                       newFiles: List[String] = List(),
                       reviewerAgentIds: List[String] = List()) {

   val applicantName = s"${applicantFirstname.capitalize} ${applicantLastname.capitalize}"
   private def imageFilter(fileName: String) = List("jpg","jpeg","png").exists(fileName.toLowerCase().endsWith(_))

   lazy val files = originalFiles ++ newFiles
   lazy val imagesFiles = files.filter(imageFilter)
   lazy val notImageFiles = files.filter(!imageFilter(_))

   def numberOfReviewNeeded(agents: Traversable[Agent]) = reviewerAgentIds.flatMap(id => agents.filter(_.id == id)).groupBy(_.qualite).size

   lazy val searchData = {
      val stripChars = "\"<>'";
      s"""${applicantFirstname.filterNot(stripChars contains _)} ${applicantLastname.filterNot(stripChars contains _)} ${applicantEmail.filterNot(stripChars contains _)} ${applicantAddress.getOrElse("").filterNot(stripChars contains _)} ${_type.filterNot(stripChars contains _)} ${fields.values.map(_.filterNot(stripChars contains _)).mkString(" ")} $status"""
   }
}