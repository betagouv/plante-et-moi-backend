package services

import javax.inject.Inject

import models.Comment
import play.api.db.DBApi
import anorm._
import anorm.JodaParameterMetaData._

@javax.inject.Singleton
class CommentService @Inject()(dbapi: DBApi) {
  private val db = dbapi.database("default")
  val simple: RowParser[Comment] = Macro.parser[Comment]("id", "application_id", "agent_id", "city", "creation_date", "comment")

  def findByApplicationId(applicationId: String) = db.withConnection { implicit connection =>
    SQL("SELECT * FROM comment WHERE application_id = {application_id}").on('application_id -> applicationId).as(simple.*)
  }

  def insert(comment: Comment) = db.withConnection { implicit connection =>
    SQL(
      """
          INSERT INTO comment VALUES (
            {id}, {application_id}, {agent_id}, {city}, {creation_date}, {comment}
          )
      """
    ).on(
      'id -> comment.id,
      'application_id -> comment.applicationId,
      'agent_id -> comment.agentId,
      'city -> comment.city,
      'creation_date -> comment.creationDate,
      'comment -> comment.comment
    ).executeUpdate()
  }
}