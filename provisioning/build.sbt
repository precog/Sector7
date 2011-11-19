import AssemblyKeys._

name := "provisioning"

version := "0.0.1"

libraryDependencies ++= commonDeps ++ Seq(
  "org.jclouds"        % "jclouds-all"  % "1.2.1",
  "org.jclouds.driver" % "jclouds-jsch" % "1.2.1",
  "joda-time"          % "joda-time"    % "1.6.2"
)