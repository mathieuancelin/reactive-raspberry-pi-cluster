package services

import actors.Actors
import akka.actor.{ActorRef, Props}
import akka.pattern._
import akka.util.Timeout
import com.amazing.store.models.User
import config.Env
import models.UserStore
import com.amazing.store.persistence.processor.{EventsourceProcessor, Recover, RecoverStarting}
import com.amazing.store.persistence.views.{Views, View}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import services.Messages._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationLong


object Messages {
  trait Command
  case class CreateUser(id: String, login: String, name: String, password: String) extends Command
  trait Event
  case class UserCreated(id: String, login: String, name: String, password: String) extends Event
  trait Query
  case class GetUser(login: String) extends Query
}

object UserView{
  val props = Props(classOf[UserView])
}

class UserView extends View {

  def updateState(event: Event){
    event match {
      case e @ UserCreated(id, login, name, password) =>
        Env.logger.debug(s"sender : ${sender()}")
        UserStore.save(User(id, login, name, password)).map(_ => e) pipeTo sender()
    }
  }

  override def receiveEvent: Receive = {
    case e: Event =>
      Env.logger.debug(s"ReceiveEvent sender : ${sender()}")
      updateState(e)
  }

  override def receiveQuery: Receive = {
    case q @ GetUser(login) =>
      Env.logger.debug(s"Receive query $q")
      UserStore.findByLogin(login) pipeTo sender()
  }

  override def receiveRecover: Receive = {
    case RecoverStarting =>
      Env.logger.debug(s"Recover starting : dropping keyspace")
      Await.result(UserStore.drop(), 30 second)
      UserStore.init()
    case e: Event =>
      Env.logger.debug(s"Recover event $e")
      updateState(e)
  }
}

object UserService {

  implicit val timeout: Timeout = Timeout(30 second)

  def getUser(login: String): Future[Option[User]] = {
    (Actors.userView() ? GetUser(login)).mapTo[Option[User]]
  }

  def findByLoginAndPassword(login: String, password: String): Future[Option[User]] = {
    (Actors.userView() ? GetUser(login)).mapTo[Option[User]].map{
      case Some(user) if user.password equals password => Some(user)
      case _ => None
    }
  }

  def createUser(id: String, login: String, name: String, password: String): Future[User] = {
    (Actors.userProcessor() ? CreateUser(id, login, name, password)).mapTo[UserCreated].map{event =>
      User(event.id, event.login, event.name, event.password)
    }
  }

  def recover() = {
    Actors.userProcessor() ! Recover
  }
}

object UsersProcessor {
  def props(view: ActorRef) = Props(classOf[UsersProcessor], view)
}

class UsersProcessor(view: ActorRef) extends EventsourceProcessor {

  override def views: Views = Views {
    Seq(view)
  }

  override def persistentId: String = "users"

  override def receiveCommand: Receive = {
    case c @ CreateUser(id, login, name, password) =>
      Env.logger.debug(s"Getting command : $c")
      persist(UserCreated(id, login, name, password))
  }



}
