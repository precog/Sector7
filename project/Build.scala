import sbt._
import Keys._

object Sector7Build extends Build {
  // Common settings between all subprojects
  scalaVersion := "2.9.1"

  organization := "com.reportgrid"

  resolvers ++= Seq(
    "Sonatype"    at "http://nexus.scala-tools.org/content/repositories/public",
    "Scala Tools" at "http://scala-tools.org/repo-snapshots/",
    "JBoss"       at "http://repository.jboss.org/nexus/content/groups/public/",
    "Akka"        at "http://akka.io/repository/",
    "GuiceyFruit" at "http://guiceyfruit.googlecode.com/svn/repo/releases/"
  )

  val commonDeps = Seq(
    "net.lag" % "configgy" % "2.0.0" intransitive(),
    "ch.qos.logback" % "logback-classic" % "0.9.30",
    "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.7"
  )

  // The projects themselves
  lazy val deployment = Project(id = "deployment", base = file("deployment"))

  lazy val provisioning = Project(id = "provisioning", base = file("provisioning"))

  lazy val root = Project(id = "sector7", base = file(".")) aggregate (deployment, provisioning)
}