package com.electricdreams.numo.core.wallet

/**
 * Interface for a temporary, single-mint wallet used in swap flows.
 *
 * This is used for swap-to-Lightning-mint operations where we need to:
 * - Interact with an unknown mint (not in the merchant's allowed list)
 * - Keep the main wallet's proofs and balances untouched
 * - Melt proofs from an incoming token to pay a Lightning invoice
 *
 * The temporary wallet is ephemeral and should be closed after use.
 */
interface TemporaryMintWallet : AutoCloseable {

    /**
     * The mint URL this temporary wallet is connected to.
     */
    val mintUrl: String

    /**
     * Refresh keysets from the mint.
     * Must be called before decoding proofs from a token.
     *
     * @return List of keyset information from the mint
     */
    suspend fun refreshKeysets(): WalletResult<List<KeysetInfo>>

    /**
     * Request a melt quote from this mint for a Lightning invoice.
     *
     * @param bolt11Invoice The BOLT11 Lightning invoice to pay
     * @return Result containing the melt quote or error
     */
    suspend fun requestMeltQuote(bolt11Invoice: String): WalletResult<MeltQuoteResult>

    /**
     * Execute a melt operation using provided proofs (from an incoming token).
     * This is different from WalletProvider.melt() which uses wallet-held proofs.
     *
     * @param quoteId The melt quote ID
     * @param encodedToken The encoded Cashu token containing proofs to melt
     * @return Result containing the melt result or error
     */
    suspend fun meltWithToken(
        quoteId: String,
        encodedToken: String
    ): WalletResult<MeltResult>

    /**
     * Close and cleanup this temporary wallet.
     * After closing, the wallet should not be used.
     */
    override fun close()
}

/**
 * Factory interface for creating temporary mint wallets.
 * Implementations provide wallet instances for unknown mints.
 */
interface TemporaryMintWalletFactory {
    /**
     * Create a temporary wallet for interacting with the specified mint.
     *
     * @param mintUrl The mint URL to connect to
     * @return Result containing the temporary wallet or error
     */
    suspend fun createTemporaryWallet(mintUrl: String): WalletResult<TemporaryMintWallet>
}
