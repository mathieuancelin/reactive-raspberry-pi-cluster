
import actors.Actors
import akka.actor.{ActorRef, Props, ActorSystem}
import akka.cluster.Cluster
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.cluster.{SeedConfig, SeedHelper}
import com.amazing.store.services.{FileService, Directory, Client}
import com.amazing.store.tools.Reference
import config.Env
import _root_.http.Feed
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.iteratee.{Enumerator, Concurrent}
import play.api.libs.json.JsValue
import play.api._
import services.FrontendService
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.Failure

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
  }
}

package object actors {

  object Actors {
    val system = Reference.empty[ActorSystem]()
    val cluster = Reference.empty[Cluster]()
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
    Actors.system.set(ActorSystem(Env.systemName, Play.current.configuration.underlying.getConfig(Env.configName)))
    Client.system.set(Actors.system())  // Init du locator
    Actors.cluster.set(Cluster(Actors.system()))
    Client.cluster.set(Actors.cluster())  // Init du locator
    Client.init() // Ecoute du cluster
    Actors.system().actorOf(Props[FrontendService], Directory.FRONTEND_SERVICE_NAME) // publication du service frontend
    val (out, channel) = Concurrent.broadcast[JsValue]
    Feed.out.set(out)
    Feed.channel.set(channel)
    Env.cassandra <== CassandraDB("default", app.configuration.getConfig("cassandra").getOrElse(Configuration.empty))
    Env.fileService.set(FileService(Env.cassandra()))
  }

  override def onStop(app: Application): Unit = {
    Actors.system.foreach(_.shutdown())
  }

  override def onRequestCompletion(request : play.api.mvc.RequestHeader) : scala.Unit = {
    super.onRequestCompletion(request)
    metrics.Messages.ping.close()
  }
}
