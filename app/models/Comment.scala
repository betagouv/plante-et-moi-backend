package models

import org.joda.time.DateTime

case class Comment(id: String, applicationId: String, agentId: String, city: String, creationDate: DateTime, comment: String)
