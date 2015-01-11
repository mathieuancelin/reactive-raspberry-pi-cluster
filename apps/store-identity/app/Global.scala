
import actors.Actors
import akka.actor._
import akka.cluster.Cluster
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.cluster.{SeedConfig, SeedHelper}
import com.amazing.store.monitoring.{MetricsActor, ProxyActor}
import com.amazing.store.persistence.processor.DistributedProcessor
import com.amazing.store.services.{Client, Directory}
import com.amazing.store.tools.Reference
import config.Env
import models.{UserStore, UserService}
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import services.{UsersProcessor, UserView}

import scala.util.Failure

package object config {
  object Env {
    val logger = Logger("AMAZING-IDENTITY")
    val systemName = "amazing-system"
    val configName = "amazing-config"
    val cartLink = Play.current.configuration.getString("amazing-config.links.cart").getOrElse("http://cart.amazing.com")
    val frontendLink = Play.current.configuration.getString("amazing-config.links.frontend").getOrElse("http://www.amazing.com")
    val backendLink = Play.current.configuration.getString("amazing-config.links.backend").getOrElse("http://backend.amazing.com")
    val identityLink = Play.current.configuration.getString("amazing-config.links.identity").getOrElse("http://identity.amazing.com")
    val cassandra = Reference.empty[CassandraDB]()
  }
}

package object actors {

  object Actors {
    val system = Reference.empty[ActorSystem]()
    val cluster = Reference.empty[Cluster]()
    val userView = Reference.empty[ActorRef]()
    val userProcessor = Reference.empty[ActorRef]()
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
    Actors.system.set(ActorSystem(Env.systemName, Play.current.configuration.underlying.getConfig(Env.configName)))
    Actors.cluster.set(Cluster(Actors.system()))
    Client.system.set(Actors.system())  // Init du locator
    Client.cluster.set(Actors.cluster())  // Init du locator
    Client.init() // Ecoute du cluster
    Actors.system().actorOf(ProxyActor.props(Props[UserService]), Directory.USER_SERVICE_NAME)  // Init du service Users

    Actors.system().actorOf(MetricsActor.props())

    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    UserStore.init()
    val userView: ActorRef = Actors.system().actorOf(UserView.props)
    Actors.userView.set(userView)
    val buildRef = DistributedProcessor(Actors.system()).buildRef("userprocessor", UsersProcessor.props(userView))
    Actors.userProcessor.set(buildRef)
  }

  override def onStop(app: Application): Unit = {
    Env.cassandra().stop()
    Actors.system.foreach(_.shutdown())
  }
}

