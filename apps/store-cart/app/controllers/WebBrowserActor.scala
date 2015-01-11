package controllers

import java.util.UUID

import actors.Actors
import akka.actor.{Actor, ActorRef, Props}
import com.amazing.store.models.User
import com.amazing.store.monitoring.ProxyActor
import com.datastax.driver.core.utils.UUIDs
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import services.Event._
import services._
import play.api.libs.concurrent.Execution.Implicits.defaultContext


import scala.concurrent.Promise

object WebBrowserActor{
  def props(user: User)(out: ActorRef) = ProxyActor.props(Props(classOf[WebBrowserActor], out, user))
}

class WebBrowserActor(out: ActorRef, user: User) extends Actor {

  override def preStart(): Unit = {
    Actors.webBrowser.set(self)
  }

  def tellToBrowser(login:String, msg: AnyRef) = if(login equals user.login) out ! msg

  override def receive: Receive = {
    case o: CartCreated =>
      metrics.Messages.ping.close()
      config.Env.logger.debug(s"Getting msg from app : $o")
      tellToBrowser(o.login, Json.obj(
        "type" -> "CartCreated",
        "login" -> o.login,
        "message" -> Json.toJson(o)))
    case o: ProductQuantityUpdated =>
      metrics.Messages.ping.close()
      config.Env.logger.debug(s"Getting msg from app : $o")
      tellToBrowser(o.login, Json.obj(
      "type" -> "ProductQuantityUpdated",
      "login" -> o.login,
      "message" -> Json.toJson(o)))
    case o: CartOrdered =>
      metrics.Messages.ping.close()
      config.Env.logger.debug(s"Getting msg from app : $o")
      tellToBrowser(o.login, Json.obj(
      "type" -> "CartOrdered",
      "login" -> o.login,
      "message" -> Json.toJson(o)))
    case ErrorValidatingCart(login) =>
      metrics.Messages.ping.close()
      config.Env.logger.error(s"Error validating cart")
      tellToBrowser(login, Json.obj("type" -> "ErrorValidatingCart"))
    case js: JsValue =>
      metrics.Messages.ping.close()
      Logger.debug(s"getting msg $js")
      (js \ "type").asOpt[String] match {
        case Some("CreateCart") =>
          createCart()
        case Some("AddProductToCart") =>
          addProductToCart( js \ "message")
        case Some("RemoveProductFromCart") =>
          removeProductFromCart( js \ "message")
        case Some("OrderCart") =>
          orderCart( js \ "message")
        case _ =>
          config.Env.logger.debug(s"msg not handled : $js")
      }
  }

  def createCart() = {
    val idCart: UUID = UUIDs.timeBased
    val promise = Promise[Unit]()
    CartService.createCart(idCart, user.login)
  }

  def addProductToCart(value : JsValue) = {
    val idCart: UUID = UUID.fromString((value \ "idCart").as[String])
    val idProduct: String = (value \ "idProduct").as[String]
    val quantity: Int = (value \ "quantity").as[Int]
    CartService.addProducttToCart(idCart, user.login, idProduct, quantity)
  }

  def removeProductFromCart(value : JsValue) = {
    val idCart: UUID = UUID.fromString((value \ "idCart").as[String])
    val idProduct: String = (value \ "idProduct").as[String]
    val quantity: Int = (value \ "quantity").as[Int]
    CartService.removeProductFromCart(idCart, user.login, idProduct, quantity)
  }

  def orderCart(value : JsValue) = {
    val promise = Promise[Unit]()
    val idCart: UUID = UUID.fromString((value \ "idCart").as[String])
    Actors.cartProcessor() ! OrderCart(idCart, user.login, DateTime.now())
  }
}
