#!/bin/bash
sed -i 's/getInstance(org.mockito.kotlin.any())/getInstance(org.mockito.ArgumentMatchers.any())/g' app/src/test/java/com/electricdreams/numo/payment/LightningMintHandlerTest.kt
