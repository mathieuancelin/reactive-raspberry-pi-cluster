
package com.amazing.store.persistence.journal

import java.lang.{Long => JLong}
import java.nio.ByteBuffer

import akka.actor.ActorContext
import akka.serialization.SerializationExtension
import com.amazing.store.cassandra.CassandraDB
import com.amazing.store.monitoring.Metrics._
import com.amazing.store.monitoring.MetricsImplicit._
import com.amazing.store.monitoring.MetricsName._
import com.datastax.driver.core.utils.Bytes
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json

import scala.collection.JavaConversions._
import scala.concurrent.duration.DurationDouble
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


class CassandraJournal(context: ActorContext) extends Journal(context) with Statement{
  val logger = Logger("CassandraJournal")

  implicit val ec: ExecutionContext = context.dispatcher
  implicit val readFirstPartition = Json.reads[PartitionInfos]

  private[this] val config = context.system.settings.config.getConfig("cassandra-journal")
  override def keyspace: String = Try(config.getString("keyspace")).getOrElse("akkaJournal")
  override def table: String = Try(config.getString("table")).getOrElse("messages")
  override def maxResultSize: Int = Try(config.getInt("max-result-size")).getOrElse(50000)
  override def replicationFactor: Int = Try(config.getInt("replication-factor")).getOrElse(1)
  val serialization = SerializationExtension(context.system)

  val cassandra = Try(config.getString("cassandra")) match {
    case Failure(e) => CassandraDB("default")
    case Success(c) => CassandraDB("default", c)
  }

  Await.result(cassandra.cql(createKeyspace).perform, 30 second)
  Await.result(cassandra.cql(createTableFirstSequence).perform, 30 second)
  Await.result(cassandra.cql(createTable).perform, 30 second)

  var currentNr: Option[PartitionNr] = None

  def currentPartitionNr(persistentId: String)(implicit ec: ExecutionContext): PartitionNr = {
    val nr = partitionNr()
    currentNr match {
      case None =>
        createHeader(persistentId, nr)
        createOrUpdatePartitionInfos(persistentId, nr)
        currentNr=Some(nr)
        nr
      case Some(value) if value == nr => value
      case Some(value) =>
        createHeader(persistentId, nr)
        createOrUpdatePartitionInfos(persistentId, nr)
        currentNr=Some(nr)
        nr
    }
  }

  def createHeader(persistentId: String, nr: PartitionNr): Unit = {
    cassandra.cql(writeHeader).on(persistentId, nr.partitionNum: JLong).perform
  }

  override def writeMessages(messages: Seq[JournalMessage]): Unit = {
    messages.foreach{msg =>
      publish(buildMark(CASSANDRA_JOURNAL_MARK))
      cassandra.cql(writeMessage).on(msg.persistentId, currentPartitionNr(msg.persistentId).partitionNum: JLong, msg.sequenceNum: JLong, ByteBuffer.wrap(serialization.serialize(msg).get))
        .perform
        .withTimer(CASSANDRA_JOURNAL_TIMER)
    }
  }

  override def nextSequenceNum(persistentId: String): Long = {
    val now = DateTime.now()
    //Format 2014-02-12 11:46:10.123 => 20140212114610123
      now.getYear      * 10000000000000L +
      now.getMonthOfYear * 100000000000L +
      now.getDayOfMonth    * 1000000000L +
      now.getHourOfDay       * 10000000L +
      now.getMinuteOfHour      * 100000L +
      now.getSecondOfMinute      * 1000L +
      now.getMillisOfSecond
  }

  override def lastSequenceNum(persistentId: String): Long = 99999999999999999L

  override def replayMessages(persistentId: String, fromSequenceNum: Long, toSequenceNum: Long)(callback: (JournalMessage) => Unit): Future[Unit] = {
    logger.debug("Replay messages ...")
    findPartitionInfo(persistentId).flatMap{
      case None => Future.successful(Unit)
      case Some(num) =>
        extractPartitionNrFromSequence(persistentId, fromSequenceNum).flatMap{ sequence =>
          replayAsync(persistentId, nextPartition = false, num.last_partition_nr, sequence, fromSequenceNum, toSequenceNum)(callback)
        }
    }
  }
  
