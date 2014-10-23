import sbt._
import Keys._

object StoreCommonsBuild extends Build {

  val appName = """store-commons"""
  val appScalaVersion = "2.11.1"
  val appVersion = "1.0-SNAPSHOT"

  lazy val root = Project(id = appName, base = file(".")).settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.3.4",
      "com.typesafe.akka" %% "akka-cluster" % "2.3.4",
      "com.codahale.metrics" % "metrics-core" % "3.0.2",
      "com.google.guava" % "guava" % "17.0",
      "com.typesafe.play" %% "play-json" % "2.3.0",
      "com.typesafe.play" %% "play" % "2.3.0",
      "org.jgroups" % "jgroups" % "3.4.4.Final",
      "com.datastax.cassandra" % "cassandra-driver-core" % "2.0.3",
      "com.google.code.findbugs" % "jsr305" % "2.0.1"
    ),
    organization := "com.amazing.store",
    version := appVersion,
    incOptions := incOptions.value.withNameHashing(true)
  )
}