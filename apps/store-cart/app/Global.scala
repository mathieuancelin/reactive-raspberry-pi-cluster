
import java.util.concurrent.atomic.AtomicBoolean

import actors.Actors
import akka.actor.{ActorRef, Props}
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.monitoring.{Metrics, ProxyActor}
import com.amazing.store.persistence.processor.DistributedProcessor
import com.amazing.store.services.{Directory, ServiceRegistry}
import com.amazing.store.tools.Reference
import com.distributedstuff.services.api.Service
import config.Env
import models.{Cart, OrderStore}
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka
import services.{CartProcessor, CartView, RemoteCartService}

package object config {
  object Env {
    val logger = Logger("AMAZING-CART")
    val systemName = "amazing-system"
    val configName = "amazing-config"
    val cartLink = Play.current.configuration.getString("amazing-config.links.cart").getOrElse("http://cart.amazing.com")
    val frontendLink = Play.current.configuration.getString("amazing-config.links.frontend").getOrElse("http://www.amazing.com")
    val backendLink = Play.current.configuration.getString("amazing-config.links.backend").getOrElse("http://backend.amazing.com")
    val identityLink = Play.current.configuration.getString("amazing-config.links.identity").getOrElse("http://identity.amazing.com")
    val cassandra = Reference.empty[CassandraDB]()
    val canRecover = new AtomicBoolean(true)

    val hostname = Play.current.configuration.getString("services.boot.host").getOrElse("127.0.0.1")
    val port = Play.current.configuration.getInt("services.boot.port").getOrElse(2550)
  }
}

package object actors {

  object Actors {
    val cartService = Reference.empty[ActorRef]()
    val webBrowser = Reference.empty[ActorRef]()
    val cartView = Reference.empty[ActorRef]()
    val cartProcessor = Reference.empty[ActorRef]()
  }
}

package object metrics {

  import com.codahale.metrics.MetricRegistry

  object Messages {
    val registry = Reference.of(new MetricRegistry)
    def ping = registry().timer("amazing.messages").time()
    def avgTime = registry().timer("amazing.messages").getSnapshot.getMean
    def avgRate = registry().timer("amazing.messages").getOneMinuteRate
  }
}

object Global extends GlobalSettings {

  override def onStart(app: Application): Unit = {
    if (Play.current.mode == Mode.Dev) {
      // in dev mode, we will have fixture scenarios
      Play.current.getExistingFile("/journal") match {
        case Some(file) =>
          file.listFiles().foreach { file =>
            Env.logger.debug(s"Deleting file ${file.getAbsolutePath}")
            file.delete()
          }
        case None =>
          Env.logger.debug(s"No file")
      }
    }

    Metrics.init("store-cart")(Akka.system)

    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    Cart.init()
    OrderStore.init()

    Actors.cartView.set(Akka.system.actorOf(ProxyActor.props(Props(classOf[CartView]))))
    val processorRef = DistributedProcessor(Akka.system).buildRef("cart", CartProcessor.props())
    Actors.cartProcessor.set(processorRef)
    Actors.cartService.set(Akka.system.actorOf(ProxyActor.props(Props(classOf[RemoteCartService])), Directory.CART_SERVICE_NAME))

    ServiceRegistry.init("CART", Metrics.registry(), Seq(
      Service(name = Directory.CART_SERVICE_NAME, url = s"akka.tcp://${Akka.system.name}@${Env.hostname}:${Env.port}/user/${Directory.CART_SERVICE_NAME}")
    ))

  }

  override def onStop(app: Application): Unit = {
    Env.cassandra().stop()
  }
}