  def listMessages(persistentId: String, partitionNr:PartitionNr, fromSequenceNum: Long, toSequenceNum: Long): Future[List[JournalMessage]] = {
    logger.trace(s"list messages from $fromSequenceNum to $toSequenceNum for partistent id : $persistentId and partition $partitionNr")
    cassandra.cql(selectMessages).on(persistentId, partitionNr.partitionNum: JLong, fromSequenceNum: JLong, toSequenceNum: JLong).raw.map{rs=>
      rs.all().toList.filter(_.getString("marker") equals  "A").map{r =>
        logger.trace(s"row : $r")
        serialization.deserialize(Bytes.getArray(r.getBytes("message")), classOf[JournalMessage]).get
      }
    }
  }

  def createOrUpdatePartitionInfos(persistentId: String, partitionNr: PartitionNr): Future[Unit] = {
    findPartitionInfo(persistentId).flatMap{
      case None =>
        logger.trace(s"Init partition info for $persistentId and $partitionNr")
        cassandra.cql(writeFirstPartition).on(persistentId, partitionNr.partitionNum: JLong, partitionNr.partitionNum: JLong).perform.map(_ => Unit)
      case Some(js) =>
        logger.trace(s"Update partition info for $persistentId with ${partitionNr}")
        cassandra.cql(updateLastPartition).on(partitionNr.partitionNum: JLong, persistentId).perform.map(_ => Unit)
    }
  }

  def findPartitionInfo(persistentId: String): Future[Option[PartitionInfos]] = {
    cassandra.cql(selectFirstPartition).on(persistentId).firstOf[PartitionInfos]
  }

  def partitionNr(): PartitionNr = {
    PartitionNr(DateTime.now())
  }

  def extractPartitionNrFromSequence(persistentId: String, sequence: Long): Future[PartitionNr] = {
    logger.trace(s"extractPartitionNrFromSequence for $persistentId, $sequence")
    if(sequence > 999999999){
      Future.successful(PartitionNr(Math.abs(sequence / 1000000000)))
    }else{
      findPartitionInfo(persistentId).map {
        case None =>
          val current = currentPartitionNr(persistentId)
          logger.trace(s"Current partition num : $current")
          current
        case Some(r) =>
          val current = r.first_partition_nr
          logger.trace(s"Current partition num : $current")
          PartitionNr(current)
      }
    }
  }

  def partitionExist(persistentId: String, partitionNr: Long) : Future[Boolean] = {
    cassandra.cql(selectHeader).on(persistentId, partitionNr:JLong).raw.map(!_.isExhausted)
  }


  def replayAsync(persistentId: String, nextPartition: Boolean, lastPartition: Long, partitionNr: PartitionNr, fromSequenceNum: Long, toSequenceNum: Long)(callback:(JournalMessage) => Unit): Future[Unit] = {
    listMessages(persistentId, partitionNr, fromSequenceNum, toSequenceNum).flatMap{
      case List() | Nil if partitionNr.partitionNum > lastPartition => Future.successful(Unit)
      case List() | Nil if !nextPartition =>
        replayAsync(persistentId, nextPartition = true, lastPartition, partitionNr, fromSequenceNum+1, toSequenceNum)(callback)
      case List() | Nil if nextPartition =>
        findNextPartitionNr(persistentId, partitionNr, lastPartition).flatMap {
          case None => Future.successful(Unit)
          case Some(p) if p.partitionNum <= lastPartition =>
            replayAsync(persistentId, nextPartition = false, lastPartition, p, fromSequenceNum + 1, toSequenceNum)(callback)
          case _ => Future.successful(Unit)
        }
      case messages =>
        messages.foreach(callback)
        replayAsync(persistentId, nextPartition = false, lastPartition, partitionNr, messages.last.sequenceNum+1, toSequenceNum)(callback)
    }
  }

