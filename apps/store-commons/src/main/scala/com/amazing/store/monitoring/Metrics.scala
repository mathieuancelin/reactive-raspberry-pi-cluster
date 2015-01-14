package com.amazing.store.monitoring

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import com.amazing.store.monitoring.Metrics.{Mark, Metric}
import com.amazing.store.tools.Reference
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.Timer.Context
import org.elasticsearch.metrics.ElasticsearchReporter
import play.api.{Logger, Play}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by adelegue on 11/01/15.
 */

object MetricsName {

  val ELACTICSEARCH_TIMER = "elasticsearch.timer"
  val ELACTICSEARCH_MARK = "elasticsearch.mark"
  val CASSANDRA_DRIVER_MARK = "cassandra.driver.mark"
  val CASSANDRA_DRIVER_TIMER = "cassandra.driver.timer"
  val CASSANDRA_JOURNAL_MARK = "cassandra.journal.mark"
  val CASSANDRA_JOURNAL_TIMER = "cassandra.journal.timer"

  def akkaInnerMessageName(theClassName: String) = Metrics.metricName(s"akka.message.inner.${theClassName}")

  def akkaOuterMessageName(theClassName: String) = Metrics.metricName(s"akka.message.outer.${theClassName}")

}

object MetricsImplicit {
  implicit def futureToMetrics[T](future: Future[T]): Metrics[T] = new Metrics(future)
}

class Metrics[T](future: Future[T]) {

  def withTimer(name: String)(implicit ec: ExecutionContext): Future[T] = {
    Metrics.timer(Metrics.metricName(name), future)
  }
}

object Metrics {

  private val _registry = Reference.empty[MetricRegistry]()

  private val _metricsActor = Reference.empty[ActorRef]()

  private val _actorSystem = Reference.empty[ActorSystem]()

  private val _namespace = Reference.empty[String]()

  private val _isInit = Reference[Boolean](false)

  sealed trait Metric
  case class Mark(name: String) extends Metric

  def buildMark(name: String) = Mark(metricName(name))

  def init(appName: String)(implicit system: ActorSystem) = {

    _registry.set(new MetricRegistry)
    _actorSystem.set(system)
    _namespace.set(appName)

    val esUrl = Play.current.configuration.getString("metrics.elasticsearch.reporter.url").getOrElse("http://localhost:9200")
    val reporter = ElasticsearchReporter.forRegistry(Metrics._registry())
      .hosts(esUrl)
      .build()
    reporter.start(30, TimeUnit.SECONDS)

    _metricsActor.set(_actorSystem().actorOf(MetricsActor.props()))
    _isInit.set(true)
  }

  def registry() = _registry()

  def metricName(name: String): String = {
    if(_isInit()){
      s"${_namespace()}.$name"
    }else{
      name
    }
  }

  def publish(metric: Metric): Unit = {
    if(_isInit()){
      _actorSystem().eventStream.publish(metric)
    }
  }

  def publishMark(name: String): Unit = publish(buildMark(name))

  def timer[T](name: String, future: Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val time: Context = registry().timer(metricName(name)).time()
    future.onComplete{
      case Success(s) => time.stop()
      case Failure(f) => time.stop()
    }
    future
  }

}

object MetricsActor {
  def props() = Props(classOf[MetricsActor])
}

class MetricsActor extends Actor {

  val logger = Logger("MetricsActor")

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(context.self, classOf[Metric])
  }
  override def receive: Receive = {
    case Mark(name) =>
      logger.trace(s"Metrics on $name")
      Metrics.registry().meter(name).mark()
  }
}
