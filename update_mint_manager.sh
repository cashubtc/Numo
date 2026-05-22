#!/bin/bash
sed -i 's/private const val KEY_ENABLE_SWAP_UNKNOWN_MINTS = "enableSwapUnknownMints"/private const val KEY_ENABLE_SWAP_UNKNOWN_MINTS = "enableSwapUnknownMints"\n        private const val KEY_PREFERRED_UNIT = "preferredBaseUnit"/g' app/src/main/java/com/electricdreams/numo/core/util/MintManager.kt
