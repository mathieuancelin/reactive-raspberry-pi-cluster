import play.PlayScala
import sbt._
import play.Play.autoImport._
import sbt.Keys._

object StoreBackendBuild extends Build {

  val appName = """store-backend"""
  val appScalaVersion = "2.11.1"
  val appVersion= "1.0-SNAPSHOT"


  lazy val root = Project(id=appName, base = file(".")).enablePlugins(PlayScala).settings(
    libraryDependencies ++= Seq(
      cache,
      ws,
      "com.amazing.store" %% "store-commons" % "1.0-SNAPSHOT",
      "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.4",
      "com.github.krasserm" %% "akka-persistence-cassandra" % "0.3.2"
    ),
    organization := "com.amazing.store",
    version := appVersion,
    incOptions := incOptions.value.withNameHashing(true),
    resolvers ++= Seq(
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
      "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"
    )
  )
}