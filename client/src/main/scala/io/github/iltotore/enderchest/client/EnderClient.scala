package io.github.iltotore.enderchest.client

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCodes}
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.github.iltotore.enderchest.FileChecksum.Protocol
import io.github.iltotore.enderchest.{FileAnalyzer, FileChecksum}
import spray.json._

import scala.concurrent.{ExecutionContextExecutor, Future}

/**
 * The core class of the client.
 *
 * @param address      the server address as String.
 * @param fileAnalyzer the file analyzer used to generate checksum.
 * @param actor        the actor used to manage concurrency and http interactions.
 * @param context      the execution context associated to the given actor.
 * @param materializer the materializer used to materialize http responses.
 */
class EnderClient(address: String, fileAnalyzer: FileAnalyzer)
                 (implicit actor: ActorSystem, context: ExecutionContextExecutor, materializer: Materializer) {


  private implicit val http: HttpExt = Http(actor)

  /**
   * Generate checksums.
   *
   * @return the number of indexed files.
   */
  def checkFiles: Future[Int] = fileAnalyzer.check

  /**
   * Download required files, update outdated ones and remove unused files.
   *
   * @return the Future of this task.
   */
  def update(implicit onProgress: ByteDownloadAction = (_, _) => (),
             onDownloadingFile: FileDownloadAction = _ => (),
             onDeletingFile: FileDeleteAction = _ => ()): Future[Done] = {

    implicit val protocol: RootJsonFormat[FileChecksum] = Protocol(fileAnalyzer.getDirectory)


    val checksums = Source(fileAnalyzer.getChecksums.toVector)
      .map(checksum => ByteString(checksum.toJson.toString()))

    val entity = HttpEntity(ContentTypes.`application/json`, checksums)
    http.singleRequest(HttpRequest(uri = address, entity = entity))
      .flatMap(response => {
        if (response._1 == StatusCodes.OK) response.entity match {
          case HttpEntity.Chunked(_, chunks) =>
            chunks
              .filterNot(_.isLastChunk)
              .map(_.data())
              .runFold(new ChunkedDownloader(fileAnalyzer.getDirectory.toFile, fileAnalyzer.exclude, onProgress, onDownloadingFile, onDeletingFile))((downloader, data) =>
                downloader(data))
              .map(_.close())

          case _ => throw new IllegalStateException("Received wrong response: " + response)
        } else throw new IllegalStateException("Received wrong response: " + response)
      })
  }
}

object EnderClient {

  val DEFAULT_SYSTEM: ActorSystem = ActorSystem("enderchest")
  val DEFAULT_EXECUTION_CONTEXT: ExecutionContextExecutor = DEFAULT_SYSTEM.dispatcher
  val DEFAULT_MATERIALIZER: Materializer = Materializer(DEFAULT_SYSTEM)

  /**
   * Used to make more user-friendly companion constructor.
   *
   * @param address      the server address as String.
   * @param fileAnalyzer the file analyzer used to generate checksum.
   * @param actorSystem  the actor used to manage concurrency and http interactions. Optional.
   * @param context      the execution context associated to the given actor. Optional.
   * @param materializer the materializer used to materialize http responses. Optional.
   */
  def apply(address: String, fileAnalyzer: FileAnalyzer,
            actorSystem: ActorSystem = DEFAULT_SYSTEM,
            context: ExecutionContextExecutor = DEFAULT_EXECUTION_CONTEXT,
            materializer: Materializer = DEFAULT_MATERIALIZER): EnderClient =
    new EnderClient(address, fileAnalyzer)(actorSystem, context, materializer)
}