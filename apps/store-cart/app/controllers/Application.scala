package controllers

import java.util.UUID

import com.amazing.store.auth.AuthActions._
import com.datastax.driver.core.utils.UUIDs
import config.Env
import models.Cart
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsNumber, Json}
import play.api.mvc._
import services._
import models.Cart._
import models.OrderStore._

import scala.concurrent.Future

object Application extends Controller {

  def applicationIsUp() = Action {
    Ok("ok").withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def stats() = Action {
    Ok(Json.obj(
      "avgRate" -> metrics.Messages.avgRate,
      "avgTime" -> metrics.Messages.avgTime
    )).withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def recoverState = Action { request =>
    request.headers.get("authtoken").filter(_ == "q8YKPTJtvkXj18dWecnHIGlqVlnmLzXmLAUd7eQ4xGeIkiP5BuzMpR0AxkFtn3TW").foreach { _ =>
      CartService.recover()
    }
    Ok
  }

  def index = UserSecured(Env.identityLink) { user => r =>
    Future.successful(Ok(views.html.index(0, List(), user)))
  }

  def currentCartJson = UserSecuredJson() { user => r =>
    CartService.findCartsForLogin(user.login).flatMap{
      case Nil | List() =>
        val idCart: UUID = UUIDs.timeBased
        CartService
          .createCart(idCart, user.login)
          .flatMap(_ => Cart.findCartForId(idCart).map{c => toJson(c.get)})
      case cart :: tail =>
        Future.successful(toJson(cart))
    }.map(r => Ok(r).withHeaders(
      ("Access-Control-Allow-Credentials", "true"),
      ("Access-Control-Allow-Origin", "http://www.amazing.com")))
  }

  def toJson(cart: Cart) = {
    val count = cart.lines.foldLeft(0){ (c,line) => line.quantity+c}
    Json.obj("id" -> cart.id, "items" ->cart.lines ,"count"-> JsNumber(count))
  }

  def history = UserSecured(Env.identityLink) { user => r =>
    for {
      count <- CartService.getCartCount(user.login)
      orders <- CartService.findAllOrders(user.login)
    } yield Ok(views.html.history(orders, count.getOrElse(0), user))
  }

  def historyJson = UserSecuredJson() { user => r =>
    for {
      count <- CartService.getCartCount(user.login)
      orders <- CartService.findAllOrders(user.login)
    } yield Ok(Json.obj(
        "orders" -> orders,
        "count" -> JsNumber(BigDecimal.int2bigDecimal(count.getOrElse(0))))
      ).withHeaders(("Access-Control-Allow-Credentials", "true"),
        ("Access-Control-Allow-Origin", "http://www.amazing.com"))
  }

  def orderCart(id: String) = UserSecured(Env.identityLink) { user => r =>
    CartService.orderCart(UUID.fromString(id), user.login).map(_ => Redirect(routes.Application.index))
  }

  def orderCartJson(id: String) = UserSecuredJson() { user => r =>
    CartService.orderCart(UUID.fromString(id), user.login).map(_ => Ok(Json.obj()))
  }

  case class Item(id: String, quantity: Int)

  val itemForm = Form(
    mapping(
      "id" -> text,
      "quantity" -> number
    )(Item.apply)(Item.unapply)
  )

  def webSocket = UserSecuredWebSocket() { user => request =>
    WebBrowserActor.props(user)
  }

  def showOrder(id: String) = UserSecured(Env.identityLink) { user => r =>
    for {
      count <- CartService.getCartCount(user.login)
      order <- CartService.findOrder(id).map(_.map(o => Ok(views.html.order(o, count.getOrElse(0), user))).getOrElse(NotFound(s"Order $id not found")))
    } yield order
  }

  def showOrderJson(id: String) = UserSecuredJson() { user => r =>
    for {
      count <- CartService.getCartCount(user.login)
      order <- CartService.findOrder(id).map(_.fold(NotFound(Json.obj()))(o => Ok(Json.obj("order" -> o, "count" -> JsNumber(BigDecimal.int2bigDecimal(count.getOrElse(0)))))))
    } yield order
  }

  def retryWhenServiceDown(where: String) = Action {
    Ok(views.html.retry(where, 0))
  }
}