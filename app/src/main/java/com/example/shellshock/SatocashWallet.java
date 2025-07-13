package com.example.shellshock;

import static com.cashujdk.cryptography.Cashu.computeB_;
import static com.cashujdk.cryptography.Cashu.computeC;
import static com.cashujdk.cryptography.Cashu.generateRandomScalar;
import static com.cashujdk.cryptography.Cashu.hashToCurve;
import static com.cashujdk.cryptography.Cashu.hexToPoint;
import static com.cashujdk.cryptography.Cashu.pointToHex;
import static com.cashujdk.cryptography.Cashu.verifyProof;
import static com.example.shellshock.SatocashNfcClient.bytesToHex;

import androidx.annotation.NonNull;

import com.cashujdk.api.CashuHttpClient;
import com.cashujdk.nut00.BlindSignature;
import com.cashujdk.nut00.BlindedMessage;
import com.cashujdk.nut00.ISecret;
import com.cashujdk.nut00.Proof;
import com.cashujdk.nut00.StringSecret;
import com.cashujdk.nut01.GetKeysResponse;
import com.cashujdk.nut01.KeysetItemResponse;
import com.cashujdk.nut02.FeeHelper;
import com.cashujdk.nut02.GetKeysetsItemResponse;
import com.cashujdk.nut02.GetKeysetsResponse;
import com.cashujdk.nut03.PostSwapRequest;
import com.cashujdk.nut03.PostSwapResponse;
import com.cashujdk.utils.Pair;
import com.cashujdk.utils.ProofSelector;

