
import actors.Actors
import akka.actor._
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.monitoring.{Metrics, ProxyActor}
import com.amazing.store.persistence.processor.DistributedProcessor
import com.amazing.store.services.{Directory, ServiceRegistry}
import com.amazing.store.tools.Reference
import com.distributedstuff.services.api.Service
import config.Env
import models.{UserService, UserStore}
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka
import services.{UserView, UsersProcessor}

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
    val hostname = Play.current.configuration.getString("services.boot.host").getOrElse("127.0.0.1")
    val port = Play.current.configuration.getInt("services.boot.port").getOrElse(2550)
  }
}

package object actors {

  object Actors {
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

    ServiceRegistry.init("IDENTITY", Seq(
      Service(name = Directory.USER_SERVICE_NAME, url = s"akka.tcp://distributed-services@${Env.hostname}:${Env.port}/user/${Directory.USER_SERVICE_NAME}")
    ))
    val system = ServiceRegistry.registry().actors()
    Metrics.init("store-identity")(system)
    ServiceRegistry.registry().useMetrics(Metrics.registry())

    system.actorOf(ProxyActor.props(Props[UserService]), Directory.USER_SERVICE_NAME)  // Init du service Users
    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    UserStore.init()
    val userView: ActorRef = system.actorOf(UserView.props)
    Actors.userView.set(userView)
    val buildRef = DistributedProcessor(system).buildRef("userprocessor", UsersProcessor.props(userView))
    Actors.userProcessor.set(buildRef)
  }

  override def onStop(app: Application): Unit = {
    Env.cassandra().stop()
  }
}

