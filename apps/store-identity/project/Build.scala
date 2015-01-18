import sbt._
import Keys._
import play.Play.autoImport._
import PlayKeys._
import play.PlayScala

object StoreIdentityBuild extends Build {

  val appName = """store-identity"""
  val appScalaVersion = "2.11.1"
  val appVersion= "1.0-SNAPSHOT"

  lazy val root = Project(id = appName, base = file(".")).enablePlugins(PlayScala).settings(
    libraryDependencies ++= Seq(
      cache,
      ws,
      "com.amazing.store" %% "store-commons" % "1.0-SNAPSHOT",
      "com.typesafe.akka" %% "akka-persistence-experimental" % "2.3.4"
    ),
    version := appVersion,
    scalaVersion := appScalaVersion,
    organization := "org.amazing.store",
    incOptions := incOptions.value.withNameHashing(true),
    resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"
  )
}