import AssemblyKeys._

name := "sector7"

organization := "com.reportgrid"

version := "0.1.3"

scalaVersion := "2.9.1"

resolvers ++= Seq(
  "Sonatype"    at "http://nexus.scala-tools.org/content/repositories/public",
  "Scala Tools" at "http://scala-tools.org/repo-snapshots/",
  "JBoss"       at "http://repository.jboss.org/nexus/content/groups/public/",
  "Akka"        at "http://akka.io/repository/",
  "GuiceyFruit" at "http://guiceyfruit.googlecode.com/svn/repo/releases/"
)

libraryDependencies ++= Seq(
  "org.jclouds" % "jclouds-all" % "1.2.1",
  "org.jclouds.driver" % "jclouds-jsch" % "1.2.1",
  "net.lag" % "configgy" % "2.0.0" intransitive(),
  "ch.qos.logback" % "logback-classic" % "0.9.30",
  "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.7",
  "com.reportgrid" %% "blueeyes" % "0.5.0-SNAPSHOT" % "compile"
)

mainClass := Some("com.reportgrid.sector7.inventory.DeploymentServer")

assembleArtifact in packageBin := false

seq(assemblySettings: _*)
