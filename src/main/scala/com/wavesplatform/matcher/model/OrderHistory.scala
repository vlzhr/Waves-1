package com.wavesplatform.matcher.model

import cats.implicits._
import cats.kernel.Monoid
import com.wavesplatform.database.{DBExt, Key, RW}
import com.wavesplatform.matcher.api.DBUtils
import com.wavesplatform.matcher.model.Events._
import com.wavesplatform.matcher.model.LimitOrder.{Filled, OrderStatus}
import com.wavesplatform.matcher.{MatcherKeys, MatcherSettings, OrderAssets}
import com.wavesplatform.metrics.TimerExt
import com.wavesplatform.state._
import kamon.Kamon
import org.iq80.leveldb.DB
import scorex.account.Address
import scorex.transaction.AssetId
import scorex.transaction.assets.exchange.Order
import scorex.utils.ScorexLogging

class OrderHistory(db: DB, settings: MatcherSettings) extends ScorexLogging {
  import OrderHistory._
  import com.wavesplatform.matcher.MatcherKeys._

  private val timer               = Kamon.timer("matcher.order-history.impl")
  private val saveOpenVolumeTimer = timer.refine("action" -> "save-open-volume")
  private val saveOrderInfoTimer  = timer.refine("action" -> "save-order-info")
  private val openVolumeTimer     = timer.refine("action" -> "open-volume")

  private def combine(order: Order, curr: Option[OrderInfo], diff: OrderInfoDiff): OrderInfo = {
    val r = curr match {
      case Some(x) =>
        OrderInfo(
          amount = order.amount,
          filled = x.filled + diff.addExecutedAmount.getOrElse(0L),
          canceled = diff.cancelledByUser.getOrElse(x.canceled),
          minAmount = diff.newMinAmount.orElse(x.minAmount),
          remainingFee = x.remainingFee - diff.executedFee.getOrElse(0L),
          unsafeTotalSpend = Some(OrderInfo.safeSum(x.totalSpend(LimitOrder(order)), diff.lastSpend.getOrElse(0L)))
        )
      case None =>
        val executedAmount = diff.addExecutedAmount.getOrElse(0L)
        val remainingFee   = order.matcherFee - diff.executedFee.getOrElse(0L)
        val canceled       = if (curr.isEmpty) diff.cancelledByUser.map(!_) else diff.cancelledByUser

        OrderInfo(
          amount = order.amount,
          filled = executedAmount,
          canceled = canceled.getOrElse(false),
          minAmount = diff.newMinAmount,
          remainingFee = remainingFee,
          unsafeTotalSpend = diff.lastSpend.orElse(Some(0L))
        )
    }

    // We should return all reserved assets. To do this, let's imagine, that order was filled on 100%
    // TODO: Solve issue without the hack
    if (r.status.isInstanceOf[LimitOrder.Filled]) {
      val lo = LimitOrder(order)
      r.copy(
        remainingFee = 0,
        unsafeTotalSpend = Some(lo.getRawSpendAmount)
      )
    } else r
  }

  private def saveOrderInfo(rw: RW, event: Event): Unit = saveOrderInfoTimer.measure {
    val orderInfoDiffs = collectChanges(event)

    val (_, changes) = orderInfoDiffs.foldLeft((Map.empty: ChangedKeys, Map.empty[Order.Id, OrderInfoChange])) {
      case ((origChangedKeys, origChanges), (order, orderInfoDiff)) =>
        val orderId = order.id()

        val orderInfoOptKey = MatcherKeys.orderInfoOpt(orderId)
        val origInfo        = changedOrElse(origChangedKeys, orderInfoOptKey, rw.get(orderInfoOptKey))

        val combinedInfo = combine(order, origInfo, orderInfoDiff)
        val change       = OrderInfoChange(order, origInfo, combinedInfo)

        log.trace(s"$orderId: ${change.origInfo.fold("[]")(_.status.toString)} -> ${change.updatedInfo.status}")

        rw.put(MatcherKeys.orderInfo(orderId), change.updatedInfo)
        val changedKeys1 = origChangedKeys
          .updated(MatcherKeys.orderInfo(orderId), change.updatedInfo)
          .updated(orderInfoOptKey, Some(change.updatedInfo))

        val changedKeys2 = if (origInfo.isEmpty) {
          saveOrder(rw, order)
          addOrderIndexes(rw, change, changedKeys1)
        } else changedKeys1

        (updateOldestActiveNr(rw, change, changedKeys2), origChanges.updated(orderId, change))
    }

    val opDiff = diff(event, changes)
    saveOpenVolume(rw, opDiff)
  }

