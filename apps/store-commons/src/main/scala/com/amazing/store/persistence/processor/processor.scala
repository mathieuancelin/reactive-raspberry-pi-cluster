package com.amazing.store.persistence.processor

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member}
import akka.pattern._
import akka.util.Timeout
import com.amazing.store.monitoring.ProxyActor
import org.joda.time.DateTime
import com.amazing.store.persistence.journal.{Journal, JournalMessage}
import com.amazing.store.persistence.views._
import play.api.Logger

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}



trait RecoverMsg
case object Recover extends RecoverMsg
case object PrepareRecover extends RecoverMsg
case object RunRecover extends RecoverMsg
case object RecoverStarting extends RecoverMsg
case object RecoveringFinished extends RecoverMsg
trait NegociationMsg
case class RequestRecover(address: ActorRef) extends NegociationMsg
case class NoProcessorAvailable(address: ActorSelection) extends NegociationMsg
case class AcceptRecover(address: ActorRef) extends NegociationMsg
case object CancelRecover extends NegociationMsg


trait SequenceGenerator {
  def nextSequence(persistentId: String): Long
}

object SequenceGenerator {
  def apply() = new SequenceGenerator {
    override def nextSequence(persistentId: String): Long = DateTime.now.getMillis
  }
}

trait EventsourceProcessor extends Actor {

  val logger = Logger("EventsourceProcessor")

  private val journal: Journal = Journal(context)
  private var bufferedMessages: Seq[JournalMessage] = Seq[JournalMessage]()


  override def receive: Receive = running()

  def receiveCommand: Receive
  def receiveRecover: Receive = {
    case _ =>
  }
  def persistentId: String
  def views: Views

  def persist[A](event: A) = {
    logger.trace(s"Going to persist event $event ")
    journal.writeMessages(Seq(JournalMessage(this.persistentId, journal.nextSequenceNum(this.persistentId), event)))
    tellToViews(View.Event(event))
  }

  private[this] def running(): Receive = {
    case Recover =>
      logger.debug("RUNNING : CHANGING STATE : RECOVERING")
      context.become(recovering(sender()))
      self ! Recover
    case msg if receiveCommand.isDefinedAt(msg) =>
      logger.trace(s"RUNNING : RECEIVING COMMAND ::: $msg")
      receiveCommand(msg)
  }

  private[this] def recovering(theSender: ActorRef): Receive = {
    case Recover =>
      tellToViews(RecoverStarting)
      self ! RunRecover
    case RunRecover =>
      implicit val ec: ExecutionContext = context.dispatcher
      logger.debug("RUN RECOVER")
      val _self = self
      val lastNum: Long = journal.lastSequenceNum(persistentId)
      logger.trace(s"Last sequence num $lastNum")
      journal.replayMessages(persistentId, 0, lastNum){msg =>
        _self ! msg
      }.map(_ => RecoveringFinished) pipeTo self
    case RecoveringFinished =>
      logger.trace(s"RECOVERING : Finished processing buffured msg  $bufferedMessages")
      journal.writeMessages(bufferedMessages)
      bufferedMessages.foreach(msg => tellToViews(View.Event(msg.payload)))
      tellToViews(RecoveringFinished)
      logger.debug(s"RECOVERING : CHANGING STATE : RUNNING")
      context.become(running())
      theSender ! RecoveringFinished
    case journal: JournalMessage =>
      logger.trace(s"RECOVERING : Processing journal msg  $journal")
      val msg = journal.payload
      if ( receiveRecover.isDefinedAt(msg) ) {
        receiveRecover(msg)
      }
      tellToViews(View.Event(msg))
    case other: Any =>
      logger.trace(s"RECOVERING : Buffering msg  $other")
      bufferedMessages :+= JournalMessage(this.persistentId, journal.nextSequenceNum(this.persistentId), other)
  }

  private[this] def tellToViews(msg: Any) = {
    views.viewsRef.foreach{view =>
      logger.trace(s"forwarding event $msg to view $view")
      view.forward(msg)
    }
  }

}


class DistributedProcessor(system: ActorSystem) {
  def buildRef(name: String, props: Props): ActorRef =  {
    system.actorOf(ProxyActor.props(Props(classOf[EventSourceProcessorProxy], props)), name)
  }
}
object DistributedProcessor {
  def apply(system: ActorSystem) = new DistributedProcessor(system)
}

class EventSourceProcessorProxy(props: Props) extends Actor {

  implicit val ec:ExecutionContext = context.system.dispatcher
  val logger = Logger("EventSourceProcessorProxy")
  val cluster = Cluster(context.system)
  val name = self.path.name
  var processor:Option[ActorRef] = None
  var remoteProcessor: Map[Member, ActorRef] = Map[Member, ActorRef]()

