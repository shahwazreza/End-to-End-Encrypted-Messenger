package crypto;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

public class KeyDerivation {

    // RFC 5869 HKDF context label — changing this invalidates existing sessions
    private static final byte[] HKDF_INFO = "E2EE-AES256-GCM-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_SALT = new byte[32]; // all-zeros salt per RFC 5869 §2.2

    public static SecretKey deriveSharedKey(PrivateKey myPrivate, PublicKey theirPublic) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(myPrivate);
        ka.doPhase(theirPublic, true);
        byte[] ikm = ka.generateSecret();

        try {
            // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HKDF_SALT, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);

            // HKDF-Expand: OKM = HMAC-SHA256(PRK, info || 0x01) — yields 32 bytes for AES-256
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] t1Input = Arrays.copyOf(HKDF_INFO, HKDF_INFO.length + 1);
            t1Input[HKDF_INFO.length] = 0x01;
            byte[] okm = mac.doFinal(t1Input);

            return new SecretKeySpec(okm, "AES");
        } finally {
            Arrays.fill(ikm, (byte) 0);
        }
    }
}