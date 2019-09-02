package com.wavesplatform.lang.v1.repl.model.transaction

import com.wavesplatform.lang.v1.repl.model._

case class InvokeScriptTransaction(
  id: ByteString,
  fee: Long,
  timestamp: Long,
  height: Int,
  `type`: Byte,
  version: Byte,
  proofs: List[ByteString],
  senderPublicKey: Account,
  dApp: String,
  call: FunctionCall
) extends Transaction with WithProofs with WithId


object InvokeScriptTransaction {
  val CONTRACT_INVOKE = 16
}