  override def preStart(): Unit = {
    createProcessorRef()
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def receive: Actor.Receive = running

  private[this] def createProcessorRef() = {
    val proc: ActorRef = context.actorOf(props)
    logger.trace(s"CREATING PROCESSOR self ${self.path} processor : $proc")
    context.watch(proc)
    processor = Some(proc)
  }

  private[this] def handleProcessorCrash: Actor.Receive = {
    case Terminated(ref) if ref == processor.get =>
      logger.trace("PROCESSOR CRASHED")
      createProcessorRef()
  }

  private[this] def running: Actor.Receive = {
    case MemberUp(member) =>
    case UnreachableMember(member) =>
    case MemberRemoved(member, previousStatus) =>
    case _: MemberEvent =>
    case RequestRecover(addr) =>
      logger.debug(s"RUNNING : Recover requested by ${sender()}, $addr")
      sender() ! AcceptRecover(self)
      logger.debug(s"RUNNING : CHANGING STATE : REMOTE RECOVER")
      context.become(remoteRecover(sender()))
    case m @ Recover =>
      logger.debug(s"RUNNING : Recover requested")
      context.become(prepareRecover)
      logger.debug(s"RUNNING : CHANGING STATE : PREPARE RECOVER")
      self ! PrepareRecover
    case msg if handleProcessorCrash.isDefinedAt(msg) => handleProcessorCrash(msg)
    case msg =>
      logger.trace(s"RUNNING : forwarding msg $msg")
      processor.get forward msg
  }

  private[this] def prepareRecover: Actor.Receive = {
    case PrepareRecover =>
      logger.debug(s"PREPARE RECOVER : PREPARE RECOVER, members are ${cluster.state.members}")
      val _self = self
      Future.sequence(cluster.state.members.filter(meNotIncluded).map(requestRecover)).onComplete{
        case Success(l) => l.find(e => e equals CancelRecover) match {
            case Some(m) =>
              logger.trace(s"PREPARE RECOVER : canceling recover")
              _self ! CancelRecover
            case None =>
              logger.trace(s"PREPARE RECOVER : run recover, members are $remoteProcessor")
              _self ! RunRecover
          }
        case Failure(f) =>
          logger.trace(s"PREPARE RECOVER : FAILURE : canceling recover", f)
          _self ! CancelRecover
      }
    case RunRecover =>
      logger.debug(s"PREPARE RECOVER : CHANGING STATE : RUN RECOVER")
      context.become(recovering)
      self ! RunRecover
    case CancelRecover =>
      logger.debug(s"PREPARE RECOVER : Requesting cancelation to members")
      broadcast(CancelRecover)
      logger.debug(s"PREPARE RECOVER : CHANGING STATE : RUNNING")
      context.become(running)
    case msg if handleNetwork.isDefinedAt(msg) => handleNetwork(msg)
    case msg if handleProcessorCrash.isDefinedAt(msg) => handleProcessorCrash(msg)
  }

  private[this] def recovering: Actor.Receive = {
    case RequestRecover =>
      logger.debug(s"RECOVERING : ${sender()} request recover, sending cancel message")
      sender() ! CancelRecover
    case RunRecover =>
      logger.debug(s"RECOVERING : START RECOVER ON PROCESSOR")
      processor.get ! Recover
    case RecoveringFinished =>
      logger.debug(s"RECOVERING : PROCESSOR HAS FINISHED RECOVER")
      broadcast(RecoveringFinished)
      context.become(running)
    case msg if handleNetwork.isDefinedAt(msg) => handleNetwork(msg)
    case msg if handleProcessorCrash.isDefinedAt(msg) => handleProcessorCrash(msg)
  }

  private[this] def remoteRecover(address: ActorRef): Actor.Receive = {
    case RecoveringFinished =>
      logger.debug(s"REMOTE RECOVER : CHANGING STATE : RUNNING")
      context.become(running)
    case CancelRecover =>
      logger.debug(s"REMOTE RECOVER : CHANGING STATE : RUNNING")
      context.become(running)
    case msg if handleProcessorCrash.isDefinedAt(msg) => handleProcessorCrash(msg)
    case msg => address forward msg
  }

  private[this] def handleNetwork: Actor.Receive = {
    case MemberUp(member) =>
      logger.trace(s"new member $member => request recover")
      requestRecover(member)
    case UnreachableMember(member) =>
    case MemberRemoved(member, previousStatus) =>
      logger.trace(s"member removed, $member")
      remoteProcessor -= member
    case _: MemberEvent =>
  }

  private[this] def broadcast(msg: Any)= {
    cluster.state.members.filter(meNotIncluded).foreach{m=>
      remoteAddress(m) ! msg
    }
  }

  private[this] def requestRecover(member: Member) : Future[NegociationMsg] = {
    val promise = Promise[NegociationMsg]()
    implicit val timeout = Timeout(30 seconds)
    val address: ActorSelection = remoteAddress(member)
    logger.trace(s"requesting recover to $address")
    val future: Future[Any] = (address ? RequestRecover(self)).mapTo[Any]
    future.onSuccess{
      case AcceptRecover(remoteAddress) =>
        logger.trace(s"recover accepted by $remoteAddress")
        remoteProcessor += member -> remoteAddress
        promise.success(AcceptRecover(remoteAddress))
      case CancelRecover =>
        logger.trace(s"cancel request")
        promise.success(CancelRecover)
      case msg =>
        logger.trace(s"$msg")
        promise.success(NoProcessorAvailable(address))
    }
    future.onFailure{
      case e: AskTimeoutException =>
        logger.trace(s"Timeout while requesting recover for $address")
        promise.success(NoProcessorAvailable(address))
      case e =>
        promise.failure(e)

    }
    promise.future
  }

  private[this] def remoteAddress(member: Member): ActorSelection = context.actorSelection(RootActorPath(member.address) / "user" / name)

  private[this] def meNotIncluded(member: Member): Boolean = member.address != cluster.selfAddress
}


