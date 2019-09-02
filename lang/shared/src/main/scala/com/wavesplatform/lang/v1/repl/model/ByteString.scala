package com.wavesplatform.lang.v1.repl.model

import com.wavesplatform.common.utils.{Base58, Base64}
import io.circe.{Decoder, HCursor}

case class ByteString(bytes: Array[Byte] = Array(0))
object ByteString {
  implicit val decoder: Decoder[ByteString] = (c: HCursor) => Right {
    val str = c.value.asString.get
    if (str.startsWith("base58:")) ByteString(Base58.decode(str.substring(7)))
    else if (str.startsWith("base64:")) ByteString(Base64.decode(str.substring(7)))
    else ByteString(Base58.decode(str))
  }
}