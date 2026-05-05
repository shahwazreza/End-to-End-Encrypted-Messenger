package crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class Encryption {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    public static class EncryptedData {
        public byte[] iv;
        public byte[] ciphertext;
    }

    public static EncryptedData encrypt(String message, SecretKey key) throws Exception {
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        EncryptedData data = new EncryptedData();
        data.iv = iv;
        data.ciphertext = cipher.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return data;
    }

    public static String decrypt(EncryptedData data, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, data.iv));
        return new String(cipher.doFinal(data.ciphertext), StandardCharsets.UTF_8);
    }
}