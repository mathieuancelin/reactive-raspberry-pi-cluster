package controllers

import java.io._
import java.util.zip.ZipFile

import akka.util.Timeout
import com.amazing.store.services.CartServiceClient
import config.Env
import play.api.Play.current
import play.api.data.Forms._
import play.api.data._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.{Json, JsValue}
import play.api.mvc.BodyParsers.parse.Multipart
import play.api.mvc.BodyParsers.parse.Multipart.FileInfo
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc._
import services.ProductService

import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble
import scala.io.Source

object Application extends Controller {

  implicit val timeout = Timeout(5 seconds)

  def applicationIsUp() = Action {
    Ok("ok").withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def stats() = Action {
    Ok(Json.obj(
      "avgRate" -> metrics.Messages.avgRate,
      "avgTime" -> metrics.Messages.avgTime
    )).withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def recoverState = Action { request =>
    request.headers.get("authtoken").filter(_ == "q8YKPTJtvkXj18dWecnHIGlqVlnmLzXmLAUd7eQ4xGeIkiP5BuzMpR0AxkFtn3TW").foreach { _ =>
      Env.logger.debug("Starting recover")
      ProductService.recover()
    }
    Ok
  }

  def index() = Action.async {
    val future: Future[List[models.Product]] = ProductService.listProducts(50)
    for{
      list <- future
    } yield {
      Env.logger.debug(s"Products $list")
      Ok(views.html.index(list))
    }
  }

  def orders() = Action.async {
    CartServiceClient.listAllOrders().map(o => Ok(views.html.orders(o)))
  }

  def webSocket = WebSocket.acceptWithActor[JsValue, JsValue]{ request => out =>
      WebBrowserActor.props(out)
  }

  val productFormTuple = Form(
    tuple(
      "label" -> text,
      "description" -> text,
      "imageName" -> optional(text),
      "price" -> bigDecimal
    )
  )

  def addProduct() = Action {
      Ok(views.html.createOrUpdateProduct(productFormTuple, routes.Application.createProduct()))
  }

  def editProduct(id: String) = Action.async {
    models.Product.read(id).map{
      case None => BadRequest
      case Some(prod) =>
        Ok(views.html.createOrUpdateProduct(
          productFormTuple.fill((prod.label, prod.description, Some(prod.image), BigDecimal(prod.price))),
          routes.Application.updateProduct(id)
        ))
    }
  }

  def createProduct = Action.async(parse.multipartFormData(bytesArrayFilePart)) { implicit request =>
    productFormTuple.bindFromRequest.fold(
      formWithErrors => {
        Env.logger.debug(s"Errors : $formWithErrors")
        Future.successful(BadRequest(views.html.createOrUpdateProduct(formWithErrors, routes.Application.createProduct())))
      },
      productData => {
        Env.logger.debug(s"Ok redirect")
        val (label, description, image, price) = productData
        request.body.file("image").map { f =>
          val imageName = formatName(f.ref._1)
          Env.fileService().save(image.getOrElse(imageName), f.ref._1, f.ref._2).flatMap{r=>
            ProductService
              .createProduct(label, description, image.getOrElse(imageName), price.toDouble)
              .map(_ => Redirect(routes.Application.index()))
          }
        }.getOrElse{
          Future.successful(Redirect(routes.Application.index()))
        }
      }
    )
  }

  private def formatName(name: String): String = {
    val regex = "(.*)\\.(jpeg|jpg|png)".r
    name match {
      case regex(start, extension) =>
        s"${start.replaceAll("[^\\p{L}\\p{Nd}]+", "")}.$extension"
      case _ =>
        name.replaceAll("[^\\p{L}\\p{Nd}]+", "")
    }
  }

  private def bytesArrayFilePart: Multipart.PartHandler[FilePart[(String, Array[Byte])]] = {
    Multipart.handleFilePart {
      case FileInfo(partName, filename, contentType) =>
        val baos = new java.io.ByteArrayOutputStream()
        Iteratee.fold[Array[Byte], java.io.ByteArrayOutputStream](baos) {
          (os, data) =>
            os.write(data)
            os
        }.map {
          os =>
            os.close()
            (filename, baos.toByteArray)
        }
    }
  }

  def updateProduct(id: String) = Action.async(parse.multipartFormData(bytesArrayFilePart)) { implicit request =>
    productFormTuple.bindFromRequest.fold(
      formWithErrors => {
        Env.logger.debug(s"Errors : $formWithErrors")
        Future.successful(BadRequest(views.html.createOrUpdateProduct(formWithErrors, routes.Application.createProduct())))
      },
      productData => {
        Env.logger.debug(s"Ok redirect")
        val (label, description, image, price) = productData
        request.body.file("image") match {
          case Some(f) =>
            val imageName = formatName(f.ref._1)
            Env.fileService().save(imageName, f.ref._1, f.ref._2).flatMap{r=>
            ProductService
              .updateProduct(id, label, description, image.getOrElse(imageName), price.toDouble)
            }.map(_ => Redirect(routes.Application.index()))
          case None =>
            ProductService
              .updateProduct(id, label, description, image.get, price.toDouble)
              .map(_ => Redirect(routes.Application.index()))
        }
      }
    )
  }

  def uploadZip = Action.async(parse.temporaryFile) { implicit request =>
    Future {
      val todir: File = new File("/tmp/products")
      todir.mkdirs()
      val file: File = request.body.file
      val mainFolder = unzipFile(file, todir)
      val dataFolder: File = new File(todir, mainFolder)
      val csvFile = new File(dataFolder, "import.csv")
      (Source.fromFile(csvFile, "UTF-8").getLines(), dataFolder)
    }.flatMap{ args =>
      val (lines, dataFolder) = args
      Env.logger.debug(s"$lines")
      val regex = "(.*)\\;(.*)\\;(.*)\\;(.*)".r
      val futureRes: Iterator[Future[Object]] = lines.map {
        case regex(label, description, image, price) =>
          val trim: String = image.trim
          val input = new FileInputStream(new File(dataFolder, trim))
          val array: Array[Byte] = new Array(input.available())
          input.read(array)
          Env.fileService().save(trim, trim, array).flatMap { r =>
            ProductService
              .createProduct(label, description, trim, price.trim.toDouble)
              .map(_ => Redirect(routes.Application.index()))
          }
        case line =>
          Env.logger.debug(s"WRONG FORMAT ====== $line")
          Future.successful(Unit)
      }
      Future.sequence(futureRes).map(_ => Redirect(routes.Application.index()))
    }
  }

  def unzipFile(file: File, todir: File) = {
    val zip = new ZipFile(file)
    val entries = zip.entries()
    val basename = file.getName
    Env.logger.debug(s"Unzipping $basename to $todir")
    var first:Option[String] = None
    while(entries.hasMoreElements){
      val entry = entries.nextElement
      val name = entry.getName
      val entryPath =
        if(name.startsWith(basename)) name.substring(basename.length)
        else name
      first match {
        case None => first = Some(entryPath)
        case _ =>
      }
      println("Extracting to " + todir + "/" + entryPath)
      if(entry.isDirectory){
        new File(todir, entryPath).mkdirs
      }else{
        val istream = zip.getInputStream(entry)
        val out: File = new File(todir, entryPath)
        val ostream = new FileOutputStream(out)
        copyStream(istream, ostream)
        ostream.close
        istream.close
      }
    }
    first.get
  }

  def copyStream(istream : InputStream, ostream : OutputStream) : Unit = {
    var bytes =  new Array[Byte](1024)
    var len = -1
    while({ len = istream.read(bytes, 0, 1024); len != -1 })
      ostream.write(bytes, 0, len)
  }

  def updatePrice(id: String) = Action{
    Env.logger.debug("update price")
    Ok
  }

}