  def findNextPartitionNr(persistentId: String, currentPartitionNr: PartitionNr, lastPartitionNr: Long): Future[Option[PartitionNr]] = {
    currentPartitionNr.nextPartitionNr() match {
      case p@PartitionNr(value) if value <= lastPartitionNr && Await.result(partitionExist(persistentId, value), 30 second) =>
        partitionExist(persistentId, value).flatMap{
          case true =>
            logger.trace(s"Partition exists : $p")
            Future.successful(Some(p))
          case false =>
            logger.trace(s"Partition suivante ...")
            findNextPartitionNr(persistentId, p.nextPartitionNr(), lastPartitionNr)
        }
      case PartitionNr(value) if value > lastPartitionNr =>
        logger.trace(s"Aucune partition suivante")
        Future.successful(None)
    }
  }

  case class PartitionInfos(processor_id: String, first_partition_nr: Long, last_partition_nr: Long)


  object PartitionNr {
    def apply(date: DateTime) =  new PartitionNr(date.getYear, date.getMonthOfYear, date.getDayOfMonth)
    def apply(sequenceNum:Long) = {
      val year = Math.abs(sequenceNum / 10000)
      val month = Math.abs((sequenceNum - (year*10000)) / 100)
      val day = sequenceNum - (year*10000 + month * 100)
      logger.trace(s"sequence $sequenceNum ::: year $year, month $month, day $day")
      new PartitionNr(year.toInt, month.toInt, day.toInt)
    }
    def apply(year: Int, month: Int, day: Int) = new PartitionNr(year, month, day)
    def unapply(partitionNr: PartitionNr): Option[Long] = Some(partitionNr.partitionNum)

  }

  class PartitionNr(year: Int, month: Int, day: Int) {

    val partitionNum = year * 10000 + month * 100 + day

    def nextPartitionNr(): PartitionNr = {
      val next = new DateTime(year, month, day, 0, 0).plusDays(1)
      PartitionNr(next)
    }
    override def toString = s"$partitionNum"
  }
}


trait Statement {

  def keyspace: String
  def table: String
  def maxResultSize: Int
  def replicationFactor: Int
  private def tableName = s"${keyspace}.${table}"

  def createKeyspace = s"""
    CREATE KEYSPACE IF NOT EXISTS ${keyspace}
    WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : ${replicationFactor} }
  """

  def createTableFirstSequence = s"""
    CREATE TABLE IF NOT EXISTS ${keyspace}.partitionInfo (
      processor_id text PRIMARY KEY ,
      first_partition_nr bigint,
      last_partition_nr bigint) WITH compression = { 'sstable_compression' : '' }
  """

  def createTable = s"""
    CREATE TABLE IF NOT EXISTS ${tableName} (
      processor_id text,
      partition_nr bigint,
      sequence_nr bigint,
      marker text,
      message blob,
      PRIMARY KEY ((processor_id, partition_nr), sequence_nr, marker)) WITH compression = { 'sstable_compression' : '' }
  """

  def selectFirstPartition = s"""
    SELECT *  FROM ${keyspace}.partitionInfo WHERE processor_id = ?
  """

  def writeFirstPartition = s"""
    INSERT INTO ${keyspace}.partitionInfo (processor_id, first_partition_nr, last_partition_nr)
    VALUES (?, ?, ?) IF NOT EXISTS
  """

  def updateLastPartition = s"""
    UPDATE ${keyspace}.partitionInfo SET last_partition_nr = ? WHERE processor_id = ?
  """

  def writeHeader = s"""
    INSERT INTO ${tableName} (processor_id, partition_nr, sequence_nr, marker, message)
    VALUES (?, ?, 0, 'H', 0x00) IF NOT EXISTS
  """

  def writeMessage = s"""
    INSERT INTO ${tableName} (processor_id, partition_nr, sequence_nr, marker, message)
    VALUES (?, ?, ?, 'A', ?)
  """

  def selectHeader = s"""
    SELECT * FROM ${tableName} WHERE
      processor_id = ? AND
      partition_nr = ? AND
      sequence_nr = 0
  """


  def selectMessages = s"""
    SELECT * FROM ${tableName} WHERE
      processor_id = ? AND
      partition_nr = ? AND
      sequence_nr >= ? AND
      sequence_nr <= ?
      ORDER BY sequence_nr ASC
      LIMIT ${maxResultSize}
  """


}


