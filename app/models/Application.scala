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
                       newFiles: List[String] = List()
                       ) {

   val applicantName = s"${applicantFirstname.capitalize} ${applicantLastname.capitalize}"
   private def imageFilter(fileName: String) = List("jpg","jpeg","png").exists(fileName.toLowerCase().endsWith(_))

   def files = originalFiles ++ newFiles
   def imagesFiles() = files.filter(imageFilter)
   def notImageFiles() = files.filter(!imageFilter(_))
}