/**
 * Numo webhook payload schema (payment.received, payloadVersion = 2).
 *
 * Notes for server implementers:
 * - `token` is intentionally never included.
 * - Endpoint auth is configured client-side per endpoint; when set,
 *   Numo sends `Authorization: <configured-auth-key>`.
 * - Fields with nullable values in Android are often omitted from JSON when null.
 *   Treat nullable fields as `optional` in TypeScript.
 * - `checkout` is present only for checkout-originated payments.
 * - `transaction` is included for normal app flows and should be treated as optional
 *   for forward/backward compatibility.
 */

export type NumoWebhookEventName = "payment.received";

export interface NumoTerminalMeta {
    platform: "android";
    appPackage: string;
    appVersionName: string;
    appVersionCode: number;
}

export interface NumoPaymentSummary {
    paymentId: string;
    amountSats: number;
    paymentType: "cashu" | "lightning" | string;
    status: "pending" | "completed" | "cancelled" | string;
    mintUrl?: string;
    tipAmountSats: number;
    tipPercentage: number;
    basketId?: string;
    lightningInvoice?: string;
    lightningQuoteId?: string;
    lightningMintUrl?: string;
}

export interface NumoSwapToLightningMintMetadata {
    unknownMintUrl: string;
    meltQuoteId: string;
    lightningMintUrl: string;
    lightningQuoteId: string;
}

export interface NumoTransactionMetadata {
    paymentId: string;
    status: "pending" | "completed" | "cancelled" | string;
    paymentType: "cashu" | "lightning" | string;
    amountSats: number;
    baseAmountSats: number;
    tipAmountSats: number;
    tipPercentage: number;
    unit: string;
    entryUnit: string;
    enteredAmount: number;
    formattedAmount?: string;
    bitcoinPrice?: number;
    mintUrl?: string;
    lightningInvoice?: string;
    lightningQuoteId?: string;
    lightningMintUrl?: string;
    paymentRequest?: string;
    basketId?: string;
    dateMs: number;
    swapToLightningMint?: NumoSwapToLightningMintMetadata;
}

export interface NumoCheckoutLineItem {
    itemId: string;
    uuid: string;
    name: string;
    variationName?: string;
    sku?: string;
    category?: string;
    quantity: number;
    priceType: "FIAT" | "SATS" | string;
    netPriceCents: number;
    priceSats: number;
    priceCurrency: string;
    vatEnabled: boolean;
    vatRate: number;
    displayName: string;
    netTotalCents: number;
    netTotalSats: number;
    vatPerUnitCents: number;
    totalVatCents: number;
    grossPricePerUnitCents: number;
    grossTotalCents: number;
}

export interface NumoCheckoutMetadata {
    checkoutBasketId: string;
    savedBasketId?: string;
    checkoutTimestamp: number;
    currency: string;
    bitcoinPrice?: number;
    totalSatoshis: number;
    itemCount: number;
    hasVat: boolean;
    hasMixedPriceTypes: boolean;
    fiatNetTotalCents: number;
    fiatVatTotalCents: number;
    fiatGrossTotalCents: number;
    satsDirectTotal: number;
    /**
     * VAT percentage -> total VAT amount in cents.
     *
     * JSON object keys are strings (e.g. { "20": 1234 }).
     */
    vatBreakdown: Record<string, number>;
    items: NumoCheckoutLineItem[];
}

export interface NumoPaymentReceivedWebhookV2 {
    event: NumoWebhookEventName;
    payloadVersion: 2;
    eventId: string;
    timestampMs: number;
    timestampIso: string;
    payment: NumoPaymentSummary;
    transaction?: NumoTransactionMetadata;
    checkout?: NumoCheckoutMetadata;
    terminal: NumoTerminalMeta;
}
