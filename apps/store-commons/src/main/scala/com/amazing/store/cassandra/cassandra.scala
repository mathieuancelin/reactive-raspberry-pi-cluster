package com.amazing.store.cassandra

import java.util.Date
import java.util.concurrent.{Callable, TimeUnit}

import com.datastax.driver.core._
import com.google.common.cache.{Cache, CacheBuilder, RemovalListener, RemovalNotification}
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.typesafe.config.ConfigFactory
import play.api.libs.json._
import play.api.{Play, Configuration, Logger}

import scala.collection.JavaConversions._
import scala.concurrent._

class CassandraCQLQuery(db: CassandraDB, name: String, query: String, args: Seq[AnyRef], namedArgs: Seq[(String, AnyRef)]) {

  import com.amazing.store.cassandra.FutureImplicits._

  def on(values: AnyRef*): CassandraCQLQuery = new CassandraCQLQuery(db, name, query, values.toSeq, Seq())

  def params(values: (String, AnyRef)*): CassandraCQLQuery = new CassandraCQLQuery(db, name, query, Seq(), values.toSeq)

  def firstOf[T](implicit r: Reads[T], ec: ExecutionContext): Future[Option[T]] = {
    first(ec).map(_.flatMap(o => r.reads(o).asOpt))
  }

  def first(implicit ec: ExecutionContext): Future[Option[JsObject]] = {
    db.api.withSession(name) { session =>
      val stmt = createStatement(session)
      Logger("CassandraDB").trace(s"first => ${query}")
      session.executeAsync(stmt).toScalaFuture.map { resultSet =>
        Option(resultSet.one()).map { row => parseRow(row, resultSet) }
      }
    }
  }

  def allOf[T](implicit r: Reads[T], ec: ExecutionContext): Future[List[T]] = {
    all(ec).map(_.map(o => r.reads(o)).collect { case JsSuccess(o, _) => o })
  }

  def all(implicit ec: ExecutionContext): Future[List[JsObject]] = {
    db.api.withSession(name) { session =>
      val stmt = createStatement(session)
      Logger("CassandraDB").trace(s"all => ${query}")
      session.executeAsync(stmt).toScalaFuture.map { resultSet =>
        resultSet.all().toList match {
          case List() | Nil => List[JsObject]()
          case list => list.map(row => parseRow(row, resultSet) )
        }
      }
    }
  }

  def raw(implicit ec: ExecutionContext):Future[ResultSet] = {
    db.api.withSession(name) { session =>
      val stmt = createStatement(session)
      Logger("CassandraDB").trace(s"raw => ${query}")
      session.executeAsync(stmt).toScalaFuture
    }
  }

  def perform(implicit ec: ExecutionContext): Future[ExecutionInfo] = {
    db.api.withSession(name) { session =>
      val stmt = createStatement(session)
      Logger("CassandraDB").trace(s"perform => ${query}")
      session.executeAsync(stmt).toScalaFuture.map { r => r.getExecutionInfo }
    }
  }

  private[cassandra] def createStatement(session: Session) = {
    var stmt = new BoundStatement(session.prepare(query)).bind(args:_*)
    for ((name, value) <- namedArgs) {
      value match {
        case s: java.lang.String      => stmt = stmt.setString(name, s)
        case s: java.lang.Double      => stmt = stmt.setDouble(name, s)
        case s: java.lang.Float       => stmt = stmt.setFloat(name, s)
        case s: java.lang.Integer     => stmt = stmt.setInt(name, s)
        case s: java.lang.Long        => stmt = stmt.setLong(name, s)
        case s: java.lang.Boolean     => stmt = stmt.setBool(name, s)
        case s: java.util.UUID        => stmt = stmt.setUUID(name, s)
        case s: java.util.Date        => stmt = stmt.setDate(name, s)
        case s: java.math.BigInteger  => stmt = stmt.setVarint(name, s)
        case s: java.math.BigDecimal  => stmt = stmt.setDecimal(name, s)
        case s: java.util.List[_]     => stmt = stmt.setList(name, s)
        case s: java.util.Set[_]      => stmt = stmt.setSet(name, s)
        case s: java.util.Map[_, _]   => stmt = stmt.setMap(name, s)
        case _ =>
      }
    }
    stmt
  }

  private[cassandra] def parseRow(row: Row, resultSet: ResultSet): JsObject = {
    val definitions = resultSet.getColumnDefinitions
    var json = Json.obj()
    definitions.asList().foreach { definition =>
      val name = definition.getName
      val value: Option[JsValue] = column(name, definition.getType, row)
      value.foreach { v => json = json ++ Json.obj(name -> v) }
    }
    Logger("CassandraDB").trace(s" => parsing column row = $json")
    json
  }

