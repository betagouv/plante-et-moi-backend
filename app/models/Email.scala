package models

import org.joda.time.DateTime

case class Email(id: String,
                 applicationId: String,
                 agentId: Option[String],
                 city: String,
                 creationDate: DateTime,
                 _type: String,
                 subject: String,
                 sentFrom: String,
                 sentTo: Array[String],
                 bodyText: String,
                 replyTo: Option[String] = None)