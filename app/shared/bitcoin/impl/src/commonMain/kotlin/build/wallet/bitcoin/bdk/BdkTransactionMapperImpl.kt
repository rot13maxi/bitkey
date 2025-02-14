package build.wallet.bitcoin.bdk

import build.wallet.bdk.bindings.BdkAddressBuilder
import build.wallet.bdk.bindings.BdkBlockTime
import build.wallet.bdk.bindings.BdkNetwork
import build.wallet.bdk.bindings.BdkResult.Err
import build.wallet.bdk.bindings.BdkResult.Ok
import build.wallet.bdk.bindings.BdkTransaction
import build.wallet.bdk.bindings.BdkTransactionDetails
import build.wallet.bdk.bindings.BdkWallet
import build.wallet.bdk.bindings.isMine
import build.wallet.bitcoin.BlockTime
import build.wallet.bitcoin.address.BitcoinAddress
import build.wallet.bitcoin.transactions.BitcoinTransaction
import build.wallet.bitcoin.transactions.BitcoinTransaction.ConfirmationStatus.Confirmed
import build.wallet.bitcoin.transactions.BitcoinTransaction.ConfirmationStatus.Pending
import build.wallet.bitcoin.transactions.TransactionDetailDao
import build.wallet.logging.LogLevel.Error
import build.wallet.logging.log
import build.wallet.money.BitcoinMoney

class BdkTransactionMapperImpl(
  private val bdkAddressBuilder: BdkAddressBuilder,
  private val transactionDetailDao: TransactionDetailDao,
) : BdkTransactionMapper {
  override suspend fun createTransaction(
    bdkTransaction: BdkTransactionDetails,
    bdkNetwork: BdkNetwork,
    bdkWallet: BdkWallet,
  ): BitcoinTransaction {
    // Sum of owned inputs of this transaction.
    // AS A RECEIVER
    //    This value is always 0, since you will not own any of the inputs.
    // AS A SENDER
    //    This value will not be 0, since you will have to put up your UTXOs as inputs.
    val sent = BitcoinMoney.sats(bdkTransaction.sent)

    // Sum of owned outputs of this transaction.
    // AS A RECEIVER
    //  The value will not be 0, since you will have some UTXOs that you now own.
    // AS A SENDER
    //  WHEN received == 0
    //    This is likely a sweep-out transaction.
    //  WHEN received != 0
    //    The value here represents the change.
    val received = BitcoinMoney.sats(bdkTransaction.received)

    // If sent amount is zero, that means this transaction is one where you only received.
    val isZeroSumTransaction = sent.isZero && !received.isZero

    val fee = bdkTransaction.fee?.let { BitcoinMoney.sats(it) }
    val transactionWeight = bdkTransaction.transaction?.weight()
    val vsize = bdkTransaction.transaction?.vsize()

    val total =
      if (isZeroSumTransaction) {
        received + (fee ?: BitcoinMoney.zero())
      } else {
        sent - received
      }

    // If this a receive, the subtotal is just how much you received.
    val subtotal =
      if (isZeroSumTransaction) {
        received
      } else {
        total - (fee ?: BitcoinMoney.zero())
      }

    val incoming = sent.isZero

    return BitcoinTransaction(
      id = bdkTransaction.txid,
      recipientAddress =
        bdkTransaction.transaction?.recipientAddress(
          bdkNetwork,
          bdkWallet,
          incoming
        ),
      broadcastTime =
        transactionDetailDao.broadcastTimeForTransaction(
          transactionId = bdkTransaction.txid
        ),
      estimatedConfirmationTime =
        transactionDetailDao.confirmationTimeForTransaction(
          transactionId = bdkTransaction.txid
        ),
      confirmationStatus = bdkTransaction.confirmationStatus(),
      subtotal = subtotal,
      total = total,
      fee = fee,
      vsize = vsize,
      weight = transactionWeight,
      incoming = incoming
    )
  }

  /**
   * Produce our own [BitcoinTransaction.ConfirmationStatus] type from BDK's [BdkTransactionDetails].
   */
  private fun BdkTransactionDetails.confirmationStatus(): BitcoinTransaction.ConfirmationStatus {
    return when (val confirmationTime = confirmationTime) {
      null -> Pending
      else ->
        Confirmed(
          blockTime = confirmationTime.blockTime
        )
    }
  }

  /**
   * Extract the address the transaction was sent to and return, if possible.
   */
  private suspend fun BdkTransaction.recipientAddress(
    bdkNetwork: BdkNetwork,
    bdkWallet: BdkWallet,
    incoming: Boolean,
  ): BitcoinAddress? {
    // Find the TxOut that does or does not correspond to the current wallet based on [incoming]
    val addressTxOut =
      output()
        .firstOrNull {
          when (val isMine = bdkWallet.isMine(it.scriptPubkey)) {
            is Ok -> if (incoming) isMine.value else !isMine.value
            is Err -> {
              // Early return null for [recipientAddress] if we were unable to determine [isMine]
              log(Error, throwable = isMine.error) { "Error calling isMine for wallet script" }
              return null
            }
          }
        } ?: return null // Early return null if [addressTxOut] is null

    // Use [BdkAddressBuilder] to convert the script of that TxOut to an address we can display
    val bdkAddress =
      bdkAddressBuilder.build(
        script = addressTxOut.scriptPubkey,
        network = bdkNetwork
      )
    return when (bdkAddress) {
      is Ok -> BitcoinAddress(bdkAddress.value.asString())
      is Err -> {
        log(Error, throwable = bdkAddress.error) { "Error building bdk address" }
        null
      }
    }
  }
}

private val BdkBlockTime.blockTime: BlockTime
  get() =
    BlockTime(
      height = height,
      timestamp = timestamp
    )
