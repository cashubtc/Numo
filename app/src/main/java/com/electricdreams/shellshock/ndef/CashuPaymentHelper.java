package com.electricdreams.shellshock.ndef;

import android.util.Log;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.bouncycastle.math.ec.ECPoint;

import com.cashujdk.api.CashuHttpClient;
import com.cashujdk.nut00.BlindSignature;
import com.cashujdk.nut00.InnerToken;
import com.cashujdk.nut00.Proof;
import com.cashujdk.nut00.StringSecret;
import com.cashujdk.nut00.Token;
import com.cashujdk.nut01.GetKeysResponse;
import com.cashujdk.nut01.KeysetItemResponse;
import com.cashujdk.nut02.FeeHelper;
import com.cashujdk.nut02.GetKeysetsResponse;
import com.cashujdk.nut03.PostSwapRequest;
import com.cashujdk.nut03.PostSwapResponse;
import com.cashujdk.nut18.PaymentRequest;
import com.cashujdk.nut18.Transport;
import com.cashujdk.nut18.TransportTag;
import com.cashujdk.utils.OutputData;
import com.cashujdk.utils.OutputHelper;
import com.cashujdk.cryptography.Cashu;

import okhttp3.OkHttpClient;

/**
 * Helper class for Cashu payment-related operations
 */
public class CashuPaymentHelper {
    private static final String TAG = "CashuPaymentHelper";

