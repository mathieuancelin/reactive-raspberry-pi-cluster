package com.amazing.store.cluster

import java.net._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}

import akka.cluster.Cluster
import com.typesafe.config.{Config, ConfigFactory}
import org.jgroups._
import play.api.libs.concurrent.Akka
import play.api.{Logger, Mode, Play}

import scala.collection.JavaConversions._
import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Random, Try}

class SeedConfig(conf: Config, channel: JChannel, address: String, port: Int) {
  private[this] val joined = new AtomicBoolean(false)
  private[this] val noMore = new AtomicBoolean(false)
  private[this] val clusterRef = new AtomicReference[Cluster]()
  private[this] val addresses = new ConcurrentLinkedQueue[akka.actor.Address]()
  private[this] val waitingFor = 2
  private[this] val p = Promise[Unit]()
  def config() = conf
  def shutdown() = channel.close()
  def joinCluster(cluster: Cluster): Future[Unit] = {
    joined.set(true)
    clusterRef.set(cluster)
    addresses.offer(akka.actor.Address("akka.tcp", "amazing-system", address, port))
    p.future
  }
  private[this] def joinIfReady() = {
    if (addresses.size() == waitingFor) {
      clusterRef.get().joinSeedNodes(scala.collection.immutable.Seq().++(addresses.toSeq))
      noMore.set(true)
      p.trySuccess(())
    }
  }
  def forceJoin(): Unit = {
    clusterRef.get().joinSeedNodes(scala.collection.immutable.Seq().++(addresses.toSeq))
    noMore.set(true)
    p.trySuccess(())
  }
  private[cluster] def newSeed(message: String) = {
    if (joined.get() && !noMore.get()) {
      message.split("\\:").toList match {
        case addr :: prt :: Nil => {
          addresses.offer(akka.actor.Address("akka.tcp", "amazing-system", addr, prt.toInt))
          joinIfReady()
        }
        case _ =>
      }
    }
  }
}

object SeedHelper {

  val defaultRemotePort = 2550

  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt (1000) + 7000)
  }

  def bootstrapSeed(configName: String)(implicit ec: ExecutionContext): Future[SeedConfig] = {
    val configBuilder = new StringBuilder()
    var config = Play.current.configuration.underlying.getConfig(configName)
    val fallback = Play.current.configuration.underlying.getConfig(configName)
    val address = InetAddress.getLocalHost.getHostAddress
    val port = Play.current.mode match {
      case Mode.Dev => Play.current.configuration.getInt("amazing-config.devremote").getOrElse(defaultRemotePort)
      case _ => freePort
    }
    configBuilder.append("enabled-transports=[\"akka.remote.netty.tcp\"]\n")
    configBuilder.append(s"akka.remote.netty.tcp.port=$port\n")
    configBuilder.append(s"akka.remote.netty.tcp.hostname=$address\n")
    config = ConfigFactory.parseString(configBuilder.toString()).withFallback(fallback)
    Logger("SeedHelper").debug(s"Akka remoting will be bound to akka.tcp://amazing-system@$address:$port")
    val channel = new JChannel()
    channel.connect("amazing-store")
    val seeds = new SeedConfig(config, channel, address, port)
    channel.setReceiver(new ReceiverAdapter() {
      override def receive(msg: Message): Unit = {
        val myself = channel.getAddress
        if (msg.getSrc != myself) {
          //Logger.debug(s"Received messages ${msg.getSrc.toString} => ${msg.getObject}")
          seeds.newSeed(msg.getObject.asInstanceOf[String])
        }
      }
    })
    def broadcastWhoIAm(duration: FiniteDuration)(implicit ec: ExecutionContext):Unit = {
      Akka.system(Play.current).scheduler.scheduleOnce(duration) {
        val msg = new Message(null, channel.getAddress, s"$address:$port")//s"akka.tcp://amazing-system@$address:$port")
        Try {
          channel.send(msg)
          broadcastWhoIAm(Duration(2, TimeUnit.SECONDS))(ec)
        }
      }
    }
    broadcastWhoIAm(Duration(1, TimeUnit.SECONDS))(play.api.libs.concurrent.Execution.Implicits.defaultContext)
    Future.successful(seeds)
  }
}
