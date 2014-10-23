package com.amazing.store.persistence.journal

import akka.actor.ActorContext
import org.joda.time.DateTime
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}


abstract class AbstractJournal(context: ActorContext) {

}

abstract class Journal(context: ActorContext) {

  def writeMessages(messages: Seq[JournalMessage]):   Unit

  def replayMessages(persistentId: String, fromSequenceNum: Long, toSequenceNum: Long)(callback: (JournalMessage) => Unit): Future[Unit]

  def lastSequenceNum(persistentId: String): Long

  def nextSequenceNum(persistentId: String): Long

}

object Journal{
  def apply(context: ActorContext):Journal = {
    Try(context.system.settings.config.getString("journalclass")) match {
      case Failure(e) => new MemoryJournal(context)
      case Success(config) =>
        Class.forName(config).getConstructors()(0).newInstance(context).asInstanceOf[Journal]
    }
  }
}


class MemoryJournal(context: ActorContext) extends Journal(context) {

  private[this] val logger = Logger("MemoryJournal")
  private[this] var journalMessages: Seq[JournalMessage] = Seq[JournalMessage]()

  implicit val ec:ExecutionContext = context.dispatcher

  override def writeMessages(messages: Seq[JournalMessage]): Unit = {
    journalMessages ++= messages
    logger.trace(s"add messages $messages, current journal : $journalMessages")
  }

  override def replayMessages(persistentId: String, fromSequenceNum: Long, toSequenceNum: Long)(callback: (JournalMessage) => Unit): Future[Unit] = {
    Future {
      journalMessages.foreach{msg =>
        callback(msg)
      }
    }
  }

  override def lastSequenceNum(persistentId: String): Long = {
    journalMessages.reverse.head.sequenceNum
  }

  override def nextSequenceNum(persistentId: String): Long = {
    DateTime.now().getMillis
  }
}

case class JournalMessage(persistentId: String, sequenceNum: Long, payload: Any) extends Serializable


