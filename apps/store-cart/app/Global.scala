
import java.util.concurrent.atomic.AtomicBoolean

import actors.Actors
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.Cluster
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.cluster.{SeedConfig, SeedHelper}
import com.amazing.store.monitoring.{Metrics, MetricsActor, ProxyActor}
import com.amazing.store.persistence.processor.DistributedProcessor
import com.amazing.store.services.{Client, Directory}
import com.amazing.store.tools.Reference
import config.Env
import models.{OrderStore, Cart}
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import services.{RemoteCartService, CartProcessor, CartView}

import scala.util.Failure

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
  }
}

package object actors {

  object Actors {
    val system = Reference.empty[ActorSystem]()
    val cluster = Reference.empty[Cluster]()
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

    Actors.system.set(ActorSystem(Env.systemName, Play.current.configuration.underlying.getConfig(Env.configName)))
    Metrics.init("store-cart")(Actors.system())

    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    Cart.init()
    OrderStore.init()

    Client.system.set(Actors.system())  // Init du locator
    Actors.cluster.set(Cluster(Actors.system()))
    Client.cluster.set(Actors.cluster())  // Init du locator
    Client.init()  // Ecoute du cluster

    Actors.cartView.set(Actors.system().actorOf(ProxyActor.props(Props(classOf[CartView]))))
    val processorRef = DistributedProcessor(Actors.system()).buildRef("cart", CartProcessor.props())
    Actors.cartProcessor.set(processorRef)
    Actors.cartService.set(Actors.system().actorOf(ProxyActor.props(Props(classOf[RemoteCartService])), Directory.CART_SERVICE_NAME))


  }

  override def onStop(app: Application): Unit = {
    Env.cassandra().stop()
    Actors.system.foreach(_.shutdown())
  }
}
