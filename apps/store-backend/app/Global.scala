import java.util.concurrent.atomic.AtomicBoolean

import actors.Actors
import akka.actor.{ActorRef, Props, ActorSystem}
import akka.cluster.Cluster
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.cluster.{SeedConfig, SeedHelper}
import com.amazing.store.monitoring.{Metrics, MetricsActor, ProxyActor}
import com.amazing.store.persistence.processor.DistributedProcessor
import com.amazing.store.services.{FileService, Directory, Client}
import com.amazing.store.tools.Reference
import config.Env
import models.Product
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import services._

import scala.util.Failure

package object config {
  object Env {
    val canRecover = new AtomicBoolean(true)
    val logger = Logger("AMAZING-BACKEND")
    val systemName = "amazing-system"
    val configName = "amazing-config"
    val cartLink = Play.current.configuration.getString("amazing-config.links.cart").getOrElse("http://cart.amazing.com")
    val frontendLink = Play.current.configuration.getString("amazing-config.links.frontend").getOrElse("http://www.amazing.com")
    val backendLink = Play.current.configuration.getString("amazing-config.links.backend").getOrElse("http://backend.amazing.com")
    val identityLink = Play.current.configuration.getString("amazing-config.links.identity").getOrElse("http://identity.amazing.com")

    val cassandra = Reference.empty[CassandraDB]()
    val fileService = Reference.empty[FileService]()
  }
}

package object actors {

  object Actors {
    val system = Reference.empty[ActorSystem]()
    val cluster = Reference.empty[Cluster]()

    val productProcessor = Reference.empty[ActorRef]()
    val productView = Reference.empty[ActorRef]()
    val websocket = Reference.empty[ActorRef]()
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
    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    Product.init()
    Actors.system.set(ActorSystem(Env.systemName, Play.current.configuration.underlying.getConfig(Env.configName)))
    Actors.cluster.set(Cluster(Actors.system()))
    Client.system.set(Actors.system())  // Init du locator
    Client.cluster.set(Actors.cluster())  // Init du locator
    Client.init()
    Actors.productView.set(Actors.system().actorOf(ProxyActor.props(Props(classOf[ProductView]))))
    val processorRef = DistributedProcessor(Actors.system()).buildRef("product", ProductProcessor.props())
    Actors.productProcessor.set(processorRef)
    Actors.system().actorOf(ProxyActor.props(Props(classOf[BackendService])), Directory.BACKEND_SERVICE_NAME)
    Metrics.init("store-backend")(Actors.system())
    Env.fileService.set(FileService(Env.cassandra()))
  }

  override def onStop(app: Application): Unit = {
    Env.cassandra().stop()
    Actors.system.foreach(_.shutdown())
  }
}
