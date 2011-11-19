import AssemblyKeys._

name := "deployment"

version := "0.1.3"

libraryDependencies ++= commonDeps ++ Seq(
  "org.jclouds" % "jclouds-all" % "1.2.1",
  "org.jclouds.driver" % "jclouds-jsch" % "1.2.1",
  "com.reportgrid" %% "blueeyes" % "0.5.0-SNAPSHOT" % "compile"
)

mainClass := Some("com.reportgrid.sector7.inventory.DeploymentServer")

assembleArtifact in packageBin := false

seq(assemblySettings: _*)
