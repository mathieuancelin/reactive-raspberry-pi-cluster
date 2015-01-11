package services

import java.util.UUID

import actors.Actors
import akka.actor._
import akka.pattern._
import akka.util.Timeout
import com.amazing.store
import com.amazing.store.messages._
import com.amazing.store.models.Order
import com.amazing.store.models.OrderLine
import com.amazing.store.monitoring.ProxyActor
import com.amazing.store.persistence.processor.{Recover, RecoverStarting, EventsourceProcessor}
import com.amazing.store.persistence.views
import com.amazing.store.persistence.views.Views
import com.datastax.driver.core.utils.UUIDs
import config.Env
import models.{OrderStore, Cart}
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, Future}
import scala.math.BigDecimal.RoundingMode

case class NoMoreRecover()
case class ErrorValidatingCart(login: String) extends Throwable

trait Command { def id(): UUID }
case class CreateCart(id: UUID, login: String, at: DateTime) extends Command
case class AddProductToCart(id: UUID, login: String, productId: String, quantity: Int, at: DateTime) extends Command
case class RemoveProductFromCart(id: UUID, login: String, productId: String, quantity: Int, at: DateTime) extends Command
case class UpdateProductQuantity(id: UUID, login: String, productId: String, quantity: Int, at: DateTime) extends Command
case class OrderCart(id: UUID, login: String, at: DateTime) extends Command
case class OrderCartWithPrices(idCart: UUID, id: UUID, login: String, lines: List[(String, Double, Int)], at: DateTime) extends Command

trait Event
case class CartCreated(id: UUID, login: String, at: DateTime) extends Event
case class ProductQuantityUpdated(id: UUID, login: String, productId: String, quantity: Int, at: DateTime) extends Event
case class CartOrderedItem(id: String, price: Double, quantity: Int) extends Event
case class CartOrdered(idCart: UUID, id: UUID, login: String, lines: List[CartOrderedItem], at: DateTime) extends Event
case class ProductDeleted(productId: String, at: DateTime) extends Event

trait Query
case class GetCartsForLogin(login: String) extends Query
case class GetOrder(id: UUID) extends Query
case class GetAllOrdersForLogin(login: String) extends Query
case class GetAllOrders() extends Query

object Event {
  implicit val cartCreated = Json.writes[CartCreated]
  implicit val productQtyUpdated = Json.writes[ProductQuantityUpdated]

  implicit val orderlineWrites = Json.writes[CartOrderedItem]
  implicit val orderlineListWrites = Writes.list[CartOrderedItem]
  implicit val cartOrdered = Json.writes[CartOrdered]
  implicit val productDeleted = Json.writes[ProductDeleted]
}

class RemoteCartService extends Actor {
  override def receive: Actor.Receive = {
    case GetAllOrdersRequest =>
      CartService.listOrders().map{o => Env.logger.debug(s"orders $o"); GetAllOrdersResponse(o)} pipeTo sender()
  }
}

object CartService {

  implicit val timeout = Timeout(30 second)

  def findCartsForLogin(login: String): Future[List[Cart]] = {
    (Actors.cartView() ? GetCartsForLogin(login)).mapTo[List[Cart]]
  }

  def getCartCount(login: String): Future[Option[Int]] = {
    (Actors.cartView() ? GetNumberOfItemsInCart(login)).mapTo[Option[Int]]
  }

  def createCart(idCart: UUID, login: String): Future[Unit] = {
    (Actors.cartProcessor() ? CreateCart(idCart, login, DateTime.now())).mapTo[Unit]
  }

  def addProducttToCart(idCart: UUID, login: String, idProduct: String, quantity: Int): Future[Unit] = {
    (Actors.cartProcessor() ? AddProductToCart(idCart, login, idProduct, quantity, DateTime.now())).mapTo[Unit]
  }

  def removeProductFromCart(idCart: UUID, login: String, idProduct: String, quantity: Int): Future[Unit] = {
    (Actors.cartProcessor() ? RemoveProductFromCart(idCart, login, idProduct, quantity, DateTime.now())).mapTo[Unit]
  }

  def orderCart(id: UUID, login: String): Future[Unit] = {
    (Actors.cartProcessor() ? OrderCart(id, login, DateTime.now())).mapTo[Unit]
  }

  def findOrder(id: String): Future[Option[Order]] = {
    (Actors.cartView() ? GetOrder(UUID.fromString(id))).mapTo[Option[Order]]
  }

  def findAllOrders(login: String): Future[List[Order]] = {
    (Actors.cartView() ? GetAllOrdersForLogin(login)).mapTo[List[Order]]
  }

  def listOrders(): Future[List[Order]] = {
    Env.logger.debug("listOrders")
    (Actors.cartView() ? services.GetAllOrders()).mapTo[List[Order]]
  }

  def recover() = {
    Actors.cartProcessor() ! Recover
  }

}

class CartView extends views.View {

  override def receiveEvent: Receive = {
    case e:Event =>
      Env.logger.debug(s"Receive event $e")
      updateState(e, recover = false)
  }

  override def receiveRecover: Receive = {
    case RecoverStarting =>
      Cart.drop()
      Cart.init()
      OrderStore.init()
    case e:Event => updateState(e, recover = true)
  }

