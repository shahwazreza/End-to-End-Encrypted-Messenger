import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class AccountStore {

    private static final Path FILE = Paths.get(
            System.getProperty("user.home"), ".messenger-server", "accounts.dat");
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS   = 256;

    /** Returns true if registered, false if username already taken. */
    public static synchronized boolean register(String username, String password) throws Exception {
        if (userExists(username)) return false;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password, salt);
        Files.createDirectories(FILE.getParent());
        Files.writeString(FILE, username + ":" + b64(salt) + ":" + b64(hash) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return true;
    }

    /** Returns true if credentials are valid. */
    public static synchronized boolean authenticate(String username, String password) throws Exception {
        if (!Files.exists(FILE)) return false;
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split(":", 3);
            if (p.length != 3 || !p[0].equals(username)) continue;
            byte[] salt     = Base64.getDecoder().decode(p[1]);
            byte[] expected = Base64.getDecoder().decode(p[2]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt));
        }
        return false;
    }

    private static boolean userExists(String username) throws IOException {
        if (!Files.exists(FILE)) return false;
        for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
            String[] p = line.split(":", 2);
            if (p.length >= 1 && p[0].equals(username)) return true;
        }
        return false;
    }

    private static byte[] pbkdf2(String password, byte[] salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS))
                .getEncoded();
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
