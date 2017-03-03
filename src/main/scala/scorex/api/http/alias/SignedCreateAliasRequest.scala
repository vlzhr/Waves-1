package scorex.api.http.alias

import play.api.libs.json.{Format, Json}


case class SignedCreateAliasRequest(senderPublicKey: String, alias: String, fee: Long, signature: String)

object SignedCreateAliasRequest {
  implicit val signedCreateAliasFormat: Format[SignedCreateAliasRequest] = Json.format
}

