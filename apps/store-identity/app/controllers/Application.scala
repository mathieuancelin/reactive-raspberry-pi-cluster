package controllers

import com.amazing.store.auth.AuthActions._
import com.amazing.store.auth.AuthConfig
import com.amazing.store.models.User
import com.datastax.driver.core.utils.UUIDs
import config.Env
import play.api.data.Forms._
import play.api.data._
import play.api.libs.Crypto
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc._
import services.UserService

import scala.concurrent.Future

case class Login(login: String, password: String)

object Application extends Controller {

  def applicationIsUp() = Action {
    Ok("ok").withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def stats() = Action {
    Ok(Json.obj(
      "avgRate" -> metrics.Messages.avgRate,
      "avgTime" -> metrics.Messages.avgTime
    )).withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  val loginForm = Form(
    mapping(
      "login" -> text,
      "password" -> text
    )(Login.apply)(Login.unapply)
  )

  def index = Action { Redirect(routes.Application.loginPage(Some(Env.frontendLink))) }

  def loginPage(service: Option[String]) = Action {
    Ok(views.html.login(service, None))
  }

  def login(service: Option[String]) = Action.async { implicit request =>
    loginForm.bindFromRequest().fold(
      error => Future.successful(Ok(views.html.login(service, Some("Sorry, wrong login or password. Try again !")))),
      form => UserService.findByLoginAndPassword(form.login, form.password).map {
        case Some(user) => {
          val cookieValue = s"${form.login}"
          Redirect(service.getOrElse(Env.frontendLink)).withCookies(Cookie(
            name = AuthConfig.SESSION_NAME,
            value = s"${Crypto.sign(cookieValue)}:::$cookieValue",
            maxAge = Some(2592000),
            path = "/",
            domain = Some(".amazing.com")
          ))
        }
        case None => Ok(views.html.login(service, Some("Sorry, wrong login or password. Try again !")))
      }
    )
  }

  def recover() = Action{
    UserService.recover()
    Ok
  }

  def logout(login: String) = UserSecured(login) {user=> request =>
    Future.successful(Ok(views.html.logout(Some(user)))
      .discardingCookies(DiscardingCookie(name = AuthConfig.SESSION_NAME, path = "/", domain = Some(".amazing.com"))))
  }

  def sessionJson = UserSecuredJson() { user => r =>
    Future.successful(
      Ok(Json.obj("id" -> user.id, "login" -> user.login, "name" -> user.name))
      .withHeaders(
          "Access-Control-Allow-Credentials" -> "true",
          "Access-Control-Allow-Origin" -> "http://www.amazing.com")
    )
  }

  val userForm = Form(
    mapping(
      "id" -> ignored(UUIDs.timeBased.toString),
      "login" -> text,
      "name" -> text,
      "password" -> text
    )(User.apply)(User.unapply)
  )

  def createAccountPage(service: Option[String]) = Action {
    Ok(views.html.account(None, userForm, service))
  }

  def updateAccountPage(login: String, service: Option[String]) = UserSecured(login) {user=> request =>
    Future.successful(Ok(views.html.account(Some(user), userForm.fill(user), service)))
  }

  def createAccount(service: Option[String]) = Action.async { implicit request =>
    userForm.bindFromRequest.fold(
        formWithErrors => {
          Env.logger.debug(s"Errors : $formWithErrors")
          Future.successful(BadRequest(views.html.account(None, formWithErrors, service)))
        },
        userData => {
          Env.logger.debug(s"Ok redirect")
          UserService.createUser(userData.id, userData.login, userData.name, userData.password).map(_ => service match{
            case None => Redirect(routes.Application.index())
            case Some(url) => Redirect(url)
          })
        }
      )
  }

}