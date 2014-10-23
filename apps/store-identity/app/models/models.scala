package models

import java.util.UUID

import akka.actor.Actor
import com.amazing.store.messages.{GetUser, GetUserResponse}
import com.amazing.store.models.User
import com.datastax.driver.core.ExecutionInfo
import com.datastax.driver.core.utils.UUIDs
import config.Env
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, Future}

object UserStore {



  implicit val userFmt = Json.format[User]

  def cassandra = Env.cassandra()

  val keyspace = "user_store"
  val tableName = s"$keyspace.users"

  def init(): Unit = {

    Await.result(cassandra.cql(s"""
    CREATE KEYSPACE IF NOT EXISTS $keyspace
    WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }
    """).perform, 30 second)

    Await.result(cassandra.cql(s"""
    CREATE TABLE IF NOT EXISTS $tableName (
      id uuid PRIMARY KEY,
      login text,
      name text,
      password text
    ) WITH compression = { 'sstable_compression' : '' };
    """).perform, 30 second)
    Await.result(cassandra.cql(s"""
      CREATE INDEX IF NOT EXISTS login ON $tableName (login)
      ;""").perform, 30 second)
  }

  var users : Seq[User] = Seq[User]()

  def findByLogin(login: String): Future[Option[User]] = {
    cassandra.cql(s"SELECT * FROM $tableName WHERE login = ? ").on(login).firstOf[User]
  }

  def save(user: User): Future[Unit] = {
    this.findByLogin(user.login).flatMap{
      case None =>
        create(user).map(_ => Unit)
      case _ =>
        update(user).map(_ => Unit)
    }
  }

  def create(user: User): Future[ExecutionInfo] = {
    cassandra.cql(s"INSERT INTO $tableName (id, login, name, password) VALUES (? ,?, ?, ?) ;").on(UUIDs.timeBased(), user.login, user.name, user.password).perform
  }

  def update(user: User): Future[ExecutionInfo] = {
    cassandra.cql(s"UPDATE $tableName SET name = ?, password = ? WHERE id = ?").on(user.name, user.password, UUID.fromString(user.id)).perform
  }

  def delete(id: String): Future[ExecutionInfo] = {
    cassandra.cql(s"DELETE FROM $tableName WHERE id = ? ;").on(UUID.fromString(id)).perform
  }

  def deleteAll(): Future[ExecutionInfo] = {
    cassandra.cql(s"DELETE FROM $tableName;").perform
  }

  def drop(): Future[ExecutionInfo] = {
    cassandra.cql(s"DROP KEYSPACE $keyspace;").perform
  }
}


class UserService extends Actor {
  def receive: Receive = {
    case GetUser(login) => {
      val ctx = metrics.Messages.ping
      config.Env.logger.info(s"GET USER for $login")
      val actor = sender()
      UserStore.findByLogin(login).map(user => actor ! GetUserResponse(user))
      ctx.close()
    }
    case _ =>
  }
}

