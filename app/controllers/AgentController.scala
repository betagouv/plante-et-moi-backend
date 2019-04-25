package controllers

import javax.inject.{Inject, Singleton}
import actions.LoginAction
import models.AgentService
import org.webjars.play.WebJarsUtil
import play.api.mvc._

@Singleton
class AgentController @Inject()(agentService: AgentService,
                                loginAction: LoginAction,
                                )(implicit val webJarsUtil: WebJarsUtil) extends InjectedController  {

  def all = loginAction { implicit request =>
    Ok(views.html.allAgents(agentService.all(request.currentCity), request.currentAgent))
  }
}