import org.bouncycastle.math.ec.ECPoint;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.net.http.HttpClient;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SatocashWallet {
    private final SatocashNfcClient cardClient;
    private Boolean authenticated;

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

                // Step 2. Get mint keysets
                CashuHttpClient cashuHttpClient = new CashuHttpClient(new HttpClient(), mintUrl);
                CompletableFuture<GetKeysetsResponse> keysetsFuture = cashuHttpClient.getKeysets();

                // Step 3. Get information about the proofs in the card
                Map<Integer, String> metadataIndexToKeyset;
                List<Integer> metadataAmountInfo = cardClient.getProofInfo(
                        SatocashNfcClient.Unit.valueOf(unit),
                        SatocashNfcClient.ProofInfoType.METADATA_AMOUNT_EXPONENT,
                        0,
                        128
                );
                List<Integer> metadataKeysetIndices = cardClient.getProofInfo(
                        SatocashNfcClient.Unit.valueOf(unit),
                        SatocashNfcClient.ProofInfoType.METADATA_KEYSET_INDEX,
                        0,
                        128
                );
                // Only consider unique indices
                Set<Integer> uniqueKeysetIndices = new HashSet<>(metadataKeysetIndices);
                // Get the actual keyset IDs from the card
                List<SatocashNfcClient.KeysetInfo> keysetInfos = cardClient.exportKeysets(new ArrayList<Integer>(uniqueKeysetIndices));
                Map<Integer, String> keysetIndicesToIds = new HashMap<>();
                for (int i = 0; i < keysetInfos.size(); ++i) {
                    keysetIndicesToIds.put(i, keysetInfos.get(i).id);
                }

                // Wait for Mint keysets and then map them to their fee
                GetKeysetsResponse keysetsResponse = keysetsFuture.join();
                Map<String, Integer> keysetsFeesMap = new HashMap<>();
                for (GetKeysetsItemResponse keyset : keysetsResponse.keysets) {
                    keysetsFeesMap.put(keyset.keysetId, keyset.inputFee);
                }

                // Map getProofInfo response to dummy proofs (without the unblinded signature)
                List<Proof> dummyProofs = new ArrayList<>();
                for (int i = 0; i < metadataAmountInfo.size(); ++i) {
                    // Check that it isn't spent
                    if ((metadataAmountInfo.get(i) & 0x80) == 0x00) {
                        Proof p = new Proof();
                        p.amount = 1L << metadataAmountInfo.get(i);
                        p.keysetId = keysetIndicesToIds.get(metadataKeysetIndices.get(i));
                        dummyProofs.add(p);
                    }
                }

                // Step 3. Coin selection
                ProofSelector selector = new ProofSelector(Optional.of(keysetsFeesMap));
                Pair<List<Proof>, List<Proof>> selection = selector.selectProofsToSend(dummyProofs, (int)amount, true);
                List<Integer> selectedProofsIndices = getProofsIndices(selection, dummyProofs);

                // Get amount plus fees
                long amountWithFees = amount + FeeHelper.ComputeFee(selection.getSecond(), keysetsFeesMap);
                long sumProofs = selection.getSecond().stream().map((p) -> p.amount).reduce(0L, Long::sum);

                if (sumProofs < amountWithFees) {
                    throw new RuntimeException("Card limit exceeded");
                }

                long changeAmount = sumProofs - amountWithFees;

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

                // Create output amounts
                Pair<List<Long>, List<Long>> outputAmounts = createOutputAmounts(amount, changeAmount);

                // Create swap outputs
                String selectedKeysetId = keysetsResponse.keysets
                        .stream()
                        .filter((k) -> k.active)
                        .min(Comparator.comparing((k) -> k.inputFee))
                        .orElseThrow()
                        .keysetId;

                // Request the keys in the keyset
                CompletableFuture<GetKeysResponse> keysFuture = cashuHttpClient.getKeys(selectedKeysetId);

                List<Pair<BlindedMessage, Pair<ISecret, BigInteger>>> outputsAndSecretData = Stream.concat(outputAmounts.getFirst().stream(), outputAmounts.getSecond().stream())
                        .map((output) -> {
                            ISecret secret = StringSecret.random();
                            BigInteger blindingFactor = generateRandomScalar();
                            BlindedMessage blindedMessage = new BlindedMessage(
                                    new BigInteger(String.valueOf(output)),
                                    selectedKeysetId,
                                    computeB_(hashToCurve(secret.getBytes()), blindingFactor),
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
                List<Proof> receiveProofs = allProofs.subList(outputAmounts.getFirst().size(), outputAmounts.getSecond().size());

                // Import changeProofs to card
                Map<String, Integer> keysetIdsToIndices = transposeMap(keysetIndicesToIds);
                for (Proof proof : changeProofs) {
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
                return receiveProofs;
            } catch (SatocashNfcClient.SatocashException | IOException e) {
                throw new RuntimeException(e);
            }
        });
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

            ECPoint key = hexToPoint(keyset.keys.get(signature.amount));
            ECPoint C = computeC(signature.getC_(), blindingFactor, key);

            result.add(new Proof(signature.amount.longValue(), signature.keysetId, secret, C, Optional.empty(), Optional.empty()));
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

    @NonNull
    private static List<Integer> getProofsIndices(Pair<List<Proof>, List<Proof>> selection, List<Proof> dummyProofs) {
        List<Proof> sendSelection = selection.getSecond();
        if (sendSelection.isEmpty()) {
            throw new RuntimeException("Empty selection: couldn't select coins for this amount");
        }
        sendSelection.sort(Comparator.comparingLong((p) -> p.amount));

        // Match the selected proofs to their respective index in the card
        List<Integer> selectedProofsIndices = new ArrayList<>(selection.getFirst().size());
        for (int i = 0; i < sendSelection.size(); ++i) {
            for (int j = 0; j < dummyProofs.size(); ++j) {
                if (sendSelection.get(i).amount == dummyProofs.get(j).amount &&
                    sendSelection.get(i).keysetId.equals(dummyProofs.get(j).keysetId)
                ) {
                    selectedProofsIndices.set(i, j);
                    break;
                }
            }
        }
        return selectedProofsIndices;
    }
}