  private def collectChanges(event: Event): Seq[(Order, OrderInfoDiff)] = event match {
    case OrderAdded(lo) =>
      Seq((lo.order, OrderInfoDiff(newMinAmount = Some(lo.minAmountOfAmountAsset))))

    case oe: OrderExecuted =>
      val submitted = oe.submittedExecuted
      val counter   = oe.counterExecuted

      Seq(
        (submitted.order,
         OrderInfoDiff(
           addExecutedAmount = Some(oe.executedAmount),
           executedFee = Some(submitted.fee),
           newMinAmount = Some(submitted.minAmountOfAmountAsset),
           lastSpend = Some(submitted.getSpendAmount)
         )),
        (counter.order,
         OrderInfoDiff(
           addExecutedAmount = Some(oe.executedAmount),
           executedFee = Some(counter.fee),
           newMinAmount = Some(counter.minAmountOfAmountAsset),
           lastSpend = Some(counter.getSpendAmount)
         ))
      )

    case OrderCanceled(lo, unmatchable) =>
      // The order should not have Cancelled status, if it was cancelled by unmatchable amounts
      val canceled = !unmatchable
      // Hack to get the right status
      val newMinAmount = if (canceled) None else Some(lo.order.amount)
      Seq((lo.order, OrderInfoDiff(cancelledByUser = Some(canceled), newMinAmount = newMinAmount)))
  }

  def openVolume(address: Address, assetId: Option[AssetId]): Long =
    openVolumeTimer.measure(db.get(MatcherKeys.openVolume(address, assetId)).getOrElse(0L))

  private def saveOpenVolume(rw: RW, opDiff: Map[Address, OpenPortfolio]): Unit = saveOpenVolumeTimer.measure {
    for ((address, op) <- opDiff) {
      val newAssets = Set.newBuilder[Option[AssetId]]
      for ((assetId, amount) <- op.orders if amount != 0) {
        val k = MatcherKeys.openVolume(address, assetId)
        val newValue = safeSum(amount, rw.get(k) match {
          case None =>
            newAssets += assetId
            0L
          case Some(v) => v
        })

        rw.put(k, Some(newValue))
      }

      val r = newAssets.result()
      if (r.nonEmpty) {
        val k         = openVolumeSeqNr(address)
        val prevSeqNr = rw.get(k)
        for ((assetId, offset) <- r.zipWithIndex) {
          rw.put(openVolumeAsset(address, prevSeqNr + offset + 1), assetId)
        }
        rw.put(k, prevSeqNr + r.size)
      }
    }
  }

  private def saveOrder(rw: RW, order: Order): Unit = rw.put(MatcherKeys.order(order.id()), Some(order))

  private type ChangedKeys = Map[Key[_], Any]

  private def changedOrElse[V](changedKeys: ChangedKeys, key: Key[V], orElse: => V): V = changedKeys.getOrElse(key, orElse).asInstanceOf[V]

  private def addOrderIndexes(rw: RW, change: OrderInfoChange, changedKeys: ChangedKeys): ChangedKeys = {
    import change.{order => o}
    val address = o.senderPublicKey.toAddress

    val commonSeqNrKey  = MatcherKeys.addressOrdersSeqNr(address)
    val commonNextSeqNr = changedOrElse(changedKeys, commonSeqNrKey, rw.get(commonSeqNrKey)) + 1

    rw.put(
      MatcherKeys.addressOrders(address, commonNextSeqNr),
      Some(OrderAssets(o.id(), o.getSpendAssetId))
    )

    rw.put(commonSeqNrKey, commonNextSeqNr)

    val pairSeqNrKey  = MatcherKeys.addressOrdersByPairSeqNr(address, o.assetPair)
    val pairNextSeqNr = rw.get(pairSeqNrKey) + 1

    rw.put(
      MatcherKeys.addressOrdersByPair(address, o.assetPair, pairNextSeqNr),
      Some(o.id())
    )

    log.trace(s"Adding order ${o.id()} to $address at $pairNextSeqNr")

    rw.put(pairSeqNrKey, pairNextSeqNr)

    changedKeys + (commonSeqNrKey -> commonNextSeqNr)
  }

