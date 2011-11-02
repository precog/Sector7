name := "sector7"

organization := "com.reportgrid"

version := "0.1"

scalaVersion := "2.9.1"
 
libraryDependencies ++= Seq(
  "org.jclouds" % "jclouds-all" % "1.2.1",
  "org.jclouds.driver" % "jclouds-jsch" % "1.2.1",
  "net.lag" % "configgy" % "2.0.0" intransitive(),
  "ch.qos.logback" % "logback-classic" % "0.9.30",
  "com.weiglewilczek.slf4s" %% "slf4s" % "1.0.7"
)

