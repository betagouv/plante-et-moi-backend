package models

import org.joda.time.DateTime

case class File(id: String,
                   applicationId: String,
                   agentId: Option[String],
                   city: String,
                   creationDate: DateTime,
                   name: String,
                   _type: Option[String],
                   data: Option[Array[Byte]]) {
  lazy val url = s"internal://$id/$name"
}