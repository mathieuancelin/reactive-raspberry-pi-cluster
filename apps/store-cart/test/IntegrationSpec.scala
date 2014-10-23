import com.amazing.cassandra.Cassandra
import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._
import play.api.libs.json.JsValue

import play.api.test._
import play.api.test.Helpers._

/**
 * add your integration spec here.
 * An integration test will fire up a whole play application in a real (or headless) browser
 */
@RunWith(classOf[JUnitRunner])
class IntegrationSpec extends Specification {

  "Application" should {

    "work from within a browser" in new WithBrowser {

      println("DEBUT")
      private val await: List[JsValue] = Helpers.await(Cassandra.query("SELECT * FROM amazing.cart WHERE login = ? ;").withArg("login", "john.doe").cursor.all())
      println(s"RESULTAT : $await")

    }
  }
}
