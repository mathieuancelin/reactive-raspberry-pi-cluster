import com.amazing.es.Index
import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._
import play.api.libs.json.Json

import play.api.test._
import play.api.test.Helpers._
import play.test.WithApplication

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ApplicationSpec extends Specification {

  "Application" should {

    "test ES" in new WithApplication {

      val indexEs = Index[Product]("products")(mappings = Json.obj(
        "products" -> Json.obj(
          "properties" -> Json.obj(
            "label"-> Json.obj(
              "type"->"string",
              "analyzer"->"standard"),
            "description"-> Json.obj(
              "type"->"string",
              "analyzer"->"standard"),
            "id" -> Json.obj(
              "type" -> "string",
              "index" -> "not_analyzed"),
            "image" -> Json.obj(
              "type" -> "string",
              "index" -> "no"),
            "fragment" -> Json.obj(
              "type" -> "string",
              "index" -> "no")
          )
        )
      ))

      val res = Helpers.await(indexEs.search("type:recherche").map(rs => rs.hits.map(h => h._source.as[models.Product].description)))
      println(res)

    }
  }
}
