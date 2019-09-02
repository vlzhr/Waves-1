package com.wavesplatform.lang.v1.repl.model.transaction

import com.wavesplatform.lang.v1.repl.model.{Account, ByteString, WithSignature}

case class BurnTransactionV1(
    id: ByteString,
    signature: ByteString,
    assetId: String,
    amount: Long,
    senderPublicKey: Account,
    fee: Long,
    timestamp: Long,
    height: Int,
    `type`: Byte,
    version: Byte
) extends WithSignature with BurnTransaction
