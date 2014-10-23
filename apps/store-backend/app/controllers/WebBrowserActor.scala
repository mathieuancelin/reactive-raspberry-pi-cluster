package controllers

import akka.actor.{Actor, ActorRef, Props}
import com.amazing.store.models.{OrderLine, Order}
import play.api.libs.json.{Writes, Json}
import actors.Actors

object WebBrowserActor {
  def props(out: ActorRef) = Props(classOf[WebBrowserActor], out)
}

class WebBrowserActor(out: ActorRef) extends Actor {


  override def preStart(): Unit = {
    Actors.websocket.set(self)
  }

  def tellToBrowser(msg: AnyRef) = out ! msg

  implicit val orderlineWrites = Json.writes[OrderLine]
  implicit val orderlineListWrites = Writes.list[OrderLine](orderlineWrites)
  implicit val orderWrites = Json.writes[Order]
  implicit val orderListWrites = Writes.list[Order]

  override def receive: Receive = {
    case o: Order =>
      tellToBrowser(Json.obj(
        "type" -> "NewOrder",
        "message" -> Json.toJson(o)))
  }
}
