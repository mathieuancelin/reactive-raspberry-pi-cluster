import sbt._
import Keys._
import play.Play.autoImport._
import PlayKeys._
import play.PlayScala

object StoreCartBuild extends Build {

  val appName = """store-cart"""
  val appScalaVersion = "2.11.1"
  val appVersion= "1.0-SNAPSHOT"

  lazy val root = Project(id = appName, base = file(".")).enablePlugins(PlayScala).settings(
    libraryDependencies ++= Seq(
      cache,
      ws,
      "com.amazing.store" %% "store-commons" % "1.0-SNAPSHOT",
      "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.4",
      "com.github.krasserm" %% "akka-persistence-cassandra" % "0.3.2"
    ),
    organization := "org.amazing.store",
    scalaVersion := appScalaVersion,
    incOptions := incOptions.value.withNameHashing(true),
    resolvers ++= Seq(
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
      "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"
    )

  )
}