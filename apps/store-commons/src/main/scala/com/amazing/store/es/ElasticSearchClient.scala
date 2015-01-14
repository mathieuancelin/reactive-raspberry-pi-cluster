package com.amazing.store.es

import com.amazing.store.es.Errors.{IndexCreationException, IndexingDataException}
import com.amazing.store.monitoring.Metrics._
import com.amazing.store.monitoring.MetricsName._
import com.amazing.store.monitoring.MetricsImplicit._
import play.api.{Play, Logger}
import play.api.libs.json._
import play.api.libs.ws.{WSRequestHolder, WSResponse}

import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future}



object HttpClient {
  val builder = new com.ning.http.client.AsyncHttpClientConfig.Builder()
  val client = new play.api.libs.ws.ning.NingWSClient(builder.build())

  def apply() = {
    client
  }
}

object Errors {

  class IndexCreationException(message: String) extends RuntimeException(message: String) {}
  class IndexingDataException(message: String) extends RuntimeException(message: String) {}

}

object Client {
  def apply(host: String = "localhost", port: Int = 9300)(implicit ec: ExecutionContext) = new Client(s"http://$host:$port")(ec)
  def apply(url: String)(implicit ec: ExecutionContext): Client = new Client(url)(ec)
}

class Client(url: String = "http://localhost:9300")(implicit ec: ExecutionContext) {

  val logger = Logger("ELASTICSEARCH")

  def createTemplate(name: String, template: JsValue): Future[WSResponse] = {
    val tempateUrl: WSRequestHolder = HttpClient().url(s"$url/_template/$name")
    tempateUrl.get().flatMap { response =>
      response.status match {
        case 200 =>
          logger.debug(s"template $name already exists")
          Future.successful(response)
        case 404 => val post: Future[WSResponse] = tempateUrl.post(template)
          post.onFailure{
            case e => logger.error(s"template $name was not created", e)
          }
          post.map { r =>
            logger.debug(s"template $name created, ${r.body}")
            r
          }
      }
    }
  }

  def createIndex(indexName: String, nameType: String)(indexSettings : JsValue = JsNull, settings : JsValue = JsNull, mappings: JsValue = JsNull): Future[Index] ={
    val rootIndexUrl = s"$url/$indexName"

    val indexToCreate = indexSettings match {
      case JsNull =>
        if( (settings equals JsNull) && (mappings equals JsNull)){
          JsNull
        }else{
          Json.obj(
            "settings" -> settings,
            "mappings" -> mappings)
        }
      case _ => indexSettings
    }

    val indexUrl = buildIndexUrl(rootIndexUrl, nameType)

    logger.debug(s"SEARCH FOR INDEX $indexName, type : $nameType, : $indexUrl")
    if(! (indexToCreate equals JsNull)){
      HttpClient().url(s"$indexUrl").head().flatMap { response =>
        response.status match {
          case 200 =>
            logger.info(s"Index $indexUrl existing nothing to do")
            Future.successful(response)
          case 404 =>
            logger.info(s"Creating index ${Json.prettyPrint(indexToCreate)}")
            HttpClient().url(rootIndexUrl).put(indexToCreate).map { r => r.status match {
              case 200 | 201 =>
                logger.info(s"index created")
                r
              case _ =>
                logger.error(s"Error while creating index $indexUrl : ${r.body}")
                Future.failed(new IndexCreationException(s"${response.body}"))
            }}
          case _ =>
            logger.error(s"Error : code ${response.status} while creating index ${response.body}")
            Future.failed(new IndexCreationException(s"${response.body}"))
        }
      }.map(_ => new Index(indexUrl)(ec))
    } else {
      Future.successful(new Index(indexUrl)(ec))
    }
  }

  def buildIndexUrl(indexName: String, nameType: String) = {
    s"$indexName/$nameType"
  }
}

object Data {

  case class SearchResult(took: Int, timed_out: Boolean, _shards: Shard, hits: Hits)

  case class Shard(total: Int, successful: Int, failed: Int)

  case class Hits(total: Int, hits: List[Hit])

  case class Hit(_index: String, _type: String, _id: String, _score: Option[BigDecimal], _source: JsValue)
}

object Index {

  def apply(nameType: String)(indexSettings : JsValue = JsNull, settings : JsValue = JsNull, mappings: JsValue = JsNull)(implicit ec: ExecutionContext) = {
    val url = Play.current.configuration.getString("http.elasticsearch.url").getOrElse("http://localhost:9200")
    val rootIndex = Play.current.configuration.getString("http.elasticsearch.index").getOrElse("amazing")
    Await.result(Client(url).createIndex(rootIndex, nameType)(indexSettings, settings, mappings), 2 seconds)
  }

}


class Index(indexUrl: String)(implicit ec: ExecutionContext) {

  import com.amazing.store.es.Data._

  implicit val formatHit = Json.format[Hit]
  implicit val cartlineListFmt = Format(Reads.list[Hit], Writes.list[Hit])
  implicit val formatShard = Json.format[Shard]
  implicit val formatHits = Json.format[Hits]
  implicit val formatSearchResult = Json.format[SearchResult]

  def saveAsJsValue(data: JsValue, mayBeId: Option[String] = None): Future[JsValue] = {
    val path: String = mayBeId match {
      case None => s"$indexUrl"
      case Some(id) => s"$indexUrl/$id"
    }
    publishMark(ELACTICSEARCH_MARK)
    HttpClient().url(path).post(data).withTimer(ELACTICSEARCH_TIMER).map{
      case r if r.status < 300 =>  Json.parse(r.body)
      case r => throw new IndexingDataException(r.body)
    }
  }

  def save[T](data: T, id: Option[String])(implicit format: Writes[T]): Future[JsValue] = {
    this.saveAsJsValue(Json.toJson(data), id)
  }

  def getAsJsValue(id: String) = {
    publishMark(ELACTICSEARCH_MARK)
    HttpClient().url(s"$indexUrl/$id").get().map(r => Json.parse(r.body) \ "_source").withTimer(ELACTICSEARCH_TIMER)
  }

  def get(id: String): Future[Option[JsValue]] = {
    this.getAsJsValue(id: String).map(_.asOpt[JsValue])
  }

  def search(query: JsValue): Future[Option[SearchResult]] = {
    publishMark(ELACTICSEARCH_MARK)
    HttpClient().url(s"$indexUrl/_search").withBody(query).get().map(handleResponse).withTimer(ELACTICSEARCH_TIMER)
  }

  def search(query: String): Future[Option[SearchResult]] = {
    publishMark(ELACTICSEARCH_MARK)
    HttpClient().url(s"$indexUrl/_search").withQueryString("q" -> query).get().map(handleResponse).withTimer(ELACTICSEARCH_TIMER)

  }

  def handleResponse(r: WSResponse) : Option[SearchResult] = {
    r.status match {
      case 200 => Some(Json.parse(r.body).as[SearchResult])
      case _ => None
    }
  }

}