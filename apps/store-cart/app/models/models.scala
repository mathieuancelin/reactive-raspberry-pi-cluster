package models

import java.util.UUID
import java.{lang, util}

import com.amazing.store.models.{Order, OrderLine}
import com.datastax.driver.core.ExecutionInfo
import com.datastax.driver.core.utils.UUIDs
import config.Env
import org.joda.time.DateTime
import play.api.data.validation.ValidationError
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, Future}
import scala.math.BigDecimal.RoundingMode

case class Cart(id: UUID, login: String, lines: List[CartLine])
case class CartLine(productId: String, quantity: Int)

object Cart {

  val cartlineRead : Reads[CartLine] = (
    (JsPath \ "key").read[String] and
    (JsPath \ "value").read[Int]
  )(CartLine.apply _)

  val cartlineWrites = Json.writes[CartLine]

  implicit val cartlineFmt: Format[CartLine] =
    Format(cartlineRead, cartlineWrites)

  implicit val cartlineListFmt = Format(Reads.list[CartLine] , Writes.list[CartLine])
  implicit val cartFmt = Json.format[Cart]

  def cassandra = Env.cassandra.get()

  def drop() = {
    Await.result(cassandra.cql("DROP KEYSPACE IF EXISTS amazing; ")
      .perform, 30 second)
    Env.logger.debug(s"KEYSPACE amazing DROPED")
  }

  def init() = {
    Env.logger.debug(s"CREATING KEYSPACE amazing IF NOT EXISTS")
    Await.result(cassandra.cql("CREATE KEYSPACE IF NOT EXISTS amazing WITH replication = {'class':'SimpleStrategy', 'replication_factor':1};")
      .perform, 30 second)

    Env.logger.debug(s"CREATING TABLE cart IF NOT EXISTS")
    Await.result(cassandra.cql(
        """CREATE TABLE IF NOT EXISTS amazing.cart (
        id uuid PRIMARY KEY,
        login text,
        lines map<text,int> ) WITH compression = { 'sstable_compression' : '' };"""
    ).perform, 30 second)

    Env.logger.debug(s"CREATING INDEX cart_login IF NOT EXISTS")
    Await.result(cassandra.cql("""CREATE INDEX IF NOT EXISTS cart_login ON amazing.cart (login);""")
      .perform, 30 second)
  }

  def countItemForCart(id: UUID): Future[Int] = {
    findCartForId(id).map {
      case None => 0
      case Some(cart) => cart.lines.foldRight(0)(_.quantity + _)
    }
  }

  def createCart(id: UUID, login : String): Future[Cart] = {
    cassandra.cql("INSERT INTO amazing.cart (id, login) VALUES ( ?, ? );").on(id, login).all.flatMap(_ => findCartForId(id).map(_.get))
  }

  def delete(id: UUID) : Future[ExecutionInfo] = {
    cassandra.cql("DELETE FROM amazing.cart WHERE id = ? ;").on(id).perform
  }

  def findCartForId(id: UUID): Future[Option[Cart]] = {
    cassandra.cql("SELECT * FROM amazing.cart WHERE id = ?;").on(id).firstOf[Cart]
  }

  def findCartsForLogin(login: String): Future[List[Cart]] = {
    cassandra.cql("SELECT * FROM amazing.cart WHERE login = ?;").on(login).allOf[Cart]
  }

  def reinitCarts(login: String): Future[ExecutionInfo] = {
    cassandra.cql("DELETE lines FROM amazing.cart WHERE login = ? ;").on(login).perform
  }

  def deleteProductFromCarts(productId: String): Future[Unit] = {
    cassandra.cql(s"DELETE lines['$productId'] FROM amazing.cart;").perform.map(_ => Unit)
  }


  def getQuantity(id:UUID, productId: String) : Future[Int] = {
    findCartForId(id).map {
      case None => 0
      case Some(cart) =>
        cart.lines.find(_.productId equals productId) match {
          case None => 0
          case Some(line) => line.quantity
      }
    }
  }