  private def updateOldestActiveNr(rw: RW, change: OrderInfoChange, origKeys: ChangedKeys): ChangedKeys = {
    lazy val address              = change.order.senderPublicKey.toAddress
    lazy val oldestActiveSeqNrKey = MatcherKeys.addressOldestActiveOrderSeqNr(address)
    lazy val oldestActiveSeqNr    = changedOrElse[Option[Int]](origKeys, oldestActiveSeqNrKey, rw.get(oldestActiveSeqNrKey))
    lazy val lastSeqNr = changedOrElse(
      origKeys,
      MatcherKeys.addressOrdersSeqNr(address),
      math.max(rw.get(MatcherKeys.addressOrdersSeqNr(address)), 1)
    )

    def findOldestActiveNr(afterNr: Int): Option[Int] =
      (afterNr to lastSeqNr).view
        .drop(1)
        .find { i =>
          val isActive = rw
            .get(MatcherKeys.addressOrders(address, i))
            .exists { orderAssets =>
              !rw.get(MatcherKeys.orderInfo(orderAssets.orderId)).status.isFinal
            }

          isActive
        }

    def update(newOldestActiveNr: Int): ChangedKeys = {
      rw.put(oldestActiveSeqNrKey, Some(newOldestActiveNr))
      origKeys + (oldestActiveSeqNrKey -> newOldestActiveNr)
    }

    if (!change.updatedInfo.status.isFinal) {
      // A new active order
      if (oldestActiveSeqNr.isEmpty) update(lastSeqNr) else origKeys
    } else if (change.origInfo.nonEmpty) {
      // An active order was closed
      oldestActiveSeqNr
        .map { oldestActiveSeqNr =>
          val shouldUpdateOldestActive = rw
            .get(MatcherKeys.addressOrders(address, oldestActiveSeqNr))
            .map(_.orderId == change.order.id())
            .getOrElse {
              // Hope, this is impossible case
              log.warn(s"Can't find nr=$oldestActiveSeqNr order for $address, will update it")
              true
            }

          if (shouldUpdateOldestActive) {
            findOldestActiveNr(oldestActiveSeqNr) match {
              case Some(x) => update(x)
              case None =>
                rw.delete(oldestActiveSeqNrKey)
                origKeys + (oldestActiveSeqNrKey -> None)
            }
          } else origKeys
        }
        .getOrElse(origKeys)
    } else origKeys
  }

  def process(event: Event): Unit       = db.readWrite(saveOrderInfo(_, event))
  def orderInfo(id: ByteStr): OrderInfo = DBUtils.orderInfo(db, id)
  def order(id: ByteStr): Option[Order] = DBUtils.order(db, id)

  def deleteOrder(address: Address, orderId: ByteStr): Boolean = db.readWrite { rw =>
    DBUtils.orderInfo(rw, orderId).status match {
      case Filled(_) | LimitOrder.Cancelled(_) =>
        rw.delete(MatcherKeys.order(orderId))
        rw.delete(MatcherKeys.orderInfo(orderId))
        true
      case _ =>
        false
    }
  }
}

object OrderHistory {
  import OrderInfo.orderStatusOrdering

  case class OrderInfoChange(order: Order, origInfo: Option[OrderInfo], updatedInfo: OrderInfo)

  object OrderHistoryOrdering extends Ordering[(ByteStr, OrderInfo, Option[Order])] {
    def orderBy(oh: (ByteStr, OrderInfo, Option[Order])): (OrderStatus, Long) = (oh._2.status, -oh._3.map(_.timestamp).getOrElse(0L))

