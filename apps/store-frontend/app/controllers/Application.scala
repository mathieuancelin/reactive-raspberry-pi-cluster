package controllers

import com.amazing.store.tools.Reference
import models.Product
import play.api.libs.{Codecs, EventSource}
import play.api.libs.json.{Json, JsArray, JsValue}
import play.api.mvc._
import play.twirl.api.Html
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.{Logger, Play}
import scala.util.Random
import play.api.Play.current
import play.api.libs.iteratee.{Enumeratee, Enumerator}
import scala.concurrent.Future
import com.amazing.store.auth.AuthActions._
import play.api.mvc.Result
import config.Env

object Application extends Controller {

  def applicationIsUp() = Action {
    Ok("ok").withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def stats() = Action {
    Ok(Json.obj(
      "avgRate" -> metrics.Messages.avgRate,
      "avgTime" -> metrics.Messages.avgTime
    )).withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  val indexPage = Reference(search(1, None))
  def index() = {
    indexPage()
  }

  def rebuildIndexPage = indexPage.set(search(1, None))

  def page(pagenNum: Int) = search(pagenNum, None)

  def search(pageNum: Int, search: Option[String]) = Action.async{
    for {
      (nbPages, html) <- Product.listFragments("recherche", search, pageNum, 6)
    } yield Ok(views.html.product(Html(html.mkString), search, pageNum, nbPages, 6))
  }


  def pageProduct(id: String) = Action.async {
    Product.getById(id).map{
      case Some(p) => Ok(Html(p.as[Product].fragments.filter(_.`type` equals "detail").head.html))
      case None => BadRequest
    }
  }

  var cartHtml = Ok(views.html.cart())
  def cart = UserSecured(Env.identityLink) { user => r =>
    Future.successful(cartHtml)
  }

  def retryWhenServiceDown(where: String) = Action {
    Ok(views.html.retry(where, 0))
  }

  def fragmentsByIds(ids: String, `type`: String) = Action.async{
    Product.listFragmentsByIds(ids.split(",").toList, `type`).map(res => Ok(JsArray(res)))
  }

  def logs(addr: String): Enumeratee[JsValue, JsValue] =
    Enumeratee.onIterateeDone{ () => Logger.info(s"$addr - SSE disconnected") }


  def feed() = Action.async{ req =>
    Future.successful(
      Ok.feed(http.Feed.out.get()
          &> logs(req.remoteAddress)
          &> EventSource()
      ).as("text/event-stream"))
  }

  def images(name: String) =  Action.async{
    Env.fileService().get(name).map{
      case None =>
        val index = Random.nextInt(3) + 1
        Play.resourceAsStream(s"public/images/product-$index.jpeg") match {
          case Some(stream) =>
            Result(
              header = ResponseHeader(200),
              body=Enumerator.fromStream(stream)
            )
          case _ =>
            BadRequest
        }
      case Some(file) =>
        val etag = Codecs.sha1(file.data)
        Result(
          header = ResponseHeader(200),
          body=Enumerator(file.data)
        ).withHeaders(ETAG -> etag, CACHE_CONTROL -> "public, max-age=3600")
    }
  }

  val barHtml: Result = Ok(views.html.templates.bar())
  def barTemplate = Action.async{
    Future.successful(barHtml)
  }

}