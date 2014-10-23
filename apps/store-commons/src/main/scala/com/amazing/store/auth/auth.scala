package com.amazing.store.auth

import java.net.URLEncoder

import com.amazing.store.models.User
import com.amazing.store.services.UserServiceClient
import com.amazing.store.tools.implicits.flatfutures._
import play.api.Play.current
import play.api.libs.Crypto
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Results._
import play.api.mvc.WebSocket.HandlerProps
import play.api.mvc._

import scala.concurrent.Future

object AuthConfig {
  val SESSION_NAME = "AMAZING-COOKIE"
}

object AuthActions {

  def UserSecured(login: String)(action: User => Request[AnyContent] => Future[Result]) = Action.async { request =>
    val currentUrl = URLEncoder.encode(s"http://${request.domain}${request.uri}")
    val fail1: Future[Result] = Future.successful(Redirect(s"$login/login?service=$currentUrl#1"))
    val fail2: Future[Result] = Future.successful(Redirect(s"$login/login?service=$currentUrl#2"))
    val fail3: Future[Result] = Future.successful(Redirect(s"http://${request.domain}/retry?where=$currentUrl#3"))
    request.cookies.get(AuthConfig.SESSION_NAME).fold(fail1)(cookie =>
      cookie.value.split(":::").toList match {
        case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash => UserServiceClient.findUserByLogin(userLogin).flattenM(user => action(user)(request))(fail3)
        case _ => fail2
      })
  }

  def UserSecuredJson()(action: User => Request[AnyContent] => Future[Result]) = Action.async { request =>
    val fail: Future[Result] = Future.successful(
      Unauthorized(Json.obj())
        .withHeaders(
          "Access-Control-Allow-Credentials" -> "true",
          "Access-Control-Allow-Origin" -> "http://www.amazing.com"))
    request.cookies.get(AuthConfig.SESSION_NAME).fold(fail)(cookie =>
      cookie.value.split(":::").toList match {
        case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash => UserServiceClient.findUserByLogin(userLogin).flattenM(user => action(user)(request))(fail)
        case _ => fail
      })
  }


  def UserSecuredWebSocket()(action: User =>  RequestHeader => HandlerProps) = WebSocket.tryAcceptWithActor[JsValue, JsValue] { request =>
    val fail = Future.successful(Left(Forbidden))
    request.cookies.get(AuthConfig.SESSION_NAME).map { cookie: Cookie =>
      cookie.value.split(":::").toList match {
        case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash =>
          UserServiceClient.findUserByLogin(userLogin).map {
            case Some(user) => Right(action(user)(request))
            case _ => Left(Forbidden)
          }
        case _ => fail
      }
    }.getOrElse(fail)
  }


  def BackendSecured[A](username: String, password: String)(action: Request[AnyContent] => Future[Result]) = Action.async { request =>
    request.headers.get("Authorization").flatMap { authorization =>
      authorization.split(" ").drop(1).headOption.filter { encoded =>
        new String(org.apache.commons.codec.binary.Base64.decodeBase64(encoded.getBytes)).split(":").toList match {
          case u :: p :: Nil if u == username && password == p => true
          case _ => false
        }
      }.map(_ => action(request))
    }.getOrElse {
      Future.successful(Unauthorized.withHeaders("WWW-Authenticate" -> """Basic realm="Amazing Store Backend""""))
    }
  }
}
