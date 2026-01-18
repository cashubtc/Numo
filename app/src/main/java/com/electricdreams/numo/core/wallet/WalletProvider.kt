package com.electricdreams.numo.core.wallet

/**
 * Main interface for wallet operations.
 *
 * This abstraction allows swapping between different wallet implementations
 * (e.g., CDK, BTCPayServer + btcnutserver) without changing the consuming code.
 *
 * All methods are suspending functions as they may involve network I/O.
 */
interface WalletProvider {

    // ========================================================================
    // Balance Operations
    // ========================================================================

    /**
     * Get the balance for a specific mint.
     *
     * @param mintUrl The mint URL to get balance for
     * @return Balance in satoshis, or 0 if mint is not registered or has no balance
     */
    suspend fun getBalance(mintUrl: String): Satoshis

    /**
     * Get balances for all registered mints.
     *
     * @return Map of mint URL to balance in satoshis
     */
    suspend fun getAllBalances(): Map<String, Satoshis>

    // ========================================================================
    // Lightning Receive Flow (Mint Quote -> Mint)
    // ========================================================================

    /**
     * Request a mint quote to receive Lightning payment.
     * This creates a Lightning invoice that, when paid, allows minting proofs.
     *
     * @param mintUrl The mint to request quote from
     * @param amount Amount in satoshis to receive
     * @param description Optional description for the payment
     * @return Result containing the quote details or error
     */
    suspend fun requestMintQuote(
        mintUrl: String,
        amount: Satoshis,
        description: String? = null
    ): WalletResult<MintQuoteResult>

    /**
     * Check the status of an existing mint quote.
     *
     * @param mintUrl The mint URL
     * @param quoteId The quote ID to check
     * @return Result containing the quote status or error
     */
    suspend fun checkMintQuote(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MintQuoteStatusResult>

    /**
     * Mint proofs after a Lightning invoice has been paid.
     * Should only be called when quote status is PAID.
     *
     * @param mintUrl The mint URL
     * @param quoteId The quote ID for which to mint proofs
     * @return Result containing the mint result or error
     */
    suspend fun mint(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MintResult>

    // ========================================================================
    // Lightning Spend Flow (Melt Quote -> Melt)
    // ========================================================================

    /**
     * Request a melt quote to pay a Lightning invoice.
     *
     * @param mintUrl The mint to request quote from
     * @param bolt11Invoice The BOLT11 Lightning invoice to pay
     * @return Result containing the quote details or error
     */
    suspend fun requestMeltQuote(
        mintUrl: String,
        bolt11Invoice: String
    ): WalletResult<MeltQuoteResult>

    /**
     * Execute a melt operation to pay a Lightning invoice.
     * Uses proofs from the wallet to pay the invoice via the mint.
     *
     * @param mintUrl The mint URL
     * @param quoteId The melt quote ID
     * @return Result containing the melt result or error
     */
    suspend fun melt(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MeltResult>

    /**
     * Check the status of an existing melt quote.
     *
     * @param mintUrl The mint URL
     * @param quoteId The quote ID to check
     * @return Result containing the quote status or error
     */
    suspend fun checkMeltQuote(
        mintUrl: String,
        quoteId: String
    ): WalletResult<MeltQuoteResult>

    // ========================================================================
    // Cashu Token Operations
    // ========================================================================

    /**
     * Receive (redeem) a Cashu token.
     * The token's proofs are received into the wallet.
     *
     * @param encodedToken The encoded Cashu token string (cashuA..., cashuB..., crawB...)
     * @return Result containing the receive result or error
     */
    suspend fun receiveToken(encodedToken: String): WalletResult<ReceiveResult>

    /**
     * Get information about a Cashu token without redeeming it.
     *
     * @param encodedToken The encoded Cashu token string
     * @return Result containing token info or error
     */
    suspend fun getTokenInfo(encodedToken: String): WalletResult<TokenInfo>

    // ========================================================================
    // Mint Information
    // ========================================================================

    /**
     * Fetch information about a mint.
     *
     * @param mintUrl The mint URL to fetch info for
     * @return Result containing mint info or error
     */
    suspend fun fetchMintInfo(mintUrl: String): WalletResult<MintInfoResult>

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Check if the wallet provider is ready for operations.
     */
    fun isReady(): Boolean
}
