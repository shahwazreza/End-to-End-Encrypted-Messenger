package crypto;

import java.nio.file.*;
import java.security.*;
import java.security.spec.*;

public class KeyManager {

    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
        kpg.initialize(new NamedParameterSpec("X25519"));
        return kpg.generateKeyPair();
    }

    /**
     * Loads the persistent X25519 identity for {@code username} from
     * ~/.messenger/<username>/, generating and saving a new pair if none exists.
     */
    public static KeyPair loadOrCreate(String username) throws Exception {
        Path dir = Paths.get(System.getProperty("user.home"), ".messenger", username);
        Files.createDirectories(dir);
        Path privPath = dir.resolve("identity.priv");
        Path pubPath  = dir.resolve("identity.pub");

        if (Files.exists(privPath) && Files.exists(pubPath)) {
            return load(privPath, pubPath);
        }

        KeyPair kp = generateKeyPair();
        Files.write(privPath, kp.getPrivate().getEncoded());
        Files.write(pubPath,  kp.getPublic().getEncoded());
        return kp;
    }

    public static boolean hasStoredIdentity(String username) {
        Path dir = Paths.get(System.getProperty("user.home"), ".messenger", username);
        return Files.exists(dir.resolve("identity.priv")) && Files.exists(dir.resolve("identity.pub"));
    }

    private static KeyPair load(Path privPath, Path pubPath) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("X25519");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privPath)));
        PublicKey  pub  = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubPath)));
        return new KeyPair(pub, priv);
    }
}