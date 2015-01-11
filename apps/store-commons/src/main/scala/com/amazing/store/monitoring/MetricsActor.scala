package com.amazing.store.monitoring

import java.util.concurrent.TimeUnit

import akka.actor.{Props, Actor}
import com.codahale.metrics.MetricRegistry
import org.elasticsearch.metrics.ElasticsearchReporter
import play.api.{Logger, Play}

/**
 * Created by adelegue on 11/01/15.
 */

object MetricsActor {
  case class Mark(name: String)

  def props() = Props(classOf[MetricsActor])
}

class MetricsActor extends Actor {

  import MetricsActor._

  val logger = Logger("MetricsActor")

  val registry = new MetricRegistry

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(context.self, classOf[Mark])
    val esUrl = Play.current.configuration.getString("metrics.elasticsearch.reporter.url").getOrElse("http://localhost:9200")
    val reporter = ElasticsearchReporter.forRegistry(registry)
      .hosts(esUrl)
      .build()
    reporter.start(30, TimeUnit.SECONDS)
  }
  override def receive: Receive = {
    case Mark(name) =>
      logger.trace(s"Metrics on $name")
      registry.meter(name).mark()
  }
}
