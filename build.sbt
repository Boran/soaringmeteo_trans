scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  // grib2 manipulation
  "edu.ucar"             % "grib"            % "5.3.1",
  "ch.qos.logback"       % "logback-classic" % "1.2.3",
  // HTTP requests
  "org.jsoup"            % "jsoup"           % "1.13.1",
  "com.lihaoyi"         %% "requests"        % "0.5.2",
  // Files manipulation
  "com.lihaoyi"         %% "os-lib"          % "0.6.3",
  // CSV
  "com.nrinaudo"        %% "kantan.csv"      % "0.6.0",
  // Quantities and refined types
  "org.typelevel"       %% "squants"         % "1.6.0",
  "eu.timepit"          %% "refined"         % "0.9.13",
  // JSON
  "io.circe"            %% "circe-literal"   % "0.13.0",
  "io.circe"            %% "circe-jawn"      % "0.13.0" % Compile,
  "io.circe"            %% "circe-parser"    % "0.13.0",
  // Testing
  "com.eed3si9n.verify" %% "verify"          % "0.2.0"  % Test
)

run / fork := true
javaOptions ++= Seq("-Xmx8g", "-Xms1g")
Universal / javaOptions ++= javaOptions.value.map(opt => s"-J$opt")

testFrameworks += new TestFramework("verify.runner.Framework")

resolvers += "Unidata All" at "https://artifacts.unidata.ucar.edu/repository/unidata-all"

Global / onChangedBuildSource := ReloadOnSourceChanges

enablePlugins(GraalVMNativeImagePlugin)
Compile / mainClass := Some("org.soaringmeteo.gfs.Main")
graalVMNativeImageGraalVersion := Some("20.2.0")
graalVMNativeImageOptions ++= Seq(
  "--enable-https",
  "--verbose",
  "--no-fallback",
  "--static",
  "-H:+ReportExceptionStackTraces"
)
// To re-generate reflect-config.json file:
// javaOptions += "-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/org.soaringmeteo/soaringmeteo"

TaskKey[Unit]("deploy") := {
  IO.move(
    (GraalVMNativeImage / packageBin).value,
    baseDirectory.value / ".." / ".." / "gfs" / "makeGFSJson"
  )
}

TaskKey[Unit]("downloadGribAndMakeJson") := {
  (Compile / runMain).toTask(" org.soaringmeteo.gfs.Main /home/julien/workspace/dev/Boran/soaringmeteo/src/makeGFSJson/target/grib /home/julien/workspace/dev/Boran/soaringmeteo/src/makeGFSJson/target/forecast").value
}

TaskKey[Unit]("makeGfsJson") := {
  (Compile / runMain).toTask(" org.soaringmeteo.gfs.MakeGFSJson /home/julien/workspace/dev/Boran/soaringmeteo/src/makeGFSJson/target/grib /home/julien/workspace/dev/Boran/soaringmeteo/src/makeGFSJson/target/forecast").value
}

TaskKey[Unit]("makeWrfJson") := {
  (Compile / runMain).toTask(" org.soaringmeteo.wrf.MakeWRFJson /home/julien/workspace/dev/Boran/soaringmeteo/wrf/wrf-loc.csv /home/julien/workspace/dev/Boran/soaringmeteo/src/makeGFSJson 2020-09-29_Init2020092700Z+54h.nc /home/julien/workspace/dev/Boran/soaringmeteo/src/makeGFSJson/target/soarwrf").value
}

TaskKey[Unit]("downloadGribFiles") := {
  (Compile / runMain).toTask(" org.soaringmeteo.gfs.DownloadGribFiles /home/julien/workspace/dev/Boran/soaringmeteo/src/makeGFSJson/target/grib").value
}
