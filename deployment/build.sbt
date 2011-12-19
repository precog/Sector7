import AssemblyKeys._

name := "deployment"

version := "0.2.0"

resolvers ++= Seq("ReportGrid repo" at            "http://nexus.reportgrid.com/content/repositories/releases",
                  "ReportGrid snapshot repo" at   "http://nexus.reportgrid.com/content/repositories/snapshots")

credentials += Credentials(Path.userHome / ".ivy2" / ".rgcredentials")

libraryDependencies ++= commonDeps ++ Seq(
  "org.jclouds" % "jclouds-all" % "1.2.1",
  "org.jclouds.driver" % "jclouds-jsch" % "1.2.1",
  "com.reportgrid" %% "blueeyes" % "0.5.0-SNAPSHOT" % "compile"
)

mainClass := Some("com.reportgrid.sector7.inventory.DeploymentServer")

assembleArtifact in packageBin := false

seq(assemblySettings: _*)
