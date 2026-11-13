import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

/** Signs domain-separated metadata using the same CI keystore as the APK. */
class SignUpdateManifest {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: SignUpdateManifest payload output apkCertSha256");
        }
        var storePath = Path.of(required("KEYSTORE_PATH"));
        var store = KeyStore.getInstance(storePath.toFile(), required("KEYSTORE_PASSWORD").toCharArray());
        var alias = required("KEY_ALIAS");
        var cert = store.getCertificate(alias);
        if (cert == null) throw new IllegalArgumentException("Signing certificate not found");
        var fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        if (!fingerprint.equalsIgnoreCase(args[2])) {
            throw new IllegalArgumentException("Manifest key must match the APK signing certificate");
        }
        var key = (PrivateKey) store.getKey(alias, required("KEY_PASSWORD").toCharArray());
        var algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> throw new IllegalArgumentException("Unsupported signing algorithm");
        };
        var payload = Files.readAllBytes(Path.of(args[0]));
        var signature = Signature.getInstance(algorithm);
        signature.initSign(key);
        signature.update("Numo update manifest v1\n".getBytes(StandardCharsets.UTF_8));
        signature.update(payload);
        var encoder = Base64.getEncoder();
        var envelope = "{\"payload\":\"" + encoder.encodeToString(payload)
            + "\",\"signature\":\"" + encoder.encodeToString(signature.sign()) + "\"}\n";
        Files.writeString(Path.of(args[1]), envelope, StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
