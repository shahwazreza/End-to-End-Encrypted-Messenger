# End-to-End Encrypted Messenger (Java)

A minimal end-to-end encrypted messaging system built in Java.  
Clients exchange public keys, derive a shared secret, and send encrypted messages through a relay server that cannot read message contents.

This project demonstrates real E2EE fundamentals:
- X25519 Diffie-Hellman key agreement
- HKDF key derivation (RFC 5869)
- AES-256-GCM authenticated encryption
- Secure client-to-client messaging over a network


## Features

- End-to-end encryption — server never sees plaintext
- AES-256-GCM authenticated encryption
- X25519 key agreement with HKDF-derived session keys
- Public key registration and retrieval
- Multi-client communication
- Configurable host and port


## File Structure

```
e2ee-messenger/
│
├── ChatApp.java          ← JavaFX desktop UI
├── MessengerClient.java  ← shared networking/crypto core
├── Client.java           ← CLI client (uses MessengerClient)
├── Server.java           ← relay server
├── Main.java             ← crypto demo / smoke test
├── pom.xml
│
├── crypto/
│   ├── KeyManager.java
│   ├── KeyDerivation.java
│   └── Encryption.java
│
└── README.md
```


## How It Works

### 1. Key Generation
Each client generates an X25519 key pair on startup. The public key is registered with the server; the private key never leaves the client.

### 2. Key Exchange
The client polls the server for the peer's public key, then derives a shared secret using X25519 Diffie-Hellman. The raw shared secret is passed through HKDF (HMAC-SHA256, RFC 5869) to produce a 32-byte AES-256 key.

### 3. Encryption
Every message is encrypted with AES-256-GCM using a unique 12-byte random IV:

```
AES/GCM/NoPadding  |  256-bit key  |  128-bit auth tag  |  12-byte IV
```

The IV is prepended to the ciphertext and Base64-encoded before sending.

### 4. Message Flow

```
Sender → encrypt(AES-256-GCM) → server relay → receiver → decrypt
```

The server only forwards ciphertext and never has access to keys or plaintext.


## How to Run

### Requirements
- Java 17+
- [Maven 3.8+](https://maven.apache.org/download.cgi) (required for the JavaFX GUI)

> Maven is only needed for the GUI. The CLI works with plain `javac`.

---

### Desktop GUI (JavaFX)

Maven downloads JavaFX automatically on first run.

```powershell
# Start the server
java Server

# Launch the GUI (in a separate terminal)
mvn javafx:run
```

Enter your username, peer's username, and click **Connect**. Both sides need to be connected for the key exchange to complete (30-second timeout).

---

### CLI (no Maven needed)

```powershell
# Compile
javac Client.java Server.java MessengerClient.java Main.java crypto/*.java

# Start the server
java Server

# Terminal 1
java Client Alice Bob

# Terminal 2
java Client Bob Alice
```

To use a custom host or port:

```powershell
java -Dserver.host=192.168.1.10 -Dserver.port=6000 Client Alice Bob
```

### TLS transport

TLS is optional and uses the standard JVM keystore and truststore settings.

Create a local demo certificate:

```powershell
keytool -genkeypair -alias messenger-server -keyalg RSA -keysize 2048 `
  -keystore server-keystore.p12 -storetype PKCS12 -storepass changeit `
  -validity 365 -dname "CN=localhost"

keytool -exportcert -alias messenger-server -keystore server-keystore.p12 `
  -storepass changeit -rfc -file server-cert.pem

keytool -importcert -alias messenger-server -file server-cert.pem `
  -keystore client-truststore.p12 -storetype PKCS12 -storepass changeit -noprompt
```

Run the TLS server:

```powershell
java -Dserver.tls=true `
  -Djavax.net.ssl.keyStore=server-keystore.p12 `
  -Djavax.net.ssl.keyStorePassword=changeit `
  Server
```

Run TLS clients:

```powershell
java -Dclient.tls=true `
  -Djavax.net.ssl.trustStore=client-truststore.p12 `
  -Djavax.net.ssl.trustStorePassword=changeit `
  Client Alice Bob
```


## Security Properties

| Property | Implementation |
|---|---|
| Confidentiality | AES-256-GCM |
| Integrity & authenticity | GCM 128-bit authentication tag |
| Key agreement | X25519 Diffie-Hellman |
| Key derivation | HKDF-Extract + HKDF-Expand (RFC 5869, HMAC-SHA256) |
| IV generation | 12-byte cryptographically random per message |
| Server access | Zero — relays ciphertext only |


## Planned Improvements

- Web frontend (React + WebCrypto API)
- TLS transport layer (SSLServerSocket) to protect key exchange in transit
- Persistent key storage
- Group messaging
- Message sequence numbers for replay protection
