package com.amazing.store.tools

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import java.util.concurrent.{ConcurrentHashMap, TimeUnit, Executors}
import akka.actor._
import akka.cluster.{Member, Cluster}
import akka.util.Timeout
import java.util.concurrent.atomic.AtomicLong
import scala.reflect.ClassTag
import collection.JavaConversions._
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import scala.Some
import akka.cluster.ClusterEvent.UnreachableMember
import play.api.Logger

private case class FutureDone()

trait FutureAwareActor extends Actor {

  private val internalEc = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(1))

  private def activeLoop: Receive = {
    case message => {
      context.become(waitingLoop)
      processNext(message)
    }
  }

  private def processNext(message: Any) = {
    message match {
      case msg if receiveAsync.isDefinedAt(msg) => {
        val ref = self
        receiveAsync(msg).onComplete {
          case _ => ref ! FutureDone()
        }(internalEc)
      }
      case _ =>
    }
  }

  private def dequeueAnProcessNext() = {
    Try(waitingMessages.dequeue()).toOption match {
      case None => context.become(activeLoop)
      case Some(message) => processNext(message)
    }
  }

  private val waitingMessages = collection.mutable.Queue[Any]()

  private def waitingLoop: Receive = {
    case FutureDone() => dequeueAnProcessNext()
    case message: AnyRef => waitingMessages.enqueue(message)
  }

  type ReceiveAsync = PartialFunction[Any, Future[_]]

  def receiveAsync: ReceiveAsync

  def receive = activeLoop
}