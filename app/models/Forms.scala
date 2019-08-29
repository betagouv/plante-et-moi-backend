package models

import views.html.helper.FieldConstructor

object Forms {
   implicit val inputFields = FieldConstructor(views.html.helpers.input.f)

   case class ApplicationEdit(applicantEmail: Option[String],
                              applicantFirstname: Option[String],
                              applicantLastname: Option[String],
                              applicantAddress: Option[String],
                              applicantPhone: Option[String]
                             ) {
     def fixFrom(application: Application): ApplicationEdit = {
       this.copy(
         applicantEmail = if(applicantEmail.contains(application.sourceApplicantEmail)) None else applicantEmail,
         applicantFirstname = if(applicantFirstname.contains(application.sourceApplicantFirstname)) None else applicantFirstname,
         applicantLastname = if(applicantLastname.contains(application.sourceApplicantLastname)) None else applicantLastname,
         applicantAddress = if(applicantAddress == application.sourceApplicantAddress) None else applicantAddress,
         applicantPhone = if(applicantPhone == application.sourceApplicantPhone) None else applicantPhone,
       )
     }
   }
   object ApplicationEdit {
      def fromApplication(application: Application)
          = ApplicationEdit(
            Some(application.applicantEmail),
            Some(application.applicantFirstname),
            Some(application.applicantLastname),
            application.applicantAddress,
            application.applicantPhone,
         )
   }
}
