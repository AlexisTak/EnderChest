package io.github.iltotore.enderchest.server

import java.io.{File, PrintWriter, StringWriter}
import java.nio.file.{Files, Paths}
import java.util.logging.Level
import java.util.regex.Pattern

import akka.actor.{ActorSystem, Terminated}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import io.github.iltotore.enderchest.EndLogger._
import io.github.iltotore.enderchest.Implicits._
import io.github.iltotore.enderchest.{FileAnalyzer, FileChecksum}
import org.simpleyaml.configuration.file.YamlConfiguration
import spray.json.{JsNumber, JsObject, JsString, _}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class Server(configFile: File)(implicit system: ActorSystem, materializer: Materializer, contextExecutor: ExecutionContextExecutor) {

  var analyzer: FileAnalyzer = _
  val http: HttpExt = Http()
  var dataChunkSize: Int = _

  def start(): Unit = {
    if (!configFile.exists()) Files.copy(getClass.getResourceAsStream("/config.yml"), configFile.toPath)
    val config = YamlConfiguration.loadConfiguration(configFile)
    info("Building file analyzer...")
    dataChunkSize = config.getInt("file.chunk-size", 8192)
    val pattern = Pattern.compile(config.getString("file.exclude")).asPredicate()
    val exclude = pattern.test _
    val defaultParallelism = Option(Runtime.getRuntime.availableProcessors())
    analyzer = new FileAnalyzer(Paths.get(System.getProperty("user.dir"), config.getString("file.directory")))(
      exclude = exclude,
      maxDepth = Option(config.getInt("file.max-depth")).filter(_ > 0),
      threadCount = Option(config.getInt("file.parallelism")).filter(_ > 0).orElse(defaultParallelism).getOrElse(1)
    )
    system.whenTerminated.onComplete {
      case Failure(exception) => log(Level.WARNING, exception.getMessage, exception)
      case _ =>
    }
    info("Opening network connexion...")
    http.bindAndHandleAsync(request => {
      request.entity.dataBytes
        .map(_.utf8String.parseJson.asJsObject)
        .map(processChecksum)
        .filter(_.isDefined)
        .map(_.get)
        .runFold(ArrayBuffer[FileChecksum]())((array, checksum) => array += checksum)
        .map(_.toVector)
        .map(receiveUpdatePart)
        .recover(e => {
          val stacktraceWriter = new StringWriter()
          val stacktracePrinter = new PrintWriter(stacktraceWriter)
          e.printStackTrace()
          e.printStackTrace(stacktracePrinter)
          HttpResponse(status = StatusCodes.InternalServerError, entity = HttpEntity(s"${e.getMessage}: $stacktraceWriter"))
        })
    }, config.getString("network.ip"), config.getInt("network.port")).onComplete {

      case Success(_) => info(s"Listening ${config.getString("network.ip")}:${config.getInt("network.port")}")

      case Failure(exception) => log(Level.WARNING, exception.getMessage, exception)
    }
  }

  def receiveUpdatePart(receivedChecksums: Vector[FileChecksum]): HttpResponse = {

    var count = 0L

    val requiredChecksums = for (checksum <- analyzer.getChecksums.toVector if !receivedChecksums.exists(checksum.equals)) yield {
      count += checksum.length
      checksum
    }

    val upload = Source(requiredChecksums)
      .flatMapConcat(checksum => FileIO.fromPath(analyzer.getDirectory.resolve(checksum.relativePath), chunkSize = dataChunkSize)
        .prepend(Source(Vector(
          ByteString("ENDERCHEST_FILE_SEPARATOR"),
          ByteString(checksum.relativePath.toString)
        ))))


    val toDelete = Source(receivedChecksums)
      .filterNot(checksum => analyzer.getChecksums.exists(_.relativePath.isSimilarTo(checksum.relativePath)))
      .map(checksum => ByteString(checksum.relativePath.toString))
      .prepend(Source.single(ByteString("ENDERCHEST_FILE_REMOVE")))

    val byteCount = ByteString(count.toString)

    val chunks = toDelete.prepend(upload).prepend(Source.single(byteCount)).map(string => HttpEntity.Chunk(string))

    info(s"Sending $count bytes...")
    HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`application/octet-stream`, chunks))
  }

  def stop(): Future[Terminated] = http.shutdownAllConnectionPools().flatMap(_ => system.terminate())

  def processChecksum(json: JsObject): Option[FileChecksum] = {
    json.getFields("path", "hash") match {
      case Seq(JsString(path), JsNumber(hash)) =>
        val relPath = Paths.get(path)
        val length = analyzer.getDirectory.resolve(relPath).toFile.length()
        Option(FileChecksum(relPath, hash.toIntExact, length))

      case _ => Option.empty
    }
  }
}