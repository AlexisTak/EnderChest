package io.github.iltotore.enderchest.client

import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory
import io.github.iltotore.enderchest.FileAnalyzer

import scala.concurrent.ExecutionContextExecutor
import scala.util.Failure

object Main {

  def main(args: Array[String]): Unit = {

    val config = ConfigFactory.parseString(
      """
        |akka.http.client.parsing.max-chunk-size=1g
        |akka.http.client.parsing.max-content-length=infinite""".stripMargin)

    implicit val system: ActorSystem = ActorSystem("my-system", ConfigFactory.load(config))
    implicit val materializer: Materializer = Materializer(system)
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher
    val path = Paths.get(System.getProperty("user.dir"), "downloads/")

    implicit val progressStatus: ByteDownloadAction =
      (downloaded, max) => println(s"Progress: ${downloaded.doubleValue / max * 100}% ($downloaded/$max)")
    implicit val downloadLogger: FileDownloadAction = file => println(s"Downloading ${file.getName}...")
    implicit val deleteLogger: FileDeleteAction = file => println(s"Deleting ${file.getName}...")

    val analyzer = new FileAnalyzer(path)()
    val client = new EnderClient("http://localhost:8080", analyzer)
    val time = System.currentTimeMillis()
    client.checkFiles
      .flatMap(_ => client.update)
      .onComplete {
        case Failure(exception) => exception.printStackTrace()

        case _ => println(s"All: ${System.currentTimeMillis() - time}ms")
      }
  }
}
