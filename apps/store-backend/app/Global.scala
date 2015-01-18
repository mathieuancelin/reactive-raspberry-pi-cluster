import java.util.concurrent.atomic.AtomicBoolean

import actors.Actors
import akka.actor.{ActorRef, Props}
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.monitoring.{Metrics, ProxyActor}
import com.amazing.store.persistence.processor.DistributedProcessor
import com.amazing.store.services.{Directory, FileService, ServiceRegistry}
import com.amazing.store.tools.Reference
import com.distributedstuff.services.api.Service
import config.Env
import models.Product
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import services._

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

    val hostname = Play.current.configuration.getString("services.boot.host").getOrElse("127.0.0.1")
    val port = Play.current.configuration.getInt("services.boot.port").getOrElse(2550)
  }
}

package object actors {

  object Actors {
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
    ServiceRegistry.init("BACKEND", Seq(
      Service(name = Directory.BACKEND_SERVICE_NAME, url = s"akka.tcp://distributed-services@${Env.hostname}:${Env.port}/user/${Directory.BACKEND_SERVICE_NAME}")
    ))
    val system = ServiceRegistry.registry().actors()
    Metrics.init("store-backend")(system)
    ServiceRegistry.registry().useMetrics(Metrics.registry())

    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    Product.init()

    Actors.productView.set(system.actorOf(ProxyActor.props(Props(classOf[ProductView]))))
    val processorRef = DistributedProcessor(system).buildRef("product", ProductProcessor.props())
    Actors.productProcessor.set(processorRef)
    system.actorOf(ProxyActor.props(Props(classOf[BackendService])), Directory.BACKEND_SERVICE_NAME)

    Env.fileService.set(FileService(Env.cassandra()))
  }

  override def onStop(app: Application): Unit = {
    Env.cassandra().stop()
  }
}