    override def compare(first: (ByteStr, OrderInfo, Option[Order]), second: (ByteStr, OrderInfo, Option[Order])): Int = {
      implicitly[Ordering[(OrderStatus, Long)]].compare(orderBy(first), orderBy(second))
    }
  }

  private case class OrderInfoDiff(addExecutedAmount: Option[Long] = None,
                                   cancelledByUser: Option[Boolean] = None,
                                   newMinAmount: Option[Long] = None,
                                   executedFee: Option[Long] = None,
                                   lastSpend: Option[Long] = None)

  def diff(event: Event, changes: Map[ByteStr, OrderInfoChange]): Map[Address, OpenPortfolio] = {
    changes.values.foldLeft(Map.empty[Address, OpenPortfolio]) {
      case (r, change) =>
        Monoid.combine(
          r,
          event match {
            case _: OrderCanceled => if (change.origInfo.isEmpty) Map.empty else diffReturn(change)
            case _                => if (change.origInfo.isEmpty) diffNew(change) else diffUpdate(change)
          }
        )
    }
  }

  private def diffNew(change: OrderInfoChange): Map[Address, OpenPortfolio] = {
    import change.{order, updatedInfo}
    val lo             = LimitOrder(order)
    val maxSpendAmount = lo.getRawSpendAmount
    val remainingSpend = maxSpendAmount - updatedInfo.totalSpend(lo)
    val remainingFee   = if (lo.feeAcc == lo.rcvAcc) math.max(updatedInfo.remainingFee - lo.getReceiveAmount, 0L) else updatedInfo.remainingFee

    Map(
      order.sender.toAddress -> OpenPortfolio(
        Monoid.combine(
          Map(order.getSpendAssetId -> remainingSpend),
          Map(lo.feeAsset           -> remainingFee)
        )
      )
    )
  }

  private def diffUpdate(change: OrderInfoChange): Map[Address, OpenPortfolio] = {
    import change.{order, updatedInfo}
    val prev         = change.origInfo.getOrElse(throw new IllegalStateException("origInfo must be defined"))
    val lo           = LimitOrder(order)
    val changedSpend = prev.totalSpend(lo) - updatedInfo.totalSpend(lo)
    val changedFee   = -releaseFee(order, prev.remainingFee, updatedInfo.remainingFee)

    Map(
      order.sender.toAddress -> OpenPortfolio(
        Monoid.combine(
          Map(order.getSpendAssetId -> changedSpend),
          Map(lo.feeAsset           -> changedFee)
        )
      )
    )
  }

  private def diffReturn(change: OrderInfoChange): Map[Address, OpenPortfolio] = {
    import change.{order, updatedInfo}
    val lo             = LimitOrder(order)
    val maxSpendAmount = lo.getRawSpendAmount
    val remainingSpend = updatedInfo.totalSpend(lo) - maxSpendAmount
    val remainingFee   = -releaseFee(order, updatedInfo.remainingFee, 0)

    Map(
      order.sender.toAddress -> OpenPortfolio(
        Monoid.combine(
          Map(order.getSpendAssetId -> remainingSpend),
          Map(lo.feeAsset           -> remainingFee)
        )
      )
    )
  }

  /**
    * @return How much reserved fee we should return during this update
    */
  private def releaseFee(totalReceiveAmount: Long, matcherFee: Long, prevRemaining: Long, updatedRemaining: Long): Long = {
    val executedBefore = matcherFee - prevRemaining
    val restReserved   = math.max(matcherFee - totalReceiveAmount - executedBefore, 0L)

    val executed = prevRemaining - updatedRemaining
    math.min(executed, restReserved)
  }

  private def releaseFee(order: Order, prevRemaining: Long, updatedRemaining: Long): Long = {
    val lo = LimitOrder(order)
    if (lo.rcvAsset == lo.feeAsset) releaseFee(lo.getReceiveAmount, order.matcherFee, prevRemaining, updatedRemaining)
    else prevRemaining - updatedRemaining
  }

}
