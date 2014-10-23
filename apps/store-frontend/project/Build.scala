import sbt._
import Keys._
import play.Play.autoImport._
import play.PlayScala
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.SbtWeb.autoImport._
import net.ground5hark.sbt.concat.SbtConcat
import net.ground5hark.sbt.concat.SbtConcat.autoImport._
import com.slidingautonomy.sbt.filter.SbtFilter.autoImport._
import com.typesafe.sbt.uglify.SbtUglify.autoImport._
import net.ground5hark.sbt.css.SbtCssCompress.autoImport._
import com.typesafe.sbt.digest.SbtDigest.autoImport._
import com.typesafe.sbt.gzip.SbtGzip.autoImport._




object StoreFrontendBuild extends Build {

  val appName = """store-frontend"""
  val appScalaVersion = "2.11.1"
  val appVersion= "1.0-SNAPSHOT"


  lazy val root = Project(id = appName, base = file("."))
    .enablePlugins(PlayScala)
    .enablePlugins(SbtWeb)
    .enablePlugins(SbtConcat)
    .settings(
      libraryDependencies ++= Seq(
        cache,
        ws,
        filters,
        "com.amazing.store" %% "store-commons" % "1.0-SNAPSHOT",
        "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.4"
      ),
      version := appVersion,
      organization := "org.amazing.store",
      incOptions := incOptions.value.withNameHashing(true),
      resolvers ++= Seq(
        "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
        "ed eustace" at "http://edeustace.com/repository/releases"
      ),
      pipelineStages in Assets := Seq(concat, filter, cssCompress, digest, gzip),
      Concat.groups := Seq(
      "concat-product.js" -> group(Seq(
          "javascripts/vendor/jquery.js",
          "javascripts/vendor/handlebars-v1.3.0.js",
          "javascripts/vendor/underscore.js",
          "javascripts/foundation/foundation.js",
          "javascripts/foundation/foundation.alert.js",
          "javascripts/amazing/app/env.js",
          "javascripts/amazing/app/http.js",
          "javascripts/amazing/app/contextService.js",
          "javascripts/amazing/app/cartService.js",
          "javascripts/amazing/app/productService.js",
          "javascripts/amazing/app/topBar.js",
          "javascripts/amazing/app/product.js"
        )
      ),
      "concat-cart.js" -> group(Seq(
        "javascripts/vendor/jquery.js",
        "javascripts/vendor/handlebars-v1.3.0.js",
        "javascripts/vendor/underscore.js",
        "javascripts/foundation/foundation.js",
        "javascripts/foundation/foundation.alert.js",
        "javascripts/amazing/app/env.js",
        "javascripts/amazing/app/http.js",
        "javascripts/amazing/app/contextService.js",
        "javascripts/amazing/app/cartService.js",
        "javascripts/amazing/app/productService.js",
        "javascripts/amazing/app/topBar.js",
        "javascripts/amazing/app/product.js",
        "javascripts/amazing/app/cart.js"
      ))
    )
    )

}