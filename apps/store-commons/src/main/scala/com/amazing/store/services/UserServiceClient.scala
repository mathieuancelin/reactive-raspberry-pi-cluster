package com.amazing.store.services

import scala.concurrent.{ExecutionContext, Future}
import com.amazing.store.models.User
import com.amazing.store.messages.{GetUser, GetUserResponse}

object UserServiceClient {

  def findUserByLogin(login: String)(implicit ec: ExecutionContext): Future[Option[User]] = {
    Client().withRole(Directory.ROLE_IDENTITY).ref(Directory.USER_SERVICE_NAME)
      .ask[GetUserResponse](GetUser(login)).map {
        case Some(GetUserResponse(Some(user))) => Some(user)
        case _ => None
      } recoverWith {
        case _ => Future.successful(None)
      }
  }
}
