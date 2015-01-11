package services

import java.text.Normalizer
import java.text.Normalizer.Form

import actors.Actors
import akka.actor._
import akka.pattern._
import akka.util.Timeout
import com.amazing.store.messages.BackendService.{ListProducts, ListProductsPrices, NewOrder}
import com.amazing.store.monitoring.ProxyActor
import com.amazing.store.persistence.processor.{EventsourceProcessor, Recover}
import com.amazing.store.persistence.views.{View, Views}
import com.amazing.store.services.FrontendServiceClient
import config.Env
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, Future}



trait Command
case class CreateProduct(label: String, description: String, imageUrl: String, price: Double) extends Command
case class UpdateProduct(id: String, label: String, description: String, imageUrl: String, price: Double) extends Command
case class UpdatePrice(id: String, price: Double) extends Command

trait Event
case class ProductCreated(id: String, created: DateTime, label: String, description: String, imageUrl: String, price: Double) extends Event
case class ProductUpdated(id: String, updated: DateTime, label: String, description: String, imageUrl: String, price: Double) extends Event
case class PriceUpdated(id: String, updated: DateTime, price: Double) extends Event

trait Query
case class ListProductsQuery(nb: Int) extends Query
case class ListProductsPricesQuery(ids: List[String]) extends Query



class BackendService extends Actor{
  override def receive: Actor.Receive = {
    case ListProducts(nb) => ProductService.listProducts(nb) pipeTo sender()
    case ListProductsPrices(ids) => ProductService.listProductsPrices(ids) pipeTo sender()
    case NewOrder(order) => Actors.websocket() ! order
  }
}


object ProductService {

  implicit val timeout = Timeout(30 second)

  def listProducts(nb: Int): Future[List[models.Product]] = {
    (Actors.productView() ? ListProductsQuery(nb)).mapTo[List[models.Product]]
  }

  def listProductsPrices(ids: List[String]): Future[List[(String, Double)]] = {
    (Actors.productView() ? ListProductsPricesQuery(ids)).mapTo[List[(String, Double)]]
  }

  def createProduct(label: String, description: String, imageUrl: String, price: Double): Future[Unit] = {
    (Actors.productProcessor() ? CreateProduct(label, description, imageUrl, price)).mapTo[Unit]
  }
  def updateProduct(id: String, label: String, description: String, imageUrl: String, price: Double): Future[Unit] = {
    (Actors.productProcessor() ? UpdateProduct(id, label, description, imageUrl, price.toDouble)).mapTo[Unit]
  }

  def recover() = {
    Actors.productProcessor() ! Recover
  }

}


class ProductView extends View {

  override def receiveEvent: Receive = {
    case msg: Event => updateState(msg, block = false)
  }

  override def receiveRecover: Receive = {
    case e@ProductCreated(id, created, label, description, imageUrl, price) =>
      Await.result(models.Product.delete(id), 30 second)
      updateState(e, block = true)
    case msg: Event => updateState(msg, block = true)
  }

  override def receiveQuery: Receive = {
    case ListProductsQuery(nb) => models.Product.list(nb) pipeTo sender()
    case msg@ListProductsPricesQuery(ids) =>
      Env.logger.debug(s"List prices : $msg")
      models.Product.listPrices(ids) pipeTo sender()
    case q: Query =>
  }

  def updateState(event: Event, block: Boolean): Unit = {
    Env.logger.debug(s"Event $event")
    event match {
      case e@ProductCreated(id, created, label, description, imageUrl, price) =>
        asyncOrBlock(e, block) {
          models.Product.create(id, created, label, description, imageUrl, price)
            .map(p => FrontendServiceClient.createProduct(id.toString, p.label, p.description, p.image, p.price))
        } pipeTo sender()
      case e@ProductUpdated(id, updated, label, description, imageUrl, price) =>
        asyncOrBlock(e, block) {
          models.Product.update(id, updated, label, description, imageUrl, price)
            .map(p => FrontendServiceClient.updateProduct(id.toString, p.label, p.description, p.image, p.price))
        } pipeTo sender()
      case e@PriceUpdated(id, updated, price) =>
        asyncOrBlock(e, block) {
          models.Product.updatePrice(id, updated, price)
            .map(p => FrontendServiceClient.updatePrice(id.toString, price))
        } pipeTo sender()
    }
  }

  def asyncOrBlock[T](event: Event, block: Boolean)(f: Future[T]): Future[Unit] = {
    Env.logger.debug(s"$event")
    if(block){
      Await.result(f, 30 second)
      Future.successful(Unit)
    } else {
      f.map { _ => Unit}
    }
  }
}

object ProductProcessor {
  def props(): Props = ProxyActor.props(Props(classOf[ProductProcessor]))
}

class ProductProcessor extends EventsourceProcessor {

  override def persistentId: String = "product-processor"

  override def views: Views = Views {
    Seq(Actors.productView())
  }

  override def receiveCommand: Receive = {
    case c @ CreateProduct(label, description, imageUrl, price) => persist(ProductCreated(generateId(label), new DateTime(), label, description, imageUrl, price))
    case c @ UpdateProduct(id, label, description, imageUrl, price) => persist(ProductUpdated(id, new DateTime(), label, description, imageUrl, price))
    case c @ UpdatePrice(id, price) => persist(PriceUpdated(id, new DateTime(), price))
  }

  def generateId(text: String): String = {
    text.toLowerCase
      .map(letter => Normalizer.normalize(letter.toString , Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "")).mkString
      .replaceAll("\\W", "-")
  }

}