  def updateProductQuantity(id: UUID, productId: String, quantity: Int) : Future[Cart] = {
    if(quantity<=0) {
      cassandra.cql(s"DELETE lines['$productId'] FROM amazing.cart WHERE id = ?;").on(id).perform.flatMap(_ => this.findCartForId(id).map(_.get))
    } else {
      cassandra.cql(s"UPDATE amazing.cart SET lines['$productId'] = $quantity WHERE id = ?;").on(id).perform.flatMap(_ => this.findCartForId(id).map(_.get))
    }
  }

}


object OrderStore {

  private implicit val uuidReads: Reads[java.util.UUID] = UUIDJsonFormats.uuidReader()
  private implicit val uuidWrites: Writes[UUID] = Writes { uuid => JsString(uuid.toString) }

  val readLineFromDb = Json.reads[OrderLine]
  val orderlineReads :Reads[OrderLine] = new Reads[OrderLine]{
    override def reads(json: JsValue): JsResult[OrderLine] = { json match {
        case str: JsString =>
          val parsedJson: JsValue = Json.parse(str.value)
          val orderLine: OrderLine = parsedJson.as[OrderLine](readLineFromDb)
          JsSuccess(orderLine)
        case _ =>
          JsError(s"Error parsing $json")
      }
    }
  }

  implicit val orderlineWrites = Json.writes[OrderLine]
  implicit val orderlineListFmt = Format(Reads.list[OrderLine](orderlineReads) , Writes.list[OrderLine](orderlineWrites))
  implicit val orderFmt = Json.format[Order]

  def cassandra = Env.cassandra.get()

  def init() = {
    Await.result(cassandra.cql("CREATE KEYSPACE IF NOT EXISTS amazing WITH replication = {'class':'SimpleStrategy', 'replication_factor':1};")
      .perform, 30 second)
    Await.result(cassandra.cql("""CREATE TABLE IF NOT EXISTS amazing.orders (
        id uuid PRIMARY KEY,
        login text,
        lines list<text>,
        totalcost double,
        at timestamp
        ) WITH compression = { 'sstable_compression' : '' };
        """)
      .perform, 30 second)
    Await.result(cassandra.cql("""CREATE INDEX IF NOT EXISTS order_login ON amazing.orders (login);""")
      .perform, 30 second)
  }

  def save(order: Order) : Future[Order] = {
    val linesToString : String = order.lines.map (l => Json.stringify (Json.toJson (l) ) ).map(s => s"'$s'").mkString(",")
    val date : util.Date = order.at.toDate
    val request: String = s"INSERT INTO amazing.orders (id, login, lines, totalCost, at) VALUES ( ?, ?, [$linesToString], ?, ? );"
    val cost: lang.Double = order.totalcost
    cassandra.cql(request).on(order.id, order.login, cost, date).perform.map(_ => order)
  }

  def findAllFor(login: String): Future[List[Order]] = {
    cassandra.cql("SELECT * FROM amazing.orders WHERE login = ?;").on(login).allOf[Order]
  }

  def findAll(): Future[List[Order]] = {
    cassandra.cql("SELECT * FROM amazing.orders ;").allOf[Order]
  }

  def findOrderById(id: UUID): Future[Option[Order]] = {
    cassandra.cql("SELECT * FROM amazing.orders WHERE id = ?;").on(id).firstOf[Order]
  }

  def delete(id: UUID) = {
    cassandra.cql("DELETE FROM amazing.orders WHERE id = ? ;").on(id).perform
  }
}



object UUIDJsonFormats {

  def uuidReader(checkUuuidValidity: Boolean = false): Reads[java.util.UUID] = new Reads[java.util.UUID] {
    import java.util.UUID

import scala.util.Try
    def check(s: String)(u: UUID): Boolean = u != null && s == u.toString
    def parseUuid(s: String): Option[UUID] = {
      val uncheckedUuid = Try(UUID.fromString(s)).toOption

      if (checkUuuidValidity) {
        uncheckedUuid filter check(s)
      } else {
        uncheckedUuid
      }
    }

    def reads(json: JsValue) = json match {
      case JsString(s) =>
        parseUuid(s).map(JsSuccess(_)).getOrElse(JsError(Seq(JsPath() -> Seq(ValidationError("error.expected.uuid")))))
      case _ => JsError(Seq(JsPath() -> Seq(ValidationError("error.expected.uuid"))))
    }
  }

}