  override def receiveQuery: Receive = {
    case q@GetCartsForLogin(login) =>
      Env.logger.debug(s"Query : $q")
      Cart.findCartsForLogin(login) pipeTo sender()
    case q@GetNumberOfItemsInCart(login) =>
      Env.logger.debug(s"Query : $q")
      Cart.findCartsForLogin(login).flatMap{
        case List() | Nil => Future.successful(None)
        case list => Cart.countItemForCart(list.head.id).map(Some(_))
      } pipeTo sender()
    case q@GetOrder(id: UUID) =>
      Env.logger.debug(s"Query : $q")
      OrderStore.findOrderById(id) pipeTo sender()
    case q@GetAllOrdersForLogin(login) =>
      Env.logger.debug(s"Query : $q")
      OrderStore.findAllFor(login) pipeTo sender()
    case q@GetAllOrders() =>
      Env.logger.debug(s"Query : $q")
      OrderStore.findAll() pipeTo sender()
    case q =>
      Env.logger.debug(s"Other Query : $q")
  }

  def updateState(event: Event, recover: Boolean ): Unit = {
    event match {
      case e @ CartCreated(id, login, at) =>
        asyncOrBlock(event, recover){
          Cart.createCart(id, login)
        } pipeTo sender()
      case e @ ProductQuantityUpdated(id, login, productId, quantity, at) =>
        asyncOrBlock(event, recover) {
          Cart.updateProductQuantity(id, productId, quantity)
        } pipeTo sender()
      case e @ CartOrdered(idCart, id, login, items, at) =>
        asyncOrBlock(event, recover) {
          val total = BigDecimal.valueOf(items.foldRight(0.0)(_.price + _)).setScale(2, RoundingMode.HALF_EVEN).toDouble
          val order = Order(id, login, items.map(l => OrderLine(l.id, l.price, l.quantity)), total, at)
          OrderStore.save(order)
            .map(_ => Cart.delete(idCart))
            .map(_ => store.services.BackendService.notifyNewOrder(order))
        } pipeTo sender()
      case e @ ProductDeleted(productId, at) =>
        asyncOrBlock(event, recover) {
          Cart.deleteProductFromCarts(productId)
        } pipeTo sender()
      case evt => Env.logger.debug(s"Unknow event $evt")
    }
  }

  def asyncOrBlock[T](event: Event, recover: Boolean)(f : Future[T]) : Future[Unit]= {
    Env.logger.debug(s"EVENT : $event")
    if(recover){
      Await.result(f, 30 second)
      Future.successful(Unit)
    } else {
      f.map{_ => tellToWebBrowser(event); Unit}
    }
  }

  def tellToWebBrowser(e: Event)= {
    Actors.webBrowser.asOption() match {
      case None =>
      case Some(ref) => ref ! e
    }
  }

}

object CartProcessor{
  def props() = ProxyActor.props(Props(classOf[CartProcessor]))
}
class CartProcessor extends EventsourceProcessor {

  override def persistentId: String = "cartprocessor"

  override def views: Views = Views {
    Seq(Actors.cartView())
  }

  override def receiveCommand: Receive = {
    case NoMoreRecover() => Env.canRecover.compareAndSet(true, false)
    case e @ CreateCart(id, login, at) =>
      Env.logger.debug(s"COMMAND $e")
      persist(CartCreated(id, login, at))
    case e @ AddProductToCart(id, login, productId, quantity, at) =>
      Env.logger.debug(s"COMMAND $e")
      Cart.getQuantity(id, productId).map{q =>
        UpdateProductQuantity(id, login, productId, quantity + q, at)
      } pipeTo self
    case e @ RemoveProductFromCart(id, login, productId, quantity, at) =>
      Env.logger.debug(s"COMMAND $e")
      Cart.getQuantity(id, productId).map{q =>
        UpdateProductQuantity(id, login, productId, q - quantity, at)
      } pipeTo self
    case e @ UpdateProductQuantity(id, login, productId, quantity, at) =>
      Env.logger.debug(s"COMMAND $e")
      persist(ProductQuantityUpdated(id, login, productId, quantity, at))
    case e @ OrderCart(id, login, at) =>
      val _sender = sender()
      Env.logger.debug(s"COMMAND $e")
      val prices: Future[List[(String, Double, Int)]] = resolvePrices(id, login, sender())
      prices.onFailure{case f => logger.error(s"Error resolving prices, sending msg to ${_sender}", f); _sender ! ErrorValidatingCart(login)}
      prices map { lines =>
        OrderCartWithPrices(id, UUIDs.timeBased, login, lines, at)
      } pipeTo self
    case e @ OrderCartWithPrices(idCart, idOrder, login, lines, at) =>
      Env.logger.debug(s"COMMAND $e")
      persist(CartOrdered(idCart, idOrder, login, lines.map(t => CartOrderedItem(t._1, t._2, t._3)), at))
    case e @ DeleteProduct(productId) =>
      Env.logger.debug(s"COMMAND $e")
      persist(ProductDeleted(productId, DateTime.now()))
  }

  private def resolvePrices(id: UUID, login: String, sender: ActorRef): Future[List[(String, Double, Int)]] = {
    Cart.findCartForId(id).flatMap { o =>
      o.map { cart =>
        Env.logger.debug(s"Requesting prices for product of cart $id")
        val pricesFromBackend: Future[List[(String, Double)]] = store.services.BackendService.listProductPrices(cart.lines.map(_.productId))
        pricesFromBackend.onFailure {
          case f =>
            Env.logger.error("BackendUnreachable", f)
            throw ErrorValidatingCart(login)
        }
        pricesFromBackend.map { prices =>
          Env.logger.debug(s"Prices from backend $prices")
          val pricesMap: Map[String, Double] = Map(prices: _ *)
          cart.lines.map { line =>
            val price: Double = pricesMap.get(line.productId) match {
              case None =>
                Env.logger.error(s"Unknow price for ${line.productId}")
                throw ErrorValidatingCart(login)
              case Some(value) => value
            }
            (line.productId, price, line.quantity)
          }
        }
      } getOrElse Future.successful(List())
    }
  }
}
