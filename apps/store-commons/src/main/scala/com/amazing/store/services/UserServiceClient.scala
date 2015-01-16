package com.amazing.store.services

import com.amazing.store.services.Directory._

import scala.concurrent.{ExecutionContext, Future}
import com.amazing.store.models.User
import com.amazing.store.messages.{GetUser, GetUserResponse}
import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport

object UserServiceClient {

  def findUserByLogin(login: String)(implicit ec: ExecutionContext): Future[Option[User]] = {
    ServiceRegistry.registry().akkaClient(USER_SERVICE_NAME)
      .ask[GetUserResponse](GetUser(login)).map {
        case Some(GetUserResponse(Some(user))) => Some(user)
        case _ => None
      } recoverWith {
        case _ => Future.successful(None)
      }
  }
}
