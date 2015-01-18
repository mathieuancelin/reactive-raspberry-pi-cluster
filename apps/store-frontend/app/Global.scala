
import _root_.http.Feed
import akka.actor.{ActorRef, Props}
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.monitoring.{Metrics, ProxyActor}
import com.amazing.store.services.{Directory, FileService, ServiceRegistry}
import com.amazing.store.tools.Reference
import com.distributedstuff.services.api.Service
import config.Env
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.{Concurrent, Enumerator}
import play.api.libs.json.JsValue
import services.FrontendService

package object config {
  object Env {
    val logger = Logger("AMAZING-FRONTEND")
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
    val webBrowserActor = Reference.empty[ActorRef]()
  }
}

package object http {
  object Feed{
    val out = Reference.empty[Enumerator[JsValue]]()
    val channel = Reference.empty[Channel[JsValue]]()
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
    ServiceRegistry.init("FRONTEND", Seq(
      Service(name = Directory.FRONTEND_SERVICE_NAME, url = s"akka.tcp://distributed-services@${Env.hostname}:${Env.port}/user/${Directory.FRONTEND_SERVICE_NAME}")
    ))
    val system = ServiceRegistry.registry().actors()
    Metrics.init("store-frontend")(system)
    ServiceRegistry.registry().useMetrics(Metrics.registry())

    system.actorOf(ProxyActor.props(Props[FrontendService]), Directory.FRONTEND_SERVICE_NAME) // publication du service frontend

    val (out, channel) = Concurrent.broadcast[JsValue]
    Feed.out.set(out)
    Feed.channel.set(channel)
    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    Env.fileService.set(FileService(Env.cassandra()))
  }

  override def onRequestCompletion(request : play.api.mvc.RequestHeader) : scala.Unit = {
    super.onRequestCompletion(request)
    metrics.Messages.ping.close()
  }
}
