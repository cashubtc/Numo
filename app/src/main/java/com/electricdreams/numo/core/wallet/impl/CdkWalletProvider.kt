package com.electricdreams.numo.core.wallet.impl

import android.util.Log
import com.electricdreams.numo.core.wallet.*
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.PaymentMethod
import org.cashudevkit.QuoteState as CdkQuoteState
import org.cashudevkit.ReceiveOptions
import org.cashudevkit.SplitTarget
import org.cashudevkit.Token as CdkToken
import org.cashudevkit.Wallet as CdkWallet
import org.cashudevkit.WalletConfig
import org.cashudevkit.WalletRepository
import org.cashudevkit.WalletSqliteDatabase
import org.cashudevkit.generateMnemonic

/**
 * CDK-based implementation of WalletProvider.
 *
 * This implementation wraps the CDK WalletRepository to provide wallet
 * operations through the WalletProvider interface.
 *
 * @param walletProvider Function that returns the current CDK WalletRepository instance
 */
class CdkWalletProvider(
    private val walletProvider: () -> WalletRepository?
) : WalletProvider, TemporaryMintWalletFactory {

    companion object {
        private const val TAG = "CdkWalletProvider"
    }

    private val wallet: WalletRepository?
        get() = walletProvider()

    // ========================================================================
    // Balance Operations
    // ========================================================================

    override suspend fun getBalance(mintUrl: String): Satoshis {
        val w = wallet ?: return Satoshis.ZERO
        return try {
            val balances = w.getBalances()
            val normalizedInput = mintUrl.removeSuffix("/")
            for (entry in balances) {
                val cdkUrl = entry.key.mintUrl.url.removeSuffix("/")
                if (cdkUrl == normalizedInput && entry.key.unit == CurrencyUnit.Sat) {
                    return Satoshis(entry.value.value.toLong())
                }
            }
            Satoshis.ZERO
        } catch (e: Exception) {
            Log.e(TAG, "Error getting balance for mint $mintUrl: ${e.message}", e)
            Satoshis.ZERO
        }
    }

    override suspend fun getAllBalances(): Map<String, Satoshis> {
        val w = wallet ?: return emptyMap()
        return try {
            val balanceMap = w.getBalances()
            balanceMap
                .filter { it.key.unit == CurrencyUnit.Sat }
                .mapKeys { it.key.mintUrl.url.removeSuffix("/") }
                .mapValues { Satoshis(it.value.value.toLong()) }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all balances: ${e.message}", e)
            emptyMap()
        }
    }

    // ========================================================================
    // Lightning Receive Flow (Mint Quote -> Mint)
    // ========================================================================

    override suspend fun requestMintQuote(
        mintUrl: String,
        amount: Satoshis,
        description: String?
    ): WalletResult<MintQuoteResult> {
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkMintUrl = MintUrl(mintUrl)
            val cdkAmount = CdkAmount(amount.value.toULong())
            val mintWallet = w.getWallet(cdkMintUrl, CurrencyUnit.Sat)
                ?: return WalletResult.Failure(WalletError.MintUnreachable(mintUrl, "Wallet not found for mint"))

            Log.d(TAG, "Requesting mint quote from $mintUrl for ${amount.value} sats")
            val quote = mintWallet.mintQuote(PaymentMethod.Bolt11, cdkAmount, description, null)

            val result = MintQuoteResult(
                quoteId = quote.id,
                bolt11Invoice = quote.request,
                amount = amount,
                status = mapQuoteState(quote.state),
                expiryTimestamp = quote.expiry?.toLong()
            )
            Log.d(TAG, "Mint quote created: id=${quote.id}")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting mint quote: ${e.message}", e)
            WalletResult.Failure(mapException(e, mintUrl))
        }
    }

    override suspend fun checkMintQuote(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MintQuoteStatusResult> {
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkMintUrl = MintUrl(mintUrl)
            val mintWallet = w.getWallet(cdkMintUrl, CurrencyUnit.Sat)
                ?: return WalletResult.Failure(WalletError.MintUnreachable(mintUrl, "Wallet not found for mint"))
            val quote = mintWallet.checkMintQuote(quoteId)

            val result = MintQuoteStatusResult(
                quoteId = quote.id,
                status = mapQuoteState(quote.state),
                expiryTimestamp = quote.expiry?.toLong()
            )
            Log.d(TAG, "Mint quote status: id=${quote.id}, state=${result.status}")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking mint quote: ${e.message}", e)
            WalletResult.Failure(mapException(e, mintUrl, quoteId))
        }
    }

    override suspend fun mint(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MintResult> {
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkMintUrl = MintUrl(mintUrl)
            val mintWallet = w.getWallet(cdkMintUrl, CurrencyUnit.Sat)
                ?: return WalletResult.Failure(WalletError.MintUnreachable(mintUrl, "Wallet not found for mint"))

            Log.d(TAG, "Minting proofs for quote $quoteId")
            val proofs = mintWallet.mint(quoteId, SplitTarget.None, null)

            val result = MintResult(
                proofsCount = proofs.size,
                amount = Satoshis.ZERO // CDK Proof doesn't expose amount directly
            )
            Log.d(TAG, "Minted ${proofs.size} proofs")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error minting proofs: ${e.message}", e)
            WalletResult.Failure(WalletError.MintFailed(quoteId, e.message ?: "Mint failed", e))
        }
    }

    // ========================================================================
    // Lightning Spend Flow (Melt Quote -> Melt)
    // ========================================================================

    override suspend fun requestMeltQuote(
        mintUrl: String,
        bolt11Invoice: String
    ): WalletResult<MeltQuoteResult> {
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkMintUrl = MintUrl(mintUrl)
            val mintWallet = w.getWallet(cdkMintUrl, CurrencyUnit.Sat)
                ?: return WalletResult.Failure(WalletError.MintUnreachable(mintUrl, "Wallet not found for mint"))

            Log.d(TAG, "Requesting melt quote from $mintUrl")
            val quote = mintWallet.meltQuote(PaymentMethod.Bolt11, bolt11Invoice, null, null)

            val result = MeltQuoteResult(
                quoteId = quote.id,
                amount = Satoshis(quote.amount.value.toLong()),
                feeReserve = Satoshis(quote.feeReserve.value.toLong()),
                status = mapQuoteState(quote.state),
                expiryTimestamp = quote.expiry?.toLong()
            )
            Log.d(TAG, "Melt quote created: id=${quote.id}, amount=${result.amount.value}, feeReserve=${result.feeReserve.value}")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting melt quote: ${e.message}", e)
            WalletResult.Failure(mapException(e, mintUrl))
        }
    }

    override suspend fun melt(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MeltResult> {
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkMintUrl = MintUrl(mintUrl)
            val mintWallet = w.getWallet(cdkMintUrl, CurrencyUnit.Sat)
                ?: return WalletResult.Failure(WalletError.MintUnreachable(mintUrl, "Wallet not found for mint"))

            Log.d(TAG, "Executing melt for quote $quoteId")
            val prepared = mintWallet.prepareMelt(quoteId)
            val finalized = prepared.confirm()

            val result = MeltResult(
                success = finalized.state == CdkQuoteState.PAID,
                status = mapQuoteState(finalized.state),
                feePaid = Satoshis(finalized.feePaid?.value?.toLong() ?: 0L),
                preimage = finalized.preimage,
                changeProofsCount = finalized.change?.size ?: 0
            )
            Log.d(TAG, "Melt result: success=${result.success}, feePaid=${result.feePaid.value}")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing melt: ${e.message}", e)
            WalletResult.Failure(WalletError.MeltFailed(quoteId, e.message ?: "Melt failed", e))
        }
    }

    override suspend fun checkMeltQuote(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MeltQuoteResult> {
        // CDK 0.15.1 Wallet does not expose a checkMeltQuote method directly.
        // Re-request the melt quote is not possible without the bolt11, so we
        // return a pending status as a safe fallback.
        Log.w(TAG, "checkMeltQuote not supported by CDK Wallet, returning PENDING for quoteId=$quoteId")
        return WalletResult.Failure(WalletError.Unknown("checkMeltQuote not supported"))
    }

    // ========================================================================
    // Cashu Token Operations
    // ========================================================================

    override suspend fun receiveToken(encodedToken: String): WalletResult<ReceiveResult> {
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkToken = CdkToken.decode(encodedToken)

            if (cdkToken.unit() != CurrencyUnit.Sat) {
                return WalletResult.Failure(
                    WalletError.InvalidToken("Unsupported token unit: ${cdkToken.unit()}")
                )
            }

            val mintUrl = cdkToken.mintUrl()
            val mintWallet = w.getWallet(mintUrl, CurrencyUnit.Sat)
                ?: return WalletResult.Failure(
                    WalletError.MintUnreachable(mintUrl.url, "Wallet not found for mint")
                )

            val receiveOptions = ReceiveOptions(
                amountSplitTarget = SplitTarget.None,
                p2pkSigningKeys = emptyList(),
                preimages = emptyList(),
                metadata = emptyMap()
            )

            val totalAmount = cdkToken.value().value.toLong()
            Log.d(TAG, "Receiving token from mint ${mintUrl.url}, amount=$totalAmount sats")
            mintWallet.receive(cdkToken, receiveOptions)

            val result = ReceiveResult(
                amount = Satoshis(totalAmount),
                proofsCount = 0
            )
            Log.d(TAG, "Token received: $totalAmount sats")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving token: ${e.message}", e)
            val error = when {
                e.message?.contains("already spent", ignoreCase = true) == true ->
                    WalletError.TokenAlreadySpent()
                e.message?.contains("invalid", ignoreCase = true) == true ->
                    WalletError.InvalidToken(e.message ?: "Invalid token", e)
                else -> WalletError.Unknown(e.message ?: "Token receive failed", e)
            }
            WalletResult.Failure(error)
        }
    }

    override suspend fun getTokenInfo(encodedToken: String): WalletResult<TokenInfo> {
        return try {
            val cdkToken = CdkToken.decode(encodedToken)
            val result = TokenInfo(
                mintUrl = cdkToken.mintUrl().url,
                amount = Satoshis(cdkToken.value().value.toLong()),
                proofsCount = 0,
                unit = cdkToken.unit().toString().lowercase()
            )
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting token info: ${e.message}", e)
            WalletResult.Failure(WalletError.InvalidToken(e.message ?: "Invalid token", e))
        }
    }

    // ========================================================================
    // Mint Information
    // ========================================================================

    override suspend fun fetchMintInfo(mintUrl: String): WalletResult<MintInfoResult> {
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkMintUrl = MintUrl(mintUrl)
            val mintWallet = w.getWallet(cdkMintUrl, CurrencyUnit.Sat)
                ?: return WalletResult.Failure(WalletError.MintUnreachable(mintUrl, "Wallet not found for mint"))
            val info = mintWallet.fetchMintInfo()
                ?: return WalletResult.Failure(WalletError.MintUnreachable(mintUrl, "Mint returned no info"))

            val result = MintInfoResult(
                name = info.name,
                description = info.description,
                descriptionLong = info.descriptionLong,
                pubkey = info.pubkey,
                version = info.version?.let { MintVersionInfo(it.name, it.version) },
                motd = info.motd,
                iconUrl = info.iconUrl,
                contacts = info.contact?.map { MintContactInfo(it.method, it.info) } ?: emptyList()
            )
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching mint info for $mintUrl: ${e.message}", e)
            WalletResult.Failure(WalletError.MintUnreachable(mintUrl, cause = e))
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun isReady(): Boolean = wallet != null

    // ========================================================================
    // TemporaryMintWalletFactory Implementation
    // ========================================================================

    override suspend fun createTemporaryWallet(mintUrl: String): WalletResult<TemporaryMintWallet> {
        return try {
            val tempMnemonic = generateMnemonic()
            val tempDb = WalletSqliteDatabase.newInMemory()
            val config = WalletConfig(targetProofCount = 10u)

            val cdkWallet = CdkWallet(
                mintUrl,
                CurrencyUnit.Sat,
                tempMnemonic,
                tempDb,
                config
            )

            Log.d(TAG, "Created temporary wallet for mint $mintUrl")
            WalletResult.Success(CdkTemporaryMintWallet(mintUrl, cdkWallet))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating temporary wallet for $mintUrl: ${e.message}", e)
            WalletResult.Failure(WalletError.MintUnreachable(mintUrl, cause = e))
        }
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun mapQuoteState(state: CdkQuoteState): QuoteStatus = when (state) {
        CdkQuoteState.UNPAID -> QuoteStatus.UNPAID
        CdkQuoteState.PENDING -> QuoteStatus.PENDING
        CdkQuoteState.PAID -> QuoteStatus.PAID
        CdkQuoteState.ISSUED -> QuoteStatus.ISSUED
        else -> QuoteStatus.UNKNOWN
    }

    private fun mapException(
        e: Exception,
        mintUrl: String? = null,
        quoteId: String? = null
    ): WalletError {
        val message = e.message?.lowercase() ?: ""
        return when {
            message.contains("not found") && quoteId != null ->
                WalletError.QuoteNotFound(quoteId)
            message.contains("expired") && quoteId != null ->
                WalletError.QuoteExpired(quoteId)
            message.contains("insufficient") ->
                WalletError.InsufficientBalance(Satoshis.ZERO, Satoshis.ZERO)
            message.contains("network") || message.contains("connection") || message.contains("timeout") ->
                WalletError.NetworkError(e.message ?: "Network error", e)
            mintUrl != null && (message.contains("unreachable") || message.contains("failed to connect")) ->
                WalletError.MintUnreachable(mintUrl, cause = e)
            else ->
                WalletError.Unknown(e.message ?: "Unknown error", e)
        }
    }
}

/**
 * CDK-based implementation of TemporaryMintWallet.
 */
internal class CdkTemporaryMintWallet(
    override val mintUrl: String,
    private val cdkWallet: CdkWallet
) : TemporaryMintWallet {

    companion object {
        private const val TAG = "CdkTempMintWallet"
    }

    override suspend fun refreshKeysets(): WalletResult<List<KeysetInfo>> {
        return try {
            val keysets = cdkWallet.refreshKeysets()
            val result = keysets.map { keyset ->
                KeysetInfo(
                    id = keyset.id.toString(),
                    active = keyset.active,
                    unit = keyset.unit.toString().lowercase()
                )
            }
            Log.d(TAG, "Refreshed ${result.size} keysets from $mintUrl")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing keysets: ${e.message}", e)
            WalletResult.Failure(WalletError.MintUnreachable(mintUrl, cause = e))
        }
    }

    override suspend fun requestMeltQuote(bolt11Invoice: String): WalletResult<MeltQuoteResult> {
        return try {
            Log.d(TAG, "Requesting melt quote from $mintUrl")
            val quote = cdkWallet.meltQuote(PaymentMethod.Bolt11, bolt11Invoice, null, null)

            val result = MeltQuoteResult(
                quoteId = quote.id,
                amount = Satoshis(quote.amount.value.toLong()),
                feeReserve = Satoshis(quote.feeReserve.value.toLong()),
                status = mapQuoteState(quote.state),
                expiryTimestamp = quote.expiry?.toLong()
            )
            Log.d(TAG, "Melt quote: id=${quote.id}, amount=${result.amount.value}, feeReserve=${result.feeReserve.value}")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting melt quote: ${e.message}", e)
            WalletResult.Failure(WalletError.Unknown(e.message ?: "Melt quote failed", e))
        }
    }

    override suspend fun meltWithToken(
        quoteId: String,
        encodedToken: String
    ): WalletResult<MeltResult> {
        return try {
            val cdkToken = CdkToken.decode(encodedToken)

            // Receive token into this wallet so its proofs are available for melt
            val receiveOptions = ReceiveOptions(
                amountSplitTarget = SplitTarget.None,
                p2pkSigningKeys = emptyList(),
                preimages = emptyList(),
                metadata = emptyMap()
            )
            cdkWallet.receive(cdkToken, receiveOptions)

            Log.d(TAG, "Executing melt for quote $quoteId")
            val prepared = cdkWallet.prepareMelt(quoteId)
            val finalized = prepared.confirm()

            val result = MeltResult(
                success = finalized.state == org.cashudevkit.QuoteState.PAID,
                status = mapQuoteState(finalized.state),
                feePaid = Satoshis(finalized.feePaid?.value?.toLong() ?: 0L),
                preimage = finalized.preimage,
                changeProofsCount = finalized.change?.size ?: 0
            )
            Log.d(TAG, "Melt result: success=${result.success}, state=${result.status}")
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing melt: ${e.message}", e)
            WalletResult.Failure(WalletError.MeltFailed(quoteId, e.message ?: "Melt failed", e))
        }
    }

    override fun close() {
        try {
            cdkWallet.close()
            Log.d(TAG, "Temporary wallet closed for $mintUrl")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing temporary wallet: ${e.message}", e)
        }
    }

    private fun mapQuoteState(state: org.cashudevkit.QuoteState): QuoteStatus = when (state) {
        org.cashudevkit.QuoteState.UNPAID -> QuoteStatus.UNPAID
        org.cashudevkit.QuoteState.PENDING -> QuoteStatus.PENDING
        org.cashudevkit.QuoteState.PAID -> QuoteStatus.PAID
        org.cashudevkit.QuoteState.ISSUED -> QuoteStatus.ISSUED
        else -> QuoteStatus.UNKNOWN
    }
}
