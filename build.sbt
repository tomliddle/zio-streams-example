import Dependencies._

ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.tjl"

val zioVersion = "1.0.14"

lazy val root = (project in file("."))
  .settings(
    name                             := "zio-streams-example",
    libraryDependencies += scalaTest  % Test,
    libraryDependencies += "dev.zio" %% "zio"         % zioVersion,
    libraryDependencies += "dev.zio" %% "zio-streams" % zioVersion,
    libraryDependencies += "dev.zio" %% "zio-kafka"   % "0.17.5",
    libraryDependencies += "dev.zio" %% "zio-json"    % "0.1.5",
  )
