package controllers

import javax.inject.{Inject, Singleton}

import actions.LoginAction
import models.AgentService
import play.api.mvc._

@Singleton
class AgentController @Inject()(agentService: AgentService,
                                loginAction: LoginAction,
                                implicit val webJarAssets: WebJarAssets) extends Controller {

  def all = loginAction { implicit request =>
    Ok(views.html.allAgents(agentService.all(request.currentCity), request.currentAgent))
  }
}
