package com.amazing.store.persistence.views

import akka.actor.{Actor, ActorRef}
import com.amazing.store.persistence.processor._
import com.amazing.store.persistence.views.View.Event
import play.api.Logger




case class Views(viewsRef: Seq[ActorRef])

object Views{
  def apply(funct: => Seq[ActorRef]) = new Views(funct)
}

object View {
  case class Event(msg: Any)
}

trait View extends Actor {

  val logger = Logger("Views")

  def receiveEvent: Receive
  def receiveQuery: Receive

  def receiveRecover: Receive

  override def receive: Actor.Receive = running()

  def running(): Receive = {
    case m @ RecoverStarting =>
      logger.trace(s"Recovery starting $m")
      context.become(recovering())
      self ! RecoverStarting
    case m @ Event(msg) if receiveEvent.isDefinedAt(msg) =>
      logger.trace(s"Event : $msg Sender ${sender()}")
      receiveEvent(msg)
    case m @ Event(msg) =>
      logger.trace(s"non handled Event : $msg Sender ${sender()}")
    case msg if receiveQuery.isDefinedAt(msg) => receiveQuery(msg)
      logger.trace(s"Query : $msg Sender ${sender()}")
      receiveQuery(msg)
    case msg =>
      logger.trace(s"non handled Query : $msg")
  }

  def standBy(): Receive = {
    case _ =>
  }

  def recovering(): Receive = {
    case f@RecoverStarting =>
      logger.trace(s"RecoverStarting")
      if (receiveRecover.isDefinedAt(f)){
        receiveRecover(f)
      }
    case f@RecoveringFinished =>
      if (receiveRecover.isDefinedAt(f)){
        receiveRecover(f)
      }
      logger.trace(s"RecoveringFinished - becoming running ")
      context.become(running())
    case m @ Event(msg) if receiveRecover.isDefinedAt(msg) =>
      logger.trace(s"Recovering view, Recover event : $msg")
      receiveRecover(msg)
    case msg  =>
      logger.trace(s"Recovering view, non handled recover event : $msg")
      //receiveRecover(msg)
  }
}


