package com.wavesplatform.lang.v1.repl.model.transactions

import com.wavesplatform.lang.v1.repl.model.WithId

object IssueTransaction {
  val ISSUE = 3
}

trait IssueTransaction extends Transaction with Signable with WithId {
  def name: String
  def description: String
  def quantity: Long
  def decimals: Byte
  def isReissuable: Boolean
}