  private[cassandra] def column(name: String, t: DataType, row: Row): Option[JsValue] = {
    def toJsonType(obj: Any) = {
      obj match {
        case s: java.lang.String      => new JsString(s)
        case s: java.lang.Double      => new JsNumber(s.doubleValue())
        case s: java.lang.Float       => new JsNumber(s.floatValue())
        case s: java.lang.Integer     => new JsNumber(s.intValue())
        case s: java.lang.Long        => new JsNumber(s.longValue())
        case s: java.lang.Boolean     => new JsBoolean(s)
        case s: java.math.BigDecimal  => new JsNumber(s)
        case s: java.util.UUID        => new JsString(s.toString)
        case s: java.util.Date        => new JsNumber(s.getTime)
        case _ => JsNull
      }
    }

    val col = t.asJavaClass() match {
      case clazz if clazz == classOf[java.util.Map[_, _]] => {
        val param1 = t.getTypeArguments.toList(0).asJavaClass()
        val param2 = t.getTypeArguments.toList(1).asJavaClass()
        Some(new JsArray(row.getMap(name, param1, param2).toMap[Any, Any].toSeq.map {
          case (key, value) => Json.obj("key" -> toJsonType(key), "value" -> toJsonType(value))
        }))
      }
      case clazz if clazz == classOf[java.util.List[_]] => {
        val param = t.getTypeArguments.toList(0).asJavaClass()
        Some(new JsArray(row.getList(name, param).toIndexedSeq.map { a => toJsonType(a) }))
      }
      case clazz if clazz == classOf[java.util.Set[_]] => {
        val param = t.getTypeArguments.toList(0).asJavaClass()
        Some(new JsArray(row.getSet(name, param).toIndexedSeq.map { a => toJsonType(a) }))
      }
      case clazz if clazz == classOf[java.lang.Double] =>     Option(new JsNumber(row.getDouble(name)))
      case clazz if clazz == classOf[java.lang.Long] =>       Option(new JsNumber(row.getLong(name)))
      case clazz if clazz == classOf[java.lang.Float] =>      Option(new JsNumber(row.getFloat(name)))
      case clazz if clazz == classOf[java.lang.Boolean] =>    Option(new JsBoolean(row.getBool(name)))
      case clazz if clazz == classOf[java.lang.Integer] =>    Option(new JsNumber(row.getInt(name)))
      case clazz if clazz == classOf[java.lang.String] =>     Option(new JsString(row.getString(name)))
      case clazz if clazz == classOf[java.math.BigDecimal] => Option(new JsNumber(row.getDecimal(name)))
      case clazz if clazz == classOf[java.util.UUID] =>       Option(new JsString(row.getUUID(name).toString))
      case clazz if clazz == classOf[java.util.Date] =>       Option(row.getDate(name)).map(d => new JsNumber(d.getTime))
      case _ => None
    }
    Logger("CassandraDB").trace(s" => parsing column $name with type ${t.asJavaClass().getName} : $col")
    col
  }
}

class CassandraDB(name: String, cfg: Configuration = Play.current.configuration.getConfig("cassandra").getOrElse(Configuration.empty)) {
  private[cassandra] val api = new CassandraApi(cfg, CassandraDB.clusterFactory)
  def cql(query: String): CassandraCQLQuery = new CassandraCQLQuery(this, name, query, Seq(), Seq())
  def stop() = {
    api.sessions.invalidateAll()
    api.clusters.foreach{ case(n, cluster) =>
      Logger.info(s"Shutting down cassandra client: $n ...")
      cluster.close()
    }
  }
}

object CassandraDB {
  def apply() = new CassandraDB("default")
  def apply(name: String) = new CassandraDB(name)
  def apply(cfg: Configuration) = new CassandraDB("default", cfg)
  def apply(name: String, cfg: Configuration) = new CassandraDB(name, cfg)
  def apply(name: String, cfg: String) = new CassandraDB(name, Configuration(ConfigFactory.parseString(cfg)))
  private[cassandra] def clusterFactory = new DefaultClusterFactory
}

private[cassandra] object FutureImplicits {
  implicit class RichListenableFuture[T](val lf: ListenableFuture[T]) extends AnyVal {
    def toScalaFuture: Future[T] = {
      val p = Promise[T]()
      Futures.addCallback[T](lf, new FutureCallbackAdapter(p))
      p.future
    }
  }
}

private[cassandra] class FutureCallbackAdapter[V](p: Promise[V]) extends FutureCallback[V] {
  override def onSuccess(result: V): Unit = p success result
  override def onFailure(t: Throwable): Unit = p failure t
}

private [cassandra] class DefaultClusterFactory {
  def initCluster(dbName: String, cfg: Configuration): Cluster = {
    def error(db: String, message: String = "") = throw cfg.reportError(db, message)
    val builder = Cluster.builder()
    val nodes = cfg.getStringList(dbName + ".nodes").getOrElse(error(dbName, s"Missing configuration [cassandra. $dbName.nodes]"))
    nodes.foreach(builder.addContactPoint)
    builder.build()
  }
}

private[cassandra] class CassandraApi(cfg: Configuration, clusterFactory: DefaultClusterFactory) {

  private val names = cfg.subKeys

  private[cassandra] val clusters: Map[String, Cluster] = names.map { dbName =>
    dbName -> clusterFactory.initCluster(dbName, cfg)
  }.toMap

  private[cassandra] val sessions: Cache[String, Session] = CacheBuilder
    .newBuilder()
    .expireAfterAccess(5, TimeUnit.MINUTES)
    .removalListener(removalListener)
    .build()

  private def removalListener: RemovalListener[String, Session] = {
    new RemovalListener[String, Session] {
      def onRemoval(notification: RemovalNotification[String, Session]) {
        Logger.info("Shutting down Cassandra Session...")
        notification.getValue.close()
      }
    }
  }

  private def withCluster[A](dbName: String)(block: Cluster => A): A = clusters.get(dbName).map { c =>
    block(c)
  }.getOrElse {
    throw new IllegalArgumentException(s"Cassandra database[$dbName] not available")
  }

  def withSession[A](dbName: String = "default")(block: Session => A): A = withCluster(dbName) { c =>
    val session = sessions.get(dbName, new Callable[Session] {
      def call() = {
        c.connect()
      }
    })
    block(session)
  }

  def withSession[A](dbName: String, keyspace: String)(block: Session => A): A = withCluster(dbName) { c =>
    val session = sessions.get(dbName + ":" + keyspace, new Callable[Session] {
      def call() = {
        c.connect(keyspace)
      }
    })
    block(session)
  }
}