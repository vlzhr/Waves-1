package scorex.api.http

import javax.ws.rs.Path

import akka.http.scaladsl.server.Route
import com.wavesplatform.network.Checkpoint
import com.wavesplatform.settings.{CheckpointsSettings, RestAPISettings}
import com.wavesplatform.state2.ByteStr
import io.swagger.annotations._
import play.api.libs.json._
import scorex.crypto.EllipticCurveImpl
import scorex.transaction.{History, TransactionParser}

@Path("/blocks")
@Api(value = "/blocks")
case class BlocksApiRoute(settings: RestAPISettings, checkpointsSettings: CheckpointsSettings, history: History, broadcastCheckpoint: Checkpoint => Unit) extends ApiRoute {

  // todo: make this configurable and fix integration tests
  val MaxBlocksPerRequest = 100

  override lazy val route =
    pathPrefix("blocks") {
      signature ~ first ~ last ~ at ~ seq ~ height ~ heightEncoded ~ child ~ address ~ delay ~ checkpoint
    }

  @Path("/address/{address}/{from}/{to}")
  @ApiOperation(value = "Address", notes = "Get list of blocks generated by specified address", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "from", value = "Start block height", required = true, dataType = "integer", paramType = "path"),
    new ApiImplicitParam(name = "to", value = "End block height", required = true, dataType = "integer", paramType = "path"),
    new ApiImplicitParam(name = "address", value = "Wallet address ", required = true, dataType = "string", paramType = "path")
  ))
  def address: Route = (path("address" / Segment / IntNumber / IntNumber) & get) { case (address, start, end) =>
    if (end >= 0 && start >= 0 && end - start >= 0 && end - start < MaxBlocksPerRequest) {
      val blocks = JsArray(
        (start to end).map { height =>
          (history.blockAt(height), height)
        }.filter(_._1.isDefined)
          .map { pair => (pair._1.get, pair._2) }
          .filter(_._1.signerData.generator.address == address).map { pair =>
          pair._1.json + ("height" -> Json.toJson(pair._2))
        })
      complete(blocks)
    } else complete(TooBigArrayAllocation)
  }

  @Path("/child/{signature}")
  @ApiOperation(value = "Child", notes = "Get children of specified block", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded signature", required = true, dataType = "string", paramType = "path")
  ))
  def child: Route = (path("child" / Segment) & get) { encodedSignature =>
    withBlock(history, encodedSignature) { block =>
      complete(history.child(block).map(_.json).getOrElse[JsObject](
        Json.obj("status" -> "error", "details" -> "No child blocks")))
    }
  }

  @Path("/delay/{signature}/{blockNum}")
  @ApiOperation(value = "Average delay",
    notes = "Average delay in milliseconds between last $blockNum blocks starting from block with $signature", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded signature", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "blockNum", value = "Number of blocks to count delay", required = true, dataType = "string", paramType = "path")
  ))
  def delay: Route = (path("delay" / Segment / IntNumber) & get) { (encodedSignature, count) =>
    withBlock(history, encodedSignature) { block =>
      complete(history.averageDelay(block, count).map(d => Json.obj("delay" -> d))
        .getOrElse[JsObject](Json.obj("status" -> "error", "details" -> "Internal error")))
    }
  }

  @Path("/height/{signature}")
  @ApiOperation(value = "Height", notes = "Get height of a block by its Base58-encoded signature", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded signature", required = true, dataType = "string", paramType = "path")
  ))
  def heightEncoded: Route = (path("height" / Segment) & get) { encodedSignature =>
    if (encodedSignature.length > TransactionParser.SignatureStringLength)
      complete(InvalidSignature)
    else {
      ByteStr.decodeBase58(encodedSignature).toOption.toRight(InvalidSignature)
        .flatMap(s => history.heightOf(s).toRight(BlockNotExists)) match {
        case Right(h) => complete(Json.obj("height" -> h))
        case Left(e) => complete(e)
      }
    }
  }

  @Path("/height")
  @ApiOperation(value = "Height", notes = "Get blockchain height", httpMethod = "GET")
  def height: Route = (path("height") & get) {
    complete(Json.obj("height" -> history.height()))
  }

  @Path("/at/{height}")
  @ApiOperation(value = "At", notes = "Get block at specified height", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "height", value = "Block height", required = true, dataType = "integer", paramType = "path")
  ))
  def at: Route = (path("at" / IntNumber) & get) { height =>
    history.blockAt(height).map(_.json) match {
      case Some(json) => complete(json + ("height" -> JsNumber(height)))
      case None => complete(Json.obj("status" -> "error", "details" -> "No block for this height"))
    }
  }

  @Path("/seq/{from}/{to}")
  @ApiOperation(value = "Seq", notes = "Get block at specified heights", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "from", value = "Start block height", required = true, dataType = "integer", paramType = "path"),
    new ApiImplicitParam(name = "to", value = "End block height", required = true, dataType = "integer", paramType = "path")
  ))
  def seq: Route = (path("seq" / IntNumber / IntNumber) & get) { (start, end) =>
    if (end >= 0 && start >= 0 && end - start >= 0 && end - start < MaxBlocksPerRequest) {
      val blocks = JsArray(
        (start to end).flatMap { height =>
          history.blockAt(height).map(_.json + ("height" -> Json.toJson(height)))
        })
      complete(blocks)
    } else complete(TooBigArrayAllocation)
  }


  @Path("/last")
  @ApiOperation(value = "Last", notes = "Get last block data", httpMethod = "GET")
  def last: Route = (path("last") & get) {
    val height = history.height()
    val lastBlock = history.blockAt(height).get
    complete(lastBlock.json + ("height" -> Json.toJson(height)))
  }

  @Path("/first")
  @ApiOperation(value = "First", notes = "Get genesis block data", httpMethod = "GET")
  def first: Route = (path("first") & get) {
    complete(history.genesis.json + ("height" -> Json.toJson(1)))
  }

  @Path("/signature/{signature}")
  @ApiOperation(value = "Signature", notes = "Get block by a specified Base58-encoded signature", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "signature", value = "Base58-encoded signature", required = true, dataType = "string", paramType = "path")
  ))
  def signature: Route = (path("signature" / Segment) & get) { encodedSignature =>
    if (encodedSignature.length > TransactionParser.SignatureStringLength) complete(InvalidSignature) else {
      ByteStr.decodeBase58(encodedSignature).toOption.toRight(InvalidSignature)
        .flatMap(s => history.blockById(s).toRight(BlockNotExists)) match {
        case Right(block) => complete(block.json + ("height" -> history.heightOf(block.uniqueId).map(Json.toJson(_)).getOrElse(JsNull)))
        case Left(e) => complete(e)
      }
    }
  }

  @Path("/checkpoint")
  @ApiOperation(value = "Checkpoint", notes = "Broadcast checkpoint of blocks", httpMethod = "POST")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "message", value = "Checkpoint message", required = true, paramType = "body",
      dataType = "scorex.network.Checkpoint")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json with response or error")
  ))
  def checkpoint: Route = {
    def validateCheckpoint(checkpoint: Checkpoint): Option[ApiError] = {
      if (EllipticCurveImpl.verify(checkpoint.signature, checkpoint.toSign, checkpointsSettings.publicKey.arr)) None
      else Some(InvalidSignature)
    }

    (path("checkpoint") & post) {
      json[Checkpoint] { checkpoint =>
        validateCheckpoint(checkpoint) match {
          case Some(apiError) => apiError
          case None =>
            broadcastCheckpoint(checkpoint)
            Json.obj("message" -> "Checkpoint broadcasted")
        }
      }
    }
  }
}
