package com.amazing.store.monitoring

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.amazing.store.monitoring.Metrics._
import com.amazing.store.monitoring.MetricsName._
import play.api.Logger


object ProxyActor {

  def props(props: Props) = Props(classOf[ProxyActor], props)
}


class ProxyActor(props: Props) extends Actor with ActorLogging {

  val logger = Logger("ProxyActor")

  override def receive: Receive = proxy(context.actorOf(props))

  def proxy(actorRef: ActorRef): Receive = {
    case msg =>
      val message: String = s"New message $msg"
      logger.trace(message)
      log.debug(message)
      publishMark(akkaInnerMessageName(msg.getClass.getName))
      actorRef forward msg
  }

}
