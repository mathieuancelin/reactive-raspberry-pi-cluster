import java.util.UUID
import java.util.concurrent.{TimeUnit, Executors}

import com.amazing.store.cassandra.CassandraDB
import com.typesafe.config.ConfigFactory
import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._
import play.api.Configuration

import play.api.test._
import play.api.test.Helpers._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ApplicationSpec extends Specification {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  "Cassandra" should {

    "Work" in {
      val cassandra = CassandraDB("default", Configuration(ConfigFactory.load()).getConfig("cassandra").getOrElse(Configuration.empty))
      val fu = for {
        _ <- cassandra.cql("CREATE KEYSPACE IF NOT EXISTS test WITH replication = {'class':'SimpleStrategy', 'replication_factor':1};").perform
        - <- cassandra.cql(
            """CREATE TABLE IF NOT EXISTS test.user (
            id uuid PRIMARY KEY,
            name text,
            surname text,
            email text
            );""").perform
         _ <- cassandra.cql("""CREATE INDEX IF NOT EXISTS user_email ON test.user (email);""").perform
         _ <- cassandra.cql("INSERT INTO test.user (id, name, surname, email) VALUES ( ?, ?, ?, ? );").on(UUID.randomUUID(), "John", "Doe", "john.doe@gmail.com").perform
      } yield ()
      Await.result(fu, Duration(10, TimeUnit.SECONDS))
      val fu2 = cassandra.cql("select * from test.user;").all.map(println)
      Await.result(fu2, Duration(10, TimeUnit.SECONDS))
      cassandra.stop()
      success
    }
  }
}
