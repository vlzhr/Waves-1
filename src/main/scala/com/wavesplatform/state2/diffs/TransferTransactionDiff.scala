package com.wavesplatform.state2.diffs

import cats.implicits._
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state2._
import com.wavesplatform.state2.reader.SnapshotStateReader
import scorex.account.Address
import scorex.transaction.ValidationError
import scorex.transaction.ValidationError.GenericError
import scorex.transaction.assets.TransferTransaction

import scala.util.Right

object TransferTransactionDiff {
  def apply(state: SnapshotStateReader, s: FunctionalitySettings, blockTime: Long, height: Int)(
      tx: TransferTransaction): Either[ValidationError, Diff] = {
    val sender = Address.fromPublicKey(tx.sender.publicKey)

    val isInvalidEi = for {
      recipient <- state.resolveAliasEi(tx.recipient)
      _ <- Either.cond((tx.feeAssetId >>= state.assetDescription >>= (_.script)).isEmpty,
                       (),
                       GenericError("Smart assets can't participate in TransferTransactions as a fee"))
      portfolios = (tx.assetId match {
        case None =>
          Map(sender -> Portfolio(-tx.amount, LeaseBalance.empty, Map.empty)).combine(
            Map(recipient -> Portfolio(tx.amount, LeaseBalance.empty, Map.empty))
          )
        case Some(aid) =>
          Map(sender -> Portfolio(0, LeaseBalance.empty, Map(aid -> -tx.amount))).combine(
            Map(recipient -> Portfolio(0, LeaseBalance.empty, Map(aid -> tx.amount)))
          )
      }).combine(
        tx.feeAssetId match {
          case None => Map(sender -> Portfolio(-tx.fee, LeaseBalance.empty, Map.empty))
          case Some(aid) =>
            val senderPf = Map(sender -> Portfolio(0, LeaseBalance.empty, Map(aid -> -tx.fee)))
            val sponsorPf = state
              .assetDescription(aid)
              .collect {
                case desc if desc.sponsorship > 0 =>
                  val feeInWaves = BigDecimal(tx.fee) * BigDecimal(Sponsorship.FeeUnit) / BigDecimal(desc.sponsorship) ///safe mul
                  Map(desc.issuer.toAddress -> Portfolio(-feeInWaves.toLongExact, LeaseBalance.empty, Map(aid -> tx.fee)))
              }
              .getOrElse(Map.empty)
            senderPf.combine(sponsorPf)
        }
      )
      assetIssued    = tx.assetId.forall(state.assetDescription(_).isDefined)
      feeAssetIssued = tx.feeAssetId.forall(state.assetDescription(_).isDefined)
    } yield (portfolios, blockTime > s.allowUnissuedAssetsUntil && !(assetIssued && feeAssetIssued))

    isInvalidEi match {
      case Left(e) => Left(e)
      case Right((portfolios, invalid)) =>
        if (invalid)
          Left(GenericError(s"Unissued assets are not allowed after allowUnissuedAssetsUntil=${s.allowUnissuedAssetsUntil}"))
        else
          Right(Diff(height, tx, portfolios))
    }
  }
}
