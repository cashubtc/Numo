#!/bin/bash
sed -i 's/org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()/org.mockito.kotlin.any(), org.mockito.kotlin.any()/g' app/src/test/java/com/electricdreams/numo/payment/LightningMintHandlerTest.kt
sed -i 's/org.mockito.ArgumentMatchers.any()/org.mockito.kotlin.any()/g' app/src/test/java/com/electricdreams/numo/payment/LightningMintHandlerTest.kt
