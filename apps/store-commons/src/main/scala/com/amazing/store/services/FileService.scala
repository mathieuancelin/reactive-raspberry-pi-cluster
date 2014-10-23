package com.amazing.store.services

import java.nio.ByteBuffer

import com.amazing.store.cassandra.CassandraDB
import com.datastax.driver.core.utils.Bytes
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration.DurationDouble

case class File(name: String, description: String, data: Array[Byte])

object FileService {
  def apply(cassandra: CassandraDB)(implicit ec: ExecutionContext) = {
    new FileService(cassandra)
  }
}

class FileService(cassandra: CassandraDB)(implicit ec: ExecutionContext) {

  val logger = Logger("FileService")

  val keyspace = "webresource"
  val table = s"$keyspace.files"

  init()

  def init(){
    Await.result(cassandra.cql(s"""
      CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = {'class':'SimpleStrategy', 'replication_factor':1};
    """).perform(ec), 30 second)

    Await.result(cassandra.cql(s"""
      CREATE TABLE IF NOT EXISTS $table (
        name text PRIMARY KEY,
        label text,
        file blob
      ) WITH compression = { 'sstable_compression' : '' };""").perform(ec), 30 second)
  }

  def save(name: String, description: String, data: Array[Byte]): Future[Unit] = {
    cassandra.cql(s"INSERT INTO $table (name, label, file) VALUES (?, ?, ?)").on(name, description, ByteBuffer.wrap(data)).perform(ec).map(_ => Unit)
  }

  def update(name: String, description: String, data: Array[Byte]): Future[Unit] = {
    cassandra.cql(s"UPDATE $table SET label = ?, file = ? WHERE name = ?").on(description, data, name).perform(ec).map(_ => Unit)
  }

  def get(name: String): Future[Option[File]] = {
    cassandra.cql(s"SELECT * from $table WHERE name = ? ").on(name).raw(ec).map{r =>
      val iter = r.iterator()
      if(iter.hasNext){
        val row = iter.next()
        logger.trace(s"file for name $name : $row")
        Some(File(row.getString("name"), row.getString("label"), Bytes.getArray(row.getBytes("file"))))
      } else {
        None
      }
    }
  }

}
