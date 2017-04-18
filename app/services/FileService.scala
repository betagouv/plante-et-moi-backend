package services

import javax.inject.Inject

import models.File
import play.api.db.DBApi
import anorm._
import anorm.JodaParameterMetaData._

class FileService @Inject()(dbapi: DBApi) {
  private val db = dbapi.database("default")
  val simple: RowParser[File] = Macro.parser[File]("id", "application_id", "agent_id", "city", "creation_date", "name", "type", "data")

  def findById(id: String) = db.withConnection { implicit connection =>
    SQL("SELECT * FROM file WHERE id = {id}").on('id -> id).as(simple.singleOpt)
  }

  def insert(file: File) = db.withTransaction { implicit connection =>
    SQL(
      """
          INSERT INTO file VALUES (
            {id}, {application_id}, {agent_id}, {city}, {creation_date}, {name}, {type}, {data}
          )
      """
    ).on(
      'id -> file.id,
      'application_id -> file.applicationId,
      'agent_id -> file.agentId,
      'city -> file.city,
      'creation_date -> file.creationDate,
      'name -> file.name,
      'type -> file._type,
      'data -> file.data
    ).executeUpdate()
    SQL(
      """
        UPDATE application_extra
        SET files = array_to_json(
          array_append(
            array(select * from jsonb_array_elements_text(files)),
            {file_url}::text
          )
        )
        WHERE application_id = {application_id}
      """)
      .on(
        'application_id -> file.applicationId,
        'file_url -> file.url
      ).executeUpdate()
  }
}
