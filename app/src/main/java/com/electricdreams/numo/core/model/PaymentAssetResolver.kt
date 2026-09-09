package com.electricdreams.numo.core.model

/**
 * Resolves an immutable charge asset at the boundary of the payment flow.
 *
 * Standard units are globally fungible and intentionally discard an issuer supplied by a caller.
 * Custom units require an exact mint scope so two mints using the same unit identifier can never
 * be treated as the same asset accidentally.
 */
object PaymentAssetResolver {

    sealed class Result {
        data class Resolved(val asset: AssetId) : Result()

        data class Rejected(val reason: RejectionReason) : Result()
    }

    enum class RejectionReason {
        INVALID_UNIT,
        RESERVED_UNIT,
        MISSING_CUSTOM_ISSUER,
        UNSUPPORTED_ASSET,
    }

    @JvmStatic
    fun resolve(
        rawUnit: String?,
        rawIssuerScope: String?,
        supportedAssets: Collection<AssetId>,
    ): Result {
        val unit = UnitId.ofOrNull(rawUnit)
            ?: return Result.Rejected(RejectionReason.INVALID_UNIT)
        if (unit.isReserved) {
            return Result.Rejected(RejectionReason.RESERVED_UNIT)
        }

        val requestedAsset = if (UnitDescriptor.defaultFor(unit).kind == UnitKind.CUSTOM) {
            val issuerScope = rawIssuerScope
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return Result.Rejected(RejectionReason.MISSING_CUSTOM_ISSUER)
            AssetId.mintScoped(unit, issuerScope)
        } else {
            AssetId.global(unit)
        }

        return if (requestedAsset in supportedAssets) {
            Result.Resolved(requestedAsset)
        } else {
            Result.Rejected(RejectionReason.UNSUPPORTED_ASSET)
        }
    }
}