    /**
     * Create a Cashu payment request for a specific amount
     * @param amount Amount in sats
     * @param description Optional description for the payment
     * @return Payment request string (creq...)
     */
    public static String createPaymentRequest(long amount, String description) {
        try {
            PaymentRequest paymentRequest = new PaymentRequest();
            paymentRequest.amount = Optional.of(amount);
            paymentRequest.unit = Optional.of("sat");
            paymentRequest.description = Optional.of(description != null ? description : "Payment for " + amount + " sats");
            
            // Generate a random ID
            String id = java.util.UUID.randomUUID().toString().substring(0, 8);
            paymentRequest.id = Optional.of(id);
            
            // Set single-use flag
            paymentRequest.singleUse = Optional.of(true);
            
            // Encode and return
            String encodedRequest = paymentRequest.encode();
            Log.d(TAG, "Created payment request: " + encodedRequest);
            return encodedRequest;
        } catch (Exception e) {
            Log.e(TAG, "Error creating payment request: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Check if a string is a valid Cashu token
     * @param text Text to check
     * @return True if it's a valid Cashu token (starts with "cashuB"), false otherwise
     * Note: This only does basic format validation, not cryptographic verification
     */
    public static boolean isCashuToken(String text) {
        return text != null && (text.startsWith("cashuB") || text.startsWith("cashuA"));
    }

    /**
     * Check if a string is a valid Cashu payment request
     * @param text Text to check
     * @return True if it's a valid Cashu payment request (starts with "creq"), false otherwise
     */
    public static boolean isCashuPaymentRequest(String text) {
        return text != null && text.startsWith("creqA");
    }
    
    /**
     * Validate a Cashu token for a specific amount
     * @param token The token to validate
     * @param expectedAmount The expected amount in sats
     * @return True if the token is valid and matches the expected amount, false otherwise
     */
    public static boolean validateToken(String tokenString, long expectedAmount) {
        if (!isCashuToken(tokenString)) {
            Log.e(TAG, "Invalid token format (not a Cashu token)");
            return false;
        }
        
        try {
            Token token = Token.decode(tokenString);
            if (!token.unit.equals("sat")) {
                Log.e(TAG, "Unsupported token unit: " + token.unit);
                return false;
            }

            long tokenAmount = token.tokens.stream()
                .mapToLong(t -> t.getProofsShortId().stream().mapToLong(p -> p.amount).sum())
                .sum();

            if (tokenAmount < expectedAmount) {
                Log.e(TAG, "Amount was insufficient: " + expectedAmount + " sats required but " + tokenAmount + " sats provided");
                return false;
            }
            
            Log.d(TAG, "Token format validation passed, cryptographic verification pending");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Token validation failed: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Attempt to redeem a Cashu token and get a reissued token
     * @param token The token to redeem
     * @return A new Token with redeemed proofs
     * @throws RedemptionException if token redemption fails
     */
    public static String redeemToken(String tokenString) throws RedemptionException {
        if (!isCashuToken(tokenString)) {
            String errorMsg = "Cannot redeem: Invalid token format";
            Log.e(TAG, errorMsg);
            throw new RedemptionException(errorMsg);
        }
        
        try {
            Token token = Token.decode(tokenString);

            String mintUrl = token.mint;

            CashuHttpClient cashuHttpClient = new CashuHttpClient(new OkHttpClient(), mintUrl);
            GetKeysetsResponse keysetsResponse = cashuHttpClient.getKeysets().join();
            if (keysetsResponse == null || keysetsResponse.keysets == null || keysetsResponse.keysets.isEmpty()) {
                throw new RedemptionException("Failed to get keysets from mint: " + mintUrl);
            }
            
            final List<String> fullKeysetIds = keysetsResponse.keysets.stream().map((k) -> k.keysetId).collect(Collectors.toList());
            final Map<String, Integer> keysetsFeesMap = keysetsResponse.keysets.stream()
                .collect(Collectors.toMap(k -> k.keysetId, k -> k.inputFee));

            long tokenAmount = token.tokens.stream()
                .mapToLong(t -> t.getProofsShortId().stream().mapToLong(p -> p.amount).sum())
                .sum();

            List<Proof> receiveProofs = token.tokens.stream()
                .flatMap(t -> t.getProofs(fullKeysetIds).stream())
                .collect(Collectors.toList());
                
            if (receiveProofs.isEmpty()) {
                throw new RedemptionException("No valid proofs found in token");
            }

            long fee = FeeHelper.ComputeFee(receiveProofs, keysetsFeesMap);

            // Create swap outputs
            String selectedKeysetId = keysetsResponse.keysets
                    .stream()
                    .filter((k) -> k.active)
                    .min(Comparator.comparing((k) -> k.inputFee))
                    .map(k -> k.keysetId)
                    .orElseThrow(() -> new RedemptionException("No active keyset found on mint"));
            Log.d(TAG, "Selected keyset ID for new proofs: " + selectedKeysetId);

            
            // Create KeysetId object
            com.cashujdk.nut01.KeysetId keysetId = new com.cashujdk.nut01.KeysetId();
            keysetId.set_id(selectedKeysetId);
            
            // Create an instance of OutputHelper
            OutputHelper outputHelper = new OutputHelper();
            List<OutputData> outputData = outputHelper.createOutputs(createOutputAmounts(tokenAmount - fee), keysetId);

            // Request the keys in the keyset
            CompletableFuture<GetKeysResponse> keysFuture = cashuHttpClient.getKeys(selectedKeysetId);

            // Create swap payload
            PostSwapRequest swapRequest = new PostSwapRequest();
            swapRequest.inputs = receiveProofs;
            swapRequest.outputs = outputData.stream().map(bm -> bm.blindedMessage).collect(Collectors.toList());

            Log.d(TAG, "Attempting to swap proofs");

            PostSwapResponse response = cashuHttpClient.swap(swapRequest).join();
            if (response == null || response.signatures == null || response.signatures.isEmpty()) {
                throw new RedemptionException("No signatures returned from mint during swap");
            }
            
            GetKeysResponse keysResponse = keysFuture.join();
            if (keysResponse == null || keysResponse.keysets == null || keysResponse.keysets.isEmpty()) {
                throw new RedemptionException("Failed to get keys from mint");
            }

            Log.d(TAG, "Successfully swapped and received proofs");

            List<Proof> proofs = constructAndVerifyProofs(response, keysResponse.keysets.get(0), outputData);
            if (proofs.isEmpty()) {
                throw new RedemptionException("Failed to verify proofs from mint");
            }

            Log.d(TAG, "Successfully constructed and verified proofs");

            Token newToken = new Token(proofs, token.unit, mintUrl);
            
            Log.d(TAG, "Token redemption successful!");
            return newToken.encode();
        } catch (RedemptionException e) {
            // Re-throw our custom exceptions
            throw e;
        } catch (Exception e) {
            String errorMsg = "Token redemption failed: " + e.getMessage();
            Log.e(TAG, errorMsg, e);
            throw new RedemptionException(errorMsg, e);
        }
    }
    
    /**
     * Exception class for token redemption failures
     */
    public static class RedemptionException extends Exception {
        public RedemptionException(String message) {
            super(message);
        }
        
        public RedemptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static long[] createOutputAmounts(long amount) {
        List<Long> receiveOutputAmounts = new ArrayList<>();
        long amountLeft = amount;
        for (int i = 0; amountLeft > 0; ++i) {
            if ((amountLeft&1) == 1) {
                receiveOutputAmounts.add(1L << i);
            }
            amountLeft >>= 1;
        }
        // Convert list to array
        long[] amountArray = new long[receiveOutputAmounts.size()];
        for (int i = 0; i < receiveOutputAmounts.size(); i++) {
            amountArray[i] = receiveOutputAmounts.get(i);
        }
        return amountArray;
    }

    private static List<Proof> constructAndVerifyProofs(PostSwapResponse response, KeysetItemResponse keyset, List<OutputData> outputsAndSecretData) {
        List<BigInteger> blindingFactors = outputsAndSecretData.stream().map((output) -> new BigInteger(1, output.blindingFactor)).toList();
        @SuppressWarnings("unchecked")
        List<StringSecret> secrets = outputsAndSecretData.stream()
            .map((output) -> (StringSecret) output.secret)
            .collect(Collectors.toList());

        List<Proof> result = new ArrayList<>();
        for (int i = 0; i < response.signatures.size(); ++i) {
            BlindSignature signature = response.signatures.get(i);
            BigInteger blindingFactor = blindingFactors.get(i);
            StringSecret secret = secrets.get(i);

            ECPoint key = Cashu.hexToPoint(keyset.keys.get(BigInteger.valueOf(signature.amount)));
            ECPoint C = Cashu.computeC(Cashu.hexToPoint(signature.c_), blindingFactor, key);

            if (!Cashu.verifyProof(Cashu.messageToCurve(secret.getSecret()), blindingFactor, C, signature.dleq.e, signature.dleq.s, key)) {
                Log.e(TAG, String.format("Couldn't verify signature: %s", signature.c_));
            }
            result.add(new Proof(signature.amount, signature.keysetId, secret, Cashu.pointToHex(C, true), Optional.empty(), Optional.empty()));
        }
        return result;
    }
}
