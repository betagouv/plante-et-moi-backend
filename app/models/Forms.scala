package models

import views.html.helper.FieldConstructor

object Forms {
   implicit val inputFields = FieldConstructor(views.html.helpers.input.f)

   case class ApplicationEdit(applicantEmail: String)
   object  ApplicationEdit {
      def fromApplication(application: Application)
          = ApplicationEdit(application.applicantEmail)
   }
}
