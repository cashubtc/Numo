# Fiat Currency Conversion APIs

## Overview

Numo fetches Bitcoin-to-Fiat conversion rates from multiple external APIs to display equivalent fiat amounts for sats payments. This document describes the sources and fallback mechanisms.

## API Sources

### 1. Coinbase (Primary)

**Base URL:** `https://api.coinbase.com/v2/prices/BTC-{CURRENCY}/spot`

Coinbase is the primary source for standard currencies (USD, EUR, GBP, DKK, SEK, NOK, etc.). The app constructs the full URL by appending the currency code.

**Response format:**
```json
{
  "data": {
    "base": "BTC",
    "currency": "USD",
    "amount": "96542.50"
  }
}
```

**Parser:** Extracts `data.amount` as a Double.

**Supported currencies:**
- USD, EUR, GBP, DKK, SEK, NOK, and other standard ISO 4217 currencies.

---

### 2. Yadio.io (Fallback)

**Base URL:** `https://api.yadio.io/rate/BTC/{CURRENCY}`

Yadio.io is used in two scenarios:
1. As a **fallback** when Coinbase fails (network error, rate limiting, etc.)
2. As the **primary source** for Latin American currencies

**Response format:**
```json
{
  "rate": 0.00001036
}
```

Yadio returns the BTC-to-fiat rate (fiat per BTC), so the parser inverts it: `1.0 / rate`.

**Parser:** `1.0 / JSONObject(response).getDouble("rate")`

**Latin American currencies (primary Yadio source):**
- CUP (Cuban Peso)
- MLC (Cuban Convertible Peso)
- ARS (Argentine Peso)
- BOB (Bolivian Boliviano)
- BRL (Brazilian Real)
- CLP (Chilean Peso)
- COP (Colombian Peso)
- CRC (Costa Rican Colon)
- DOP (Dominican Peso)
- GTQ (Guatemalan Quetzal)
- HNL (Honduran Lempira)
- MXN (Mexican Peso)
- NIO (Nicaraguan Cordoba)
- PAB (Panamanian Balboa)
- PEN (Peruvian Sol)
- PYG (Paraguayan Guarani)
- UYU (Uruguayan Peso)
- VES (Venezuelan Bolivar)

---

### 3. CoinGecko (JPY only)

**URL:** `https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=jpy`

CoinGecko is used specifically for Japanese Yen due to Coinbase limitations with JPY.

**Response format:**
```json
{
  "bitcoin": {
    "jpy": 14382500
  }
}
```

**Parser:** Extracts `bitcoin.jpy` as a Double.

---

### 4. Upbit (KRW only)

**URL:** `https://api.upbit.com/v1/ticker?markets=KRW-BTC`

Upbit is used for Korean Won as it provides reliable KRW-BTC pairs.

**Response format:**
```json
[
  {
    "market": "KRW-BTC",
    "trade_price": 135250000
  }
]
```

**Parser:** Extracts `trade_price` from the first array element.

---

## Fallback Mechanism

### Primary Fallback Flow

When fetching a price, the app:

1. **Tries Coinbase first** (for non-Latam currencies)
2. **Falls back to Yadio.io** if Coinbase fails due to:
   - Network errors
   - HTTP non-200 responses
   - JSON parsing errors
   - Connection timeouts (5 seconds)

### Why Yadio.io as Fallback?

Coinbase API may be **blocked or rate-limited** in certain regions, particularly in countries under US sanctions like Cuba. Yadio.io provides a reliable alternative that works globally.

### Implementation

See `CurrencyManager.kt` and `BitcoinPriceWorker.kt` for the fallback implementation:

- `CurrencyManager.getPriceApiUrl()` - Returns the appropriate primary URL
- `CurrencyManager.getFallbackApiUrl()` - Returns the Yadio.io URL
- `BitcoinPriceWorker.fetchPrice()` - Orchestrates the try-primary-then-fallback flow

---

## Supported Currencies Summary

| Currency | Code | API Source |
|----------|------|------------|
| US Dollar | USD | Coinbase |
| Euro | EUR | Coinbase |
| British Pound | GBP | Coinbase |
| Japanese Yen | JPY | CoinGecko |
| Danish Krone | DKK | Coinbase |
| Swedish Krona | SEK | Coinbase |
| Norwegian Krone | NOK | Coinbase |
| Korean Won | KRW | Upbit |
| Cuban Peso | CUP | Yadio.io |
| Cuban Convertible Peso | MLC | Yadio.io |
| Argentine Peso | ARS | Yadio.io |
| Brazilian Real | BRL | Yadio.io |
| Chilean Peso | CLP | Yadio.io |
| Colombian Peso | COP | Yadio.io |
| And other Latam currencies... | | Yadio.io |

---

## Caching

Prices are cached in SharedPreferences with a 1-minute TTL. The app updates prices:
- On app start (if cached price is stale)
- Every minute while the app is active
- Immediately when the user changes currency

---

## File References

- `app/src/main/java/com/electricdreams/numo/core/util/CurrencyManager.kt` - API URL construction and parsing
- `app/src/main/java/com/electricdreams/numo/core/worker/BitcoinPriceWorker.kt` - Fetching, caching, and fallback logic
