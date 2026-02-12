package com.electricdreams.numo.core.wallet.impl

import android.util.Log
import com.electricdreams.numo.core.wallet.*
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.MultiMintReceiveOptions
import org.cashudevkit.MultiMintWallet
import org.cashudevkit.QuoteState as CdkQuoteState
import org.cashudevkit.ReceiveOptions
import org.cashudevkit.SplitTarget
import org.cashudevkit.Token as CdkToken
import org.cashudevkit.Wallet as CdkWallet
import org.cashudevkit.WalletConfig
import org.cashudevkit.WalletSqliteDatabase
import org.cashudevkit.generateMnemonic

/**
 * CDK-based implementation of WalletProvider.
 *
 * This implementation wraps the CDK MultiMintWallet to provide wallet
 * operations through the WalletProvider interface.
 *
 * @param walletProvider Function that returns the current CDK MultiMintWallet instance
 */
class CdkWalletProvider(
    private val walletProvider: () -> MultiMintWallet?
) : WalletProvider, TemporaryMintWalletFactory {

    companion object {
        private const val TAG = "CdkWalletProvider"
    }

    private val wallet: MultiMintWallet?
        get() = walletProvider()

    // ========================================================================
    // Balance Operations
    // ========================================================================

    override suspend fun getBalance(mintUrl: String): Satoshis {
        val w = wallet ?: return Satoshis.ZERO
        return try {
            val balanceMap = w.getBalances()
            val amount = balanceMap[mintUrl]?.value?.toLong() ?: 0L
            Satoshis(amount)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting balance for mint $mintUrl: ${e.message}", e)
            Satoshis.ZERO
        }
    }

    override suspend fun getAllBalances(): Map<String, Satoshis> {
        val w = wallet ?: return emptyMap()
        return try {
            val balanceMap = w.getBalances()
            balanceMap.mapValues { (_, amount) -> Satoshis(amount.value.toLong()) }
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

            Log.d(TAG, "Requesting mint quote from $mintUrl for ${amount.value} sats")
            val quote = w.mintQuote(cdkMintUrl, cdkAmount, description)

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
            val quote = w.checkMintQuote(cdkMintUrl, quoteId)

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
            Log.d(TAG, "Minting proofs for quote $quoteId")
            val proofs = w.mint(cdkMintUrl, quoteId, null)

            val totalAmount = proofs.sumOf { it.amount.value.toLong() }
            val result = MintResult(
                proofsCount = proofs.size,
                amount = Satoshis(totalAmount)
            )
            Log.d(TAG, "Minted ${proofs.size} proofs, total ${totalAmount} sats")
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
            Log.d(TAG, "Requesting melt quote from $mintUrl")
            val quote = w.meltQuote(cdkMintUrl, bolt11Invoice, null)

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
            Log.d(TAG, "Executing melt for quote $quoteId")
            val melted = w.meltWithMint(cdkMintUrl, quoteId)

            val result = MeltResult(
                success = melted.state == CdkQuoteState.PAID,
                status = mapQuoteState(melted.state),
                feePaid = Satoshis(melted.feePaid?.value?.toLong() ?: 0L),
                preimage = melted.preimage,
                changeProofsCount = melted.change?.size ?: 0
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
        val w = wallet
            ?: return WalletResult.Failure(WalletError.NotInitialized())

        return try {
            val cdkMintUrl = MintUrl(mintUrl)
            val quote = w.checkMeltQuote(cdkMintUrl, quoteId)

            val result = MeltQuoteResult(
                quoteId = quote.id,
                amount = Satoshis(quote.amount.value.toLong()),
                feeReserve = Satoshis(quote.feeReserve.value.toLong()),
                status = mapQuoteState(quote.state),
                expiryTimestamp = quote.expiry?.toLong()
            )
            WalletResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking melt quote: ${e.message}", e)
            WalletResult.Failure(mapException(e, mintUrl, quoteId))
        }
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

            val receiveOptions = ReceiveOptions(
                amountSplitTarget = SplitTarget.None,
                p2pkSigningKeys = emptyList(),
                preimages = emptyList(),
                metadata = emptyMap()
            )
            val mmReceive = MultiMintReceiveOptions(
                allowUntrusted = false,
                transferToMint = null,
                receiveOptions = receiveOptions
            )

            Log.d(TAG, "Receiving token from mint ${cdkToken.mintUrl().url}")
            w.receive(cdkToken, mmReceive)

            val tokenAmount = cdkToken.value().value.toLong()
            val result = ReceiveResult(
                amount = Satoshis(tokenAmount),
                proofsCount = 0 // CDK doesn't expose proof count directly after receive
            )
            Log.d(TAG, "Token received: ${tokenAmount} sats")
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
                proofsCount = 0, // Would need keyset info to count proofs
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
            val info = w.fetchMintInfo(cdkMintUrl)
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
            val quote = cdkWallet.meltQuote(bolt11Invoice, null)

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
            // Decode token and get proofs with keyset info
            val cdkToken = CdkToken.decode(encodedToken)

            // Refresh keysets to ensure we have the keyset info needed for proofs
            val keysets = cdkWallet.refreshKeysets()
            val proofs = cdkToken.proofs(keysets)

            Log.d(TAG, "Executing melt with ${proofs.size} proofs for quote $quoteId")
            val melted = cdkWallet.meltProofs(quoteId, proofs)

            val result = MeltResult(
                success = melted.state == org.cashudevkit.QuoteState.PAID,
                status = mapQuoteState(melted.state),
                feePaid = Satoshis(melted.feePaid?.value?.toLong() ?: 0L),
                preimage = melted.preimage,
                changeProofsCount = melted.change?.size ?: 0
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
