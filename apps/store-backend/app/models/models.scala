package models


import java.lang
import java.util.UUID

import com.datastax.driver.core.ExecutionInfo
import config.Env
import org.joda.time.DateTime
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext



case class Product(id :String, updated: DateTime, created: DateTime, label: String, description: String, image: String, price: Double)

object Product{

  implicit val productFmt = Json.format[Product]

  def cassandra = Env.cassandra()

  def drop() = {
    Await.result(cassandra.cql("DROP KEYSPACE IF EXISTS amazingbackend; ").perform, 30 second)
    Env.logger.debug(s"KEYSPACE amazingbackend DROPED")
  }

  def init() = {
    Env.logger.debug(s"CREATING KEYSPACE amazingbackend IF NOT EXISTS")
    Await.result(cassandra.cql("CREATE KEYSPACE IF NOT EXISTS amazingbackend WITH replication = {'class':'SimpleStrategy', 'replication_factor':1};")
      .perform, 30 second)

    Env.logger.debug(s"CREATING TABLE amazingbackend.products IF NOT EXISTS")
    Await.result(cassandra.cql(
      """CREATE TABLE IF NOT EXISTS amazingbackend.products (
      id varchar PRIMARY KEY,
      updated timestamp,
      created timestamp,
      label text,
      description text,
      image text,
      price double
      ) WITH compression = { 'sstable_compression' : '' } ;"""
    ).perform, 30 second)
  }

  def create(id :String, created: DateTime, label: String, description: String, image: String, price: lang.Double): Future[Product] = {
    cassandra.cql("INSERT INTO amazingbackend.products (id, updated, created, label, description, image, price) VALUES ( ?, ?, ?, ?, ?, ?, ? );")
      .on(id, created.toDate, created.toDate, label, description, image, price)
      .perform.flatMap(_ => read(id).map(_.get))
  }

  def delete(id: String): Future[ExecutionInfo] = {
    cassandra.cql("DELETE FROM amazingbackend.products WHERE id = ? ;").on(id).perform
  }

  def update(id :String, updated: DateTime, label: String, description: String, image: String, price: lang.Double): Future[Product] = {
    cassandra.cql("UPDATE amazingbackend.products SET updated = ?, label = ?, description = ?, image = ?, price = ? WHERE id = ? ;")
      .on(updated.toDate, label, description, image, price, id)
      .perform.flatMap{d =>
        Env.logger.debug(s" TRACE : ${d.getQueryTrace}")
        read(id).map(_.get)
      }
  }

  def updatePrice(id :String, updated: DateTime, price: lang.Double): Future[Product] = {
    cassandra.cql("UPDATE amazingbackend.products SET updated=?, price=? WHERE  = ?;")
      .on(updated.toDate, price, id)
      .perform.flatMap(_ => read(id).map(_.get))
  }

  def read(id: String) : Future[Option[Product]] = {
    cassandra.cql("SELECT * FROM amazingbackend.products WHERE id = ? ;").on(id).firstOf[Product]
  }

  def list(nb: Int, from: DateTime = new DateTime()): Future[List[Product]]  = {
    Env.logger.debug(s"SELECT * FROM amazingbackend.products ;")
    cassandra.cql(s"SELECT * FROM amazingbackend.products ;").allOf[Product]
  }

  def listPrices(ids: List[String]): Future[List[(String, Double)]] = {
    cassandra.cql(s"SELECT id, price FROM amazingbackend.products where id in ( ${ids.map(id => s"'$id'").mkString(",")} );").all
      .map(l => l.map{o => Env.logger.debug(s"Prices : $o") ; ( (o \ "id").as[String], (o \ "price").as[Double] )})
  }

}