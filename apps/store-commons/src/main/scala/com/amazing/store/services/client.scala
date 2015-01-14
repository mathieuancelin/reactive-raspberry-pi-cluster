package com.amazing.store.services

import java.util
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import akka.actor._
import akka.cluster.ClusterEvent._
import com.amazing.store.monitoring.Metrics.publishMark
import com.amazing.store.monitoring.MetricsImplicit._
import com.amazing.store.monitoring.MetricsName._


//import akka.cluster.{Cluster, Member}
import akka.cluster.{Member, Cluster}
import akka.util.Timeout
import com.amazing.store.tools.Reference
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

class ClusterStateClient extends Actor {

  val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember], classOf[ReachableMember])
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case MemberUp(member) => {
      Client.displayState()
    }
    case UnreachableMember(member) => {
      Logger("CLIENTS_WATCHER").debug(s"$member is unreachable")
      Client.unreachable.add(member)
      Logger("CLIENTS_WATCHER").debug(s"blacklist : ${Client.unreachable}")
      Client.displayState()
    }
    case ReachableMember(member) => {
      Logger("CLIENTS_WATCHER").debug(s"$member is reachable again")
      Client.unreachable.remove(member)
      Logger("CLIENTS_WATCHER").debug(s"blacklist : ${Client.unreachable}")
      Client.displayState()
    }
    case MemberRemoved(member, previousStatus) => {
      Client.displayState()
    }
    case _: MemberEvent =>
  }
}

//case class Member(address: Address, roles: Seq[String], status: String) {
//  def getRoles: Seq[String] = roles
//}

object Client {

  private[this] val timeout: Timeout = Timeout(5, TimeUnit.SECONDS)

  val unreachable = Collections.synchronizedSet(new util.HashSet[Member]())

  //def members(): Seq[Member] = {
  // Seq(
  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.51", 2550), Seq("IDENTITY"), "UP"),
  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.52", 2553), Seq("IDENTITY"), "UP"),
  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.57", 2558), Seq("IDENTITY"), "UP"),

  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.52", 2553), Seq("FRONTEND"), "UP"),
  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.56", 2551), Seq("FRONTEND"), "UP"),

  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.53", 2553), Seq("BACKEND"), "UP"),
  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.57", 2557), Seq("BACKEND"), "UP"),

  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.53", 2552), Seq("CART"), "UP"),
  //   Member(Address("akka.tcp", "amazing-system", "192.168.1.57", 2552), Seq("CART"), "UP")
  // )
  //}

  def members(): Seq[Member] = cluster().state.members.toSeq.filterNot(unreachable.contains)

  private[services] def displayState() = {
    Logger("CLIENTS_WATCHER").debug(s"----------------------------------------------------------------------------")
    Logger("CLIENTS_WATCHER").debug(s"Cluster members are : ")
    members().foreach { member =>
      Logger("CLIENTS_WATCHER").debug(s"==> ${member.address} :: ${member.getRoles} => ${member.status}")
    }
    Logger("CLIENTS_WATCHER").debug(s"----------------------------------------------------------------------------")
  }

  def init() = system().actorOf(Props[ClusterStateClient])

  val system = Reference.empty[ActorSystem]()
  val cluster = Reference.empty[Cluster]()

  def ref(name: String): Client = new Client(name, system(), timeout, Some(cluster()), Set[String](), 5)
  def apply() = new ClientBuilder(system(), timeout, Some(cluster()), Set[String](), 5)
  private[services] val next = new AtomicLong(0)
}

class ClientBuilder(system: ActorSystem, timeout: Timeout, cluster: Option[Cluster], roles: Set[String], retry: Int) {
  def withActorSystem(system: ActorSystem) = new ClientBuilder(system, timeout, cluster, roles, retry)
  def withCluster(cluster: Cluster) = new ClientBuilder(system, timeout, Some(cluster), roles, retry)
  def withNoCluster() = new ClientBuilder(system, timeout, None, roles, retry)
  def withTimeout(timeout: Timeout) = new ClientBuilder(system, timeout, cluster, roles, retry)
  def withRole(role: String) = new ClientBuilder(system, timeout, cluster, Set(role), retry)
  def withRoles(roles: Set[String]) = new ClientBuilder(system, timeout, cluster, roles, retry)
  def retry(r: Int) = new ClientBuilder(system, timeout, cluster, roles, r)
  def ref(name: String): Client = new Client(name, system, timeout, cluster, roles, retry)
}

class Client(name: String, system: ActorSystem, timeout: Timeout, cluster: Option[Cluster] = None, roles: Set[String] = Set[String](), retryTimes: Int) {
  import scala.collection.JavaConversions._

  private[this] def retryPromise[T](times: Int, promise: Promise[T], failure: Option[Throwable], f: => Future[T], ec: ExecutionContext): Unit = {
    (times, failure) match {
      case (0, Some(e)) => promise.tryFailure(e)
      case (0, None) => promise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      case (i, _) => f.onComplete {
        case Success(t) => promise.trySuccess(t)
        case Failure(e) => retryPromise[T](times - 1, promise, Some(e), f, ec)
      }(ec)
    }
  }

  private[this] def retry[T](times: Int)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    retryPromise[T](times, promise, None, f, ec)
    promise.future
  }

  private[this] def next(membersListSize: Int): Int = {
    (Client.next.getAndIncrement % (if (membersListSize > 0) membersListSize else 1)).toInt
  }

  private[this] def next(members: Seq[Member]): Option[Member] = Try(members(next(members.size))).toOption

  private[this] def roleMatches(member: Member): Boolean = {
    //println(s"[CLIENT_DEBUG] search : $roles, member : ${member.getRoles.toSet}")
    if (roles.isEmpty) true
    else roles.intersect(member.getRoles.toSet).nonEmpty
  }
  private[this] def meIncluded(member: Member): Boolean = true
  private[this] def meNotIncluded(member: Member): Boolean = member.address != cluster.get.selfAddress

  def !(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = tell(message, sender)

  def tell(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
    Logger("AKKA-LB-CLIENTS").debug(s"Calling service '/user/$name' with '$message' ")
    cluster.map { c =>
      next(Client.members().filter(roleMatches)).foreach { member =>
        Logger("AKKA-LB-CLIENTS").debug(s"Calling service '/user/$name' with '$message' on '${member.address}'")
        system.actorSelection(RootActorPath(member.address) / "user" / name).tell(message, sender)
      }
    } getOrElse system.actorSelection("/user/" + name).tell(message, sender)
    Future.successful(())
  }

  def ask[T](message: Any)(implicit timeout: Timeout = timeout, tag: ClassTag[T]): Future[Option[T]] = {
    implicit val ec = system.dispatcher

    cluster.map {  c =>
      retry(retryTimes) {
        next(Client.members().filter(roleMatches)).map { member =>
          Logger("AKKA-LB-CLIENTS").debug(s"Calling service '/user/$name' with '$message' on '${member.address}'")
            val selection = system.actorSelection(RootActorPath(member.address) / "user" / name)
            publishMark(akkaOuterMessageName(message.getClass.getName))
            akka.pattern.ask(selection, message)(timeout).mapTo[T](tag).map(Option(_))
        } getOrElse Future.successful(None)
      }
    } getOrElse {
      retry(retryTimes) {
        val selection = system.actorSelection("/user/" + name)
        publishMark(akkaOuterMessageName(message.getClass.getName))
        akka.pattern.ask(selection, message)(timeout).mapTo[T](tag).map(Option(_))
      }
    }.withTimer(s"${message.getClass.getName}")
  }

  def !!(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = broadcast(message, sender)

  def broadcast(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
    if (cluster.isEmpty) tell(message, sender)
    else cluster.map { c =>
      Client.members().filter(roleMatches).filter(meNotIncluded).foreach { member =>
        Logger("AKKA-LB-CLIENTS").debug(s"Calling service '/user/$name' with '$message' on '${member.address}'")
        publishMark(akkaOuterMessageName(message.getClass.getName))
        system.actorSelection(RootActorPath(member.address) / name).tell(message, sender)
      }
    }
    Future.successful(())
  }

  def !!!(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = broadcastAll(message, sender)

  def broadcastAll(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
    if (cluster.isEmpty) tell(message, sender)
    else cluster.map { c =>
      Client.members().filter(roleMatches).filter(meIncluded).foreach { member =>
        Logger("AKKA-LB-CLIENTS").debug(s"Calling service '/user/$name' with '$message' on '${member.address}'")
        publishMark(akkaOuterMessageName(message.getClass.getName))
        system.actorSelection(RootActorPath(member.address) / name).tell(message, sender)
      }
    }
    Future.successful(())
  }

  def iAmAlone(): Boolean = {
    if (cluster.isEmpty) true
    else cluster.map{c =>
      Client.members().filter(roleMatches).filter(meNotIncluded).isEmpty
    }.getOrElse(true)
  }

  //case class Status(role: String, status: String, memberUp: Integer)
  //case class Green(override val role: String, members: Integer) extends Status(role, "green", members)
  //case class Yellow(override val role: String) extends Status(role, "yellow", 1)
  //case class Red(override val role: String) extends Status(role, "red", 0)
//
  //def status() = Client.members().flatMap(m => m.getRoles).groupBy(_).mapValues(_.size).toSeq.map {
  //  case (role, 0) => Red(role)
  //  case (role, 1) => Yellow(role)
  //  case (role, nb) => Green(role, nb)
  //}
}