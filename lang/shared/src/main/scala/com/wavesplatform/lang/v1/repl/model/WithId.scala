package com.wavesplatform.lang.v1.repl.model

import com.wavesplatform.lang.v1.repl.model.transactions.ByteString

trait WithId {
  def id: ByteString
}
