package com.example.shellshock;

import android.util.Log;
import androidx.annotation.NonNull;
import com.cashujdk.api.CashuHttpClient;
import com.cashujdk.nut00.*;
import com.cashujdk.nut01.*;
import com.cashujdk.nut02.*;
import com.cashujdk.nut03.*;
import com.cashujdk.utils.*;
import org.bouncycastle.math.ec.ECPoint;
import org.jetbrains.annotations.NotNull;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.*;
import okhttp3.OkHttpClient;

import static com.cashujdk.cryptography.Cashu.*;
import static com.example.shellshock.SatocashNfcClient.bytesToHex;

public class SatocashWallet {
    private final SatocashNfcClient cardClient;
    private Boolean authenticated;
    @NotNull
    public static String pendingProofToken;

    public static final String TAG = "SatocashWallet";

    public SatocashWallet(SatocashNfcClient _client) {
        cardClient = _client;
        authenticated = false;
    }

    public CompletableFuture<Boolean> authenticatePIN(String pinCode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                cardClient.verifyPin(pinCode, 0);
                authenticated = true;
            } catch (SatocashNfcClient.SatocashException e) {
                throw new RuntimeException(e);
            }
            return true;
        });
    }

    public CompletableFuture<List<Proof>> getPayment(long amount, String unit) {
        return CompletableFuture.supplyAsync(() -> {
            if (!authenticated) {
                throw new RuntimeException("Not authenticated");
            }

            try {
                // Step 1. Get mint
                String mintUrl;
                mintUrl = cardClient.exportMint(0);
                Log.d(TAG, "Got mint URL: " + mintUrl);

                // Step 2. Get mint keysets
                CashuHttpClient cashuHttpClient = new CashuHttpClient(new OkHttpClient(), mintUrl);
                CompletableFuture<GetKeysetsResponse> keysetsFuture = cashuHttpClient.getKeysets();

                // Step 3. Get information about the proofs in the card
                List<Integer> metadataAmountInfo = cardClient.getProofInfo(
                        SatocashNfcClient.Unit.valueOf(unit),
                        SatocashNfcClient.ProofInfoType.METADATA_AMOUNT_EXPONENT,
                        0,
                        128
                );
                Log.d(TAG, "Got metadata amount info, size: " + metadataAmountInfo.size());

                List<Integer> metadataKeysetIndices = cardClient.getProofInfo(
                        SatocashNfcClient.Unit.valueOf(unit),
                        SatocashNfcClient.ProofInfoType.METADATA_KEYSET_INDEX,
                        0,
                        128
                );
                Log.d(TAG, "Got metadata keyset indices, size: " + metadataKeysetIndices.size());

                // Only consider unique indices from unspent proofs
                Set<Integer> uniqueKeysetIndices = new HashSet<>();
                for (int i = 0; i < metadataKeysetIndices.size(); i++) {
                    // Check if the proof is not spent (0x80 bit not set)
                    if ((metadataAmountInfo.get(i) & 0x80) == 0) {
                        uniqueKeysetIndices.add(metadataKeysetIndices.get(i));
                    }
                }
                Log.d(TAG, "Unique keyset indices (from unspent proofs): " + uniqueKeysetIndices);

                // Get the actual keyset IDs from the card
                List<SatocashNfcClient.KeysetInfo> keysetInfos = cardClient.exportKeysets(new ArrayList<>(uniqueKeysetIndices));
                Log.d(TAG, "Got keyset infos, size: " + keysetInfos.size());

                Map<Integer, String> keysetIndicesToIds = new HashMap<>();
                for (SatocashNfcClient.KeysetInfo info : keysetInfos) {
                    keysetIndicesToIds.put(info.index, info.id);
                }
                Log.d(TAG, "Keyset indices to IDs map: " + keysetIndicesToIds);

                // Wait for Mint keysets and then map them to their fee
                GetKeysetsResponse keysetsResponse = keysetsFuture.join();
                Map<String, Integer> keysetsFeesMap = new HashMap<>();
                for (GetKeysetsItemResponse keyset : keysetsResponse.keysets) {
                    keysetsFeesMap.put(keyset.keysetId, keyset.inputFee);
                }
                Log.d(TAG, "Got keysets fees map: " + keysetsFeesMap);

                // Map getProofInfo response to dummy proofs (without the unblinded signature)
                List<Proof> dummyProofs = new ArrayList<>();
                for (int i = 0; i < metadataAmountInfo.size(); ++i) {
                    // Check that it isn't spent
                    if ((metadataAmountInfo.get(i) & 0x80) == 0) {
                        int keysetIndex = metadataKeysetIndices.get(i);
                        String keysetId = keysetIndicesToIds.get(keysetIndex);
                        if (keysetId != null) {
                            Proof p = new Proof();
                            p.amount = 1L << (metadataAmountInfo.get(i) & 0x7F); // Remove the spent bit
                            p.keysetId = keysetId;
                            dummyProofs.add(p);
                            Log.d(TAG, "Added dummy proof: amount=" + p.amount + ", keysetId=" + p.keysetId);
                        }
                    }
                }
                Log.d(TAG, "Created dummy proofs, size: " + dummyProofs.size());

                // Step 3. Coin selection
                ProofSelector selector = new ProofSelector(Optional.of(keysetsFeesMap));
                Pair<List<Proof>, List<Proof>> selection = selector.selectProofsToSend(dummyProofs, (int)amount, true);

                List<Proof> sendSelection = selection.getSecond();
                Log.d(TAG, "Selected proofs for sending, size: " + sendSelection.size());
                
                if (sendSelection.isEmpty()) {
                    throw new RuntimeException("Empty selection: couldn't select coins for this amount");
                }

                // Get amount plus fees
                long amountWithFees = amount + FeeHelper.ComputeFee(selection.getSecond(), keysetsFeesMap);
                long sumProofs = selection.getSecond().stream().map((p) -> p.amount).reduce(0L, Long::sum);

                if (sumProofs < amountWithFees) {
                    throw new RuntimeException("Card limit exceeded");
                }

                long changeAmount = sumProofs - amountWithFees;

                // Match the selected proofs to their respective index in the card
                List<Integer> selectedProofsIndices = new ArrayList<>();
                for (Proof selectedProof : sendSelection) {
                    for (int j = 0; j < metadataAmountInfo.size(); ++j) {
                        if ((metadataAmountInfo.get(j) & 0x80) == 0) { // Not spent
                            int keysetIndex = metadataKeysetIndices.get(j);
                            String keysetId = keysetIndicesToIds.get(keysetIndex);
                            long proofAmount = 1L << (metadataAmountInfo.get(j) & 0x7F);
                            
                            if (proofAmount == selectedProof.amount && 
                                selectedProof.keysetId.equals(keysetId)) {
                                selectedProofsIndices.add(j);
                                break;
                            }
                        }
                    }
                }
                
                if (selectedProofsIndices.size() != sendSelection.size()) {
                    throw new RuntimeException("Failed to match all selected proofs to card indices");
                }
                
                Log.d(TAG, "Selected proof indices: " + selectedProofsIndices);

                // Step 4. Extract proofs from card
                List<Proof> exportedProofs = cardClient.exportProofs(selectedProofsIndices).stream().map((pf) -> {
                    return new Proof(
                            1L << pf.amountExponent,
                            keysetIndicesToIds.get(pf.keysetIndex),
                            new StringSecret(bytesToHex(pf.secret)),
                            hexToPoint(bytesToHex(pf.unblindedKey)),
                            Optional.empty(),
                            Optional.empty()
                    );
                }).collect(Collectors.toList());
                Log.d(TAG, "Exported proofs from card, size: " + exportedProofs.size());

                // Create output amounts
                Pair<List<Long>, List<Long>> outputAmounts = createOutputAmounts(amount, changeAmount);

                // Create swap outputs
                String selectedKeysetId = keysetsResponse.keysets
                        .stream()
                        .filter((k) -> k.active)
                        .min(Comparator.comparing((k) -> k.inputFee))
                        .orElseThrow()
                        .keysetId;
                Log.d(TAG, "Selected keyset ID for new proofs: " + selectedKeysetId);

                // Request the keys in the keyset
                CompletableFuture<GetKeysResponse> keysFuture = cashuHttpClient.getKeys(selectedKeysetId);

                List<Pair<BlindedMessage, Pair<ISecret, BigInteger>>> outputsAndSecretData = Stream.concat(outputAmounts.getFirst().stream(), outputAmounts.getSecond().stream())
                        .map((output) -> {
                            ISecret secret = StringSecret.random();
                            BigInteger blindingFactor = generateRandomScalar();
                            BlindedMessage blindedMessage = new BlindedMessage(
                                    output,
                                    selectedKeysetId,
                                    pointToHex(computeB_(hashToCurve(secret.getBytes()), blindingFactor), true),
                                    Optional.empty()
                            );
                            return new Pair<>(blindedMessage, new Pair<>(secret, blindingFactor));
                        })
                        .collect(Collectors.toList());

                // Create swap payload
                PostSwapRequest swapRequest = new PostSwapRequest();
                swapRequest.inputs = exportedProofs;
                swapRequest.outputs = outputsAndSecretData.stream().map(Pair::getFirst).collect(Collectors.toList());

                PostSwapResponse response = cashuHttpClient.swap(swapRequest).join();
                GetKeysResponse keysResponse = keysFuture.join();
                List<Proof> allProofs = constructAndVerifyProofs(response, keysResponse.keysets.get(0), outputsAndSecretData);

                List<Proof> changeProofs = allProofs.subList(0, outputAmounts.getFirst().size());
                List<Proof> receiveProofs = allProofs.subList(outputAmounts.getFirst().size(), allProofs.size());

                // Import changeProofs to card
                Map<String, Integer> keysetIdsToIndices = transposeMap(keysetIndicesToIds);
                importProofs(changeProofs, keysetIdsToIndices);
                return receiveProofs;
            } catch (SatocashNfcClient.SatocashException | IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public CompletableFuture<Integer> importProofsFromToken(String tokenString) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Token token = Token.decode(tokenString);
                int importedCount = 0;

                Map<String, Integer> keysetIdsToIndices = new HashMap<>(); // To store keysetId to card index mapping

                // First, populate keysetIdsToIndices with existing keysets on the card
                List<SatocashNfcClient.KeysetInfo> existingKeysets = cardClient.exportKeysets(new ArrayList<>());
                for (SatocashNfcClient.KeysetInfo info : existingKeysets) {
                    keysetIdsToIndices.put(info.id, info.index);
                }

                String mintUrl = token.mint;
                for (InnerToken tokenEntry : token.tokens) { // Correctly iterate through token entries
                    // 1. Ensure existence of Mint in the card. If not use cardClient to import it.

                    try {
                        String expMint = cardClient.exportMint(0); // Try to export to check if it exists or for a side effect
                        if (expMint.equals( mintUrl)) {
                            cardClient.importMint(mintUrl);
                        }
                    } catch (SatocashNfcClient.SatocashException e) {
                        Log.d(TAG, "Mint not present on card, attempting to import: " + mintUrl);
                        try {
                            cardClient.importMint(mintUrl); // Corrected: only mintUrl as argument
                        } catch (SatocashNfcClient.SatocashException ex) {
                            throw new RuntimeException("Failed to import mint: " + ex.getMessage(), ex);
                        }
                    }


                    // 2. For every proof:
                    // 2a. check that the keyset exists in the card, if not import it.
                    // 2b. Import the proof
                    for (com.cashujdk.nut00.Proof proof : tokenEntry.proofs) { // Correctly access proofs from TokenEntry
                        // Check the keyset is in the card, import otherwise
                        if (!keysetIdsToIndices.containsKey(proof.keysetId)) {
                            Log.d(TAG, "Keyset not present on card, attempting to import: " + proof.keysetId);
                            int index = cardClient.importKeyset(proof.keysetId, 0, SatocashNfcClient.Unit.SAT /* TODO: change this to the actual unit of the keyset*/);
                            keysetIdsToIndices.put(proof.keysetId, index);
                        }
                        cardClient.importProof(
                                keysetIdsToIndices.get(proof.keysetId),
                                ilog2(proof.amount),
                                pointToHex(proof.c, true),
                                proof.secret.toString()
                        );
                        importedCount++;
                    }
                }
                return importedCount;
            }  catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void importProofs(List<Proof> proofs, Map<String, Integer> keysetIdsToIndices) throws SatocashNfcClient.SatocashException {
        for (Proof proof : proofs) {
            // Check the keyset is in the card, import otherwise
            if (!keysetIdsToIndices.containsKey(proof.keysetId)) {
                int index = cardClient.importKeyset(proof.keysetId, 0, SatocashNfcClient.Unit.SAT /* TODO: change this to the actual unit of the keyset*/);
                keysetIdsToIndices.put(proof.keysetId, index);
            }
            cardClient.importProof(
                    keysetIdsToIndices.get(proof.keysetId),
                    ilog2(proof.amount),
                    pointToHex(proof.c, true),
                    proof.secret.toString()
            );
        }
    }

    public static int ilog2(long number) {
        if (number < 0) {
            throw new IllegalArgumentException();
        }
        return 63 - Long.numberOfLeadingZeros(number);
    }

    private static <K, V> Map<V, K> transposeMap(Map<K, V> originalMap) {
        Map<V, K> transposedMap = new HashMap<>();
        for (Map.Entry<K, V> entry : originalMap.entrySet()) {
            transposedMap.put(entry.getValue(), entry.getKey());
        }
        return transposedMap;
    }

    private List<Proof> constructAndVerifyProofs(PostSwapResponse response, KeysetItemResponse keyset, List<Pair<BlindedMessage, Pair<ISecret, BigInteger>>> outputsAndSecretData) {
        List<BigInteger> blindingFactors = outputsAndSecretData.stream().map((output) -> output.getSecond().getSecond()).collect(Collectors.toList());
        List<ISecret> secrets = outputsAndSecretData.stream().map((output) -> output.getSecond().getFirst()).collect(Collectors.toList());

        List<Proof> result = new ArrayList<>();
        for (int i = 0; i < response.signatures.size(); ++i) {
            BlindSignature signature = response.signatures.get(i);
            BigInteger blindingFactor = blindingFactors.get(i);
            ISecret secret = secrets.get(i);

            ECPoint key = hexToPoint(keyset.keys.get(BigInteger.valueOf(signature.amount)));
            ECPoint C = computeC(signature.getC_(), blindingFactor, key);

            result.add(new Proof(signature.amount, signature.keysetId, secret, C, Optional.empty(), Optional.empty()));
        }
        return result;
    }

    private static Pair<List<Long>, List<Long>> createOutputAmounts(long amount, long changeAmount) {
        // TEMPORARY (until cashu-jdk catches up) Create output amounts
        List<Long> receiveOutputAmounts = new ArrayList<>();
        List<Long> changeOutputAmounts = new ArrayList<>();
        long amountLeft = amount;
        for (int i = 0; amountLeft > 0; ++i) {
            if ((amountLeft&1) == 1) {
                receiveOutputAmounts.add(1L << i);
            }
            amountLeft >>= 1;
        }
        amountLeft = changeAmount;
        for (int i = 0; amountLeft > 0; ++i) {
            if ((amountLeft&1) == 1) {
                changeOutputAmounts.add(1L << i);
            }
            amountLeft >>= 1;
        }

        return new Pair<>(changeOutputAmounts, receiveOutputAmounts);
    }
}
