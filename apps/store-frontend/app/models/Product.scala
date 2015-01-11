package models

import com.amazing.store.es.Index
import controllers.Application
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.twirl.api
import play.twirl.api.{BaseScalaTemplate, HtmlFormat, Template1}

import config.Env
import scala.concurrent.Future


case class Product(id: String, label: String, description: String, image: String, price: Double, fragments: List[Fragment]) {
  def generateFragments = Product(id, label, description, image, price, Product.generateFragments(this))
}

case class Fragment(`type`: String, html: String)


object Product {

  implicit val fragmentFormat = Json.format[Fragment]
  implicit val productFormat = Json.format[Product]
  val reads: Reads[Product] = Json.reads[Product]

  implicit val fragmentWrites = Json.writes[Fragment]
  implicit val productWrites = Json.writes[Product]
  val writes: Writes[Product] = productWrites

  val indexEs = Index("products")(settings = Json.obj(
    "settings" -> Json.obj(
      "index" -> Json.obj(
        "analysis" -> Json.obj(
          "analyzer" -> Json.obj(
            "custom_search_analyzer_fr" -> Json.obj(
              "filter" -> Json.arr("asciifolding", "lowercase", "snowball_fr", "worddelimiter"),
              "tokenizer" -> "nGram",
              "type" -> "custom"
            )
          ),
          "filter" -> Json.obj(
            "elision" -> Json.obj(
              "articles" -> Json.arr("l", "m", "t", "qu", "n", "s", "j", "d"),
              "type" -> "elision"
            ),
            "snowball_en" -> Json.obj(
              "language" -> "English",
              "type" -> "snowball"
            ),
            "snowball_fr" -> Json.obj(
              "language" -> "French",
              "type" -> "snowball"
            ),
            "stopwords" -> Json.obj(
              "ignore_case" -> "true",
              "stopwords" -> Json.arr("_french_"),
              "type" -> "stop"
            ),
            "worddelimiter" -> Json.obj(
              "type" -> "word_delimiter"
            )
          ),
          "tokenizer" -> Json.obj(
            "nGram" -> Json.obj(
              "max_gram" -> "50",
              "min_gram" -> "3",
              "type" -> "nGram"
            )
          )
        )
      )
    ),
    "mappings" -> Json.obj(
      "products" -> Json.obj(
        "properties" -> Json.obj(
          "label" -> Json.obj(
            "type" -> "string",
            "analyzer" -> "standard",
            "fields" -> Json.obj(
              "fr" -> Json.obj(
                "analyzer" -> "custom_search_analyzer_fr",
                "type" -> "string"
              )
            )
          ),
          "description" -> Json.obj(
            "type" -> "string",
            "analyzer" -> "standard",
            "fields" -> Json.obj(
              "fr" -> Json.obj(
                "analyzer" -> "custom_search_analyzer_fr",
                "type" -> "string"
              )
            )
          ),
          "id" -> Json.obj(
            "type" -> "string",
            "index" -> "not_analyzed"),
          "image" -> Json.obj(
            "type" -> "string",
            "index" -> "no"),
          "fragments" -> Json.obj(
            "type" -> "nested",
            "properties" -> Json.obj(
              "type" -> Json.obj(
                "type" -> "string",
                "index" -> "not_analyzed"),
              "html" -> Json.obj(
                "type" -> "string",
                "index" -> "no")
            )
          )
        )
      )
    )
  )
  )


  val fragments = Map(
    "recherche" -> views.html.fragments.detailproduit,
    "cart" -> views.html.fragments.cartproduit,
    "detail" -> views.html.pageproduct
  )

  def getById(id: String) = indexEs.get(id)

  def save(product: Product): Future[Product] = indexEs.save(product, Some(product.id)).map(ind => product)

  def listFragments(`type`: String, mayBeSearch: Option[String], pageNumber: Int, numberPerPage: Int): Future[(Long, List[String])] = {
    val from = (pageNumber - 1) * numberPerPage
    val searchQuery = mayBeSearch match {
      case None => s"fragments.type:${`type`}"
        Json.obj(
          "from" -> from,
          "size" -> numberPerPage,
          "query" -> Json.obj(
            "match_all" -> Json.obj()
          ),
          "sort" -> Json.arr(
            Json.obj("label" -> "asc")
          )
        )
      case Some(q) =>
        s"fragments.type:${`type`} AND description:$q"
        Json.obj(
          "from" -> from,
          "size" -> numberPerPage,
          "min_score" -> 0.2,
          "query" -> Json.obj(
//            "fuzzy_like_this" -> Json.obj(
//              "fields" -> Json.arr("label", "label.fr", "description", "description.fr"),
//              "like_text" -> q
//            )
            "multi_match" -> Json.obj(
              "query" -> q,

              "fields" -> Json.arr("label", "label.fr", "description", "description.fr")
            )
          )
        )
    }
    config.Env.logger.debug(s"Query = $searchQuery")
    indexEs.search(searchQuery).map {
      case Some(indexResult) =>
        val frags: List[String] = for {
          hits <- indexResult.hits.hits
          fragment <- hits._source.as[Product].fragments if fragment.`type` equals `type`
        } yield fragment.html
        val nbPages = indexResult.hits.total / numberPerPage + 1
        (nbPages.toLong, frags)
      case None =>
        (0l, List())
    }
  }

  def listFragmentsByIds(ids: List[String], `type`: String): Future[List[JsValue]] = {
    config.Env.logger.debug(s"List frags by id : $ids, type : ${`type`}")
    indexEs.search(Json.obj(
      "query" -> Json.obj(
        "ids" -> Json.obj(
          "values" -> Json.toJson(ids)
        )
      )
    )).map {
      case Some(rs) =>
        for {
          product <- rs.hits.hits.map(h => h._source.as[Product])
          fragment <- product.fragments if fragment.`type` equals `type`
        } yield Json.obj("id" -> product.id, "price" -> product.price, "html" -> fragment.html)
      case None =>
        List()
    }
  }

  def generateFragments(product: Product): List[Fragment] = fragments.map { entry =>
    val fragments: BaseScalaTemplate[HtmlFormat.Appendable, api.Format[HtmlFormat.Appendable]] with Template1[Product, HtmlFormat.Appendable] = entry._2
    Fragment(entry._1, fragments.render(product).toString())
  }.toList

  def mergeAndUpdate(id: String, label: String, description: String, imageUrl: String, price: Double): Future[Product] = {
    val savedProduct: Future[Product] = getById(id).flatMap {
      case None =>
        Env.logger.debug(s"Product $id not exist in db, creating")
        val product = Product(
          id,
          label,
          description,
          imageUrl,
          price,
          List()
        )
        save(product.generateFragments)
      case Some(jsDbProduct) =>
        val dbProduct = jsDbProduct.as[Product]
        Env.logger.debug(s"Product $id exist in db, updating")
        val product = Product(
          id,
          Some(label).getOrElse(dbProduct.label),
          Some(description).getOrElse(dbProduct.description),
          Some(imageUrl).getOrElse(dbProduct.image),
          Some(price).getOrElse(dbProduct.price),
          List()
        )
        save(product.generateFragments)
    }
    savedProduct.foreach(r => Application.rebuildIndexPage)
    savedProduct
  }

}

