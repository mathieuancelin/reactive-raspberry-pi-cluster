package com.amazing.es

import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.libs.ws.{WSResponse, WS}
import play.api.{Play, Logger}

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, Future}

object Index{

  val logger = Logger("ELASTICSEARCH")

  def apply[T](name: String)(index : JsValue = JsNull, settings : JsValue = JsNull, mappings: JsValue = JsNull): Index ={

    val url = Play.current.configuration.getString("http.elasticsearch.url").getOrElse("http://localhost:9200")
    val rootIndex = Play.current.configuration.getString("http.elasticsearch.index").getOrElse("amazing")
    val rootIndexUrl = s"$url/$rootIndex"

    val indexToCreate = index match {
      case JsNull =>  Json.obj(
          "settings" -> settings,
          "mappings" -> mappings
        )
      case _ => index
    }
    logger.debug(s"SEARCH FOR INDEX $name, : $rootIndexUrl/$name")
    val indexCreation : Future[Any] = WS.url(s"$rootIndexUrl/$name").head().map { response =>
      response.status match {
        case 200 =>
          logger.info(s"Index $name existing nothing to do")
        case 404 =>
          logger.info(s"Creating index ${Json.prettyPrint(indexToCreate)}")
          WS.url(rootIndexUrl).put(indexToCreate).map { r => r.status match {
              case 200 | 201 => logger.info(s"index created")
              case _ => logger.error(s"Error while creating index $name : ${r.body}")
            }
          }
        case _ =>
          logger.error(s"Error while creating index ${response.body}")
      }
    }
    Await.result(indexCreation, 30 second)
    new Index(name, rootIndexUrl)
  }


}

case class SearchResult(took: Int, timed_out: Boolean, _shards: Shard, hits: Hits)
case class Shard(total: Int, successful: Int, failed: Int)
case class Hits(total: Int, hits: List[Hit])
case class Hit(_index: String, _type: String, _id: String, _score: Option[BigDecimal], _source: JsValue)

class Index(name: String, rootIndexUrl: String) {

  import play.api.libs.json._

  val indexUrl = s"$rootIndexUrl/$name"

  implicit val formatHit = Json.format[Hit]
  implicit val cartlineListFmt = Format(Reads.list[Hit], Writes.list[Hit])
  implicit val formatShard = Json.format[Shard]
  implicit val formatHits = Json.format[Hits]
  implicit val formatSearchResult = Json.format[SearchResult]

  def saveAsJsValue(id: String, data: JsValue): Future[JsValue] = {
    WS.url(s"$indexUrl/$id").post(data).map(r => Json.parse(r.body))
  }

  def save[T](id: String, data: T)(implicit format: Writes[T]): Future[JsValue] = {
    this.saveAsJsValue(id, Json.toJson(data))
  }

  def getAsJsValue(id: String) = {
    WS.url(s"$indexUrl/$id").get().map(r => Json.parse(r.body) \ "_source")
  }

  def get(id: String): Future[Option[JsValue]] = {
    this.getAsJsValue(id: String).map(_.asOpt[JsValue])
  }

  def search(query: JsValue): Future[Option[SearchResult]] = {
    WS.url(s"$indexUrl/_search").withBody(query).get().map(handleResponse)
  }

  def search(query: String): Future[Option[SearchResult]] = {
    WS.url(s"$indexUrl/_search").withQueryString("q" -> query).get().map(handleResponse)

  }

  def handleResponse(r: WSResponse) : Option[SearchResult] = {
    r.status match {
      case 200 => Some(Json.parse(r.body).as[SearchResult])
      case _ =>
        Index.logger.error(s"Error while searching ${r.body}")
        None
    }
  }

}