package com.electricdreams.numo.core.wallet

/**
 * Sealed class hierarchy for wallet errors.
 * Provides type-safe error handling independent of implementation.
 */
sealed class WalletError : Exception() {

    /** Wallet is not initialized or not ready for operations */
    data class NotInitialized(
        override val message: String = "Wallet not initialized"
    ) : WalletError()

    /** Invalid mint URL format */
    data class InvalidMintUrl(
        val mintUrl: String,
        override val message: String = "Invalid mint URL: $mintUrl"
    ) : WalletError()

    /** Mint is not reachable or not responding */
    data class MintUnreachable(
        val mintUrl: String,
        override val message: String = "Mint unreachable: $mintUrl",
        override val cause: Throwable? = null
    ) : WalletError()

    /** Quote not found or expired */
    data class QuoteNotFound(
        val quoteId: String,
        override val message: String = "Quote not found: $quoteId"
    ) : WalletError()

    /** Quote has expired */
    data class QuoteExpired(
        val quoteId: String,
        override val message: String = "Quote expired: $quoteId"
    ) : WalletError()

    /** Quote is not in the expected state for the operation */
    data class InvalidQuoteState(
        val quoteId: String,
        val expectedState: QuoteStatus,
        val actualState: QuoteStatus,
        override val message: String = "Quote $quoteId in invalid state: expected $expectedState, got $actualState"
    ) : WalletError()

    /** Insufficient balance for the operation */
    data class InsufficientBalance(
        val required: Satoshis,
        val available: Satoshis,
        override val message: String = "Insufficient balance: required ${required.value} sats, available ${available.value} sats"
    ) : WalletError()

    /** Token is invalid or malformed */
    data class InvalidToken(
        override val message: String = "Invalid token format",
        override val cause: Throwable? = null
    ) : WalletError()

    /** Token has already been spent */
    data class TokenAlreadySpent(
        override val message: String = "Token has already been spent"
    ) : WalletError()

    /** Proofs are invalid or verification failed */
    data class InvalidProofs(
        override val message: String = "Invalid proofs",
        override val cause: Throwable? = null
    ) : WalletError()

    /** Melt operation failed */
    data class MeltFailed(
        val quoteId: String,
        override val message: String = "Melt operation failed for quote: $quoteId",
        override val cause: Throwable? = null
    ) : WalletError()

    /** Mint operation failed */
    data class MintFailed(
        val quoteId: String,
        override val message: String = "Mint operation failed for quote: $quoteId",
        override val cause: Throwable? = null
    ) : WalletError()

    /** Network error during wallet operation */
    data class NetworkError(
        override val message: String = "Network error",
        override val cause: Throwable? = null
    ) : WalletError()

    /** Generic/unknown error wrapping underlying exception */
    data class Unknown(
        override val message: String = "Unknown wallet error",
        override val cause: Throwable? = null
    ) : WalletError()
}

/**
 * Result type for wallet operations that can fail.
 * Provides a functional way to handle success/failure without exceptions.
 */
sealed class WalletResult<out T> {
    data class Success<T>(val value: T) : WalletResult<T>()
    data class Failure(val error: WalletError) : WalletResult<Nothing>()

    /**
     * Returns the value if success, or throws the error if failure.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error
    }

    /**
     * Returns the value if success, or null if failure.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    /**
     * Returns the value if success, or the default value if failure.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> default
    }

    /**
     * Maps the success value using the provided transform function.
     */
    inline fun <R> map(transform: (T) -> R): WalletResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /**
     * Flat maps the success value using the provided transform function.
     */
    inline fun <R> flatMap(transform: (T) -> WalletResult<R>): WalletResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    /**
     * Executes the given block if this is a success.
     */
    inline fun onSuccess(block: (T) -> Unit): WalletResult<T> {
        if (this is Success) block(value)
        return this
    }

    /**
     * Executes the given block if this is a failure.
     */
    inline fun onFailure(block: (WalletError) -> Unit): WalletResult<T> {
        if (this is Failure) block(error)
        return this
    }

    companion object {
        /**
         * Wraps a potentially throwing operation into a WalletResult.
         */
        inline fun <T> runCatching(block: () -> T): WalletResult<T> = try {
            Success(block())
        } catch (e: WalletError) {
            Failure(e)
        } catch (e: Exception) {
            Failure(WalletError.Unknown(e.message ?: "Unknown error", e))
        }
    }
}
