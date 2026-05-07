# End-to-End Encrypted Messenger v2 (Java)

A full end-to-end encrypted messaging application built in Java.  
Users create accounts, sign in, browse online users on a live dashboard, and chat with end-to-end encryption — the server never sees message contents or plaintext passwords.

This project demonstrates real E2EE fundamentals:
- X25519 Diffie-Hellman key exchange
- HKDF key derivation (RFC 5869)
- AES-256-GCM authenticated encryption
- PBKDF2WithHmacSHA256 password hashing
- Persistent identity and message history


## Features

- **Account system** — register and sign in with a username and password
- **Live dashboard** — see who's online in real time, search by username
- **End-to-end encryption** — server never sees plaintext
- **Persistent identity** — X25519 key pair saved per user, consistent across sessions
- **Message history** — chat history saved locally and restored on reconnect
- **TLS transport** — always-on TLS layer to protect the connection
- **JavaFX desktop GUI** and **CLI** client


## File Structure

```
e2ee-messenger/
│
├── ChatApp.java          ← JavaFX desktop GUI (auth, dashboard, chat screens)
├── MessengerClient.java  ← networking/crypto core (auth + dashboard + chat)
├── Client.java           ← CLI client
├── Server.java           ← relay server with account auth and user broadcasting
├── AccountStore.java     ← PBKDF2 password hashing + account persistence
├── MessageHistory.java   ← per-user chat history (save + load)
├── Main.java             ← crypto smoke test
├── pom.xml
│
├── crypto/
│   ├── KeyManager.java   ← X25519 key pair generation + persistent storage
│   ├── KeyDerivation.java
│   └── Encryption.java
│
└── README.md
```


## How It Works

### 1. Account Authentication
On first use, the client sends `REGISTER_ACCOUNT|username|password`. On subsequent sessions, it sends `LOGIN|username|password`. The server hashes passwords with PBKDF2WithHmacSHA256 (310,000 iterations, random salt) and stores them in `~/.messenger-server/accounts.dat`. Passwords are never stored or transmitted in plaintext.

### 2. Dashboard
After authentication, the server sends the current online user list and broadcasts `USER_JOINED` / `USER_LEFT` events in real time. The GUI dashboard updates live.

### 3. Key Exchange
When a user starts a chat, their X25519 key pair is loaded from `~/.messenger/<username>/` (or generated on first use). The public key is registered with the server, and the client polls for the peer's public key. Once both keys are available, a shared secret is derived using X25519 + HKDF (RFC 5869, HMAC-SHA256) to produce a 32-byte AES-256 key.

### 4. Encryption
Every message is encrypted with AES-256-GCM using a unique 12-byte random IV:

```
AES/GCM/NoPadding  |  256-bit key  |  128-bit auth tag  |  12-byte IV
```

The IV is prepended to the ciphertext and Base64-encoded before sending.

### 5. Message Flow

```
Sender → encrypt(AES-256-GCM) → server relay → receiver → decrypt
```

The server only forwards ciphertext. It never has access to keys or plaintext.

### 6. Message History
Sent and received messages are saved to `~/.messenger/<username>/history/<peer>.log` and displayed when a chat is reopened.


## How to Run

### Requirements
- Java 17+
- [Maven 3.8+](https://maven.apache.org/download.cgi) (required for the JavaFX GUI)

> Maven is only needed for the GUI. The CLI works with plain `javac`.

---

### Desktop GUI (JavaFX)

Maven downloads JavaFX automatically on first run.

```powershell
# Launch the GUI
mvn javafx:run
```

**First time:** select **Register**, enter a username and password, click **Create Account**.  
**Returning:** select **Sign In**, enter your credentials, click **Sign In**.  

When connecting to `localhost`, the GUI automatically generates TLS certificates and starts a TLS server in the background — no manual setup needed. For a remote server, point the host field at it and ensure the remote server is running with TLS.

Once signed in, the dashboard shows all online users. Click **Chat** next to a username to open a secure chat. Both users need to be online for the key exchange to complete (30-second timeout).

---

### CLI (no Maven needed)

TLS is on by default, so you need to generate certificates before starting the server.

```powershell
# Compile
javac AccountStore.java MessageHistory.java crypto/*.java
javac -cp . Server.java MessengerClient.java Client.java

# Generate TLS certificates (one-time setup)
keytool -genkeypair -alias messenger-server -keyalg RSA -keysize 2048 `
  -keystore server-keystore.p12 -storetype PKCS12 -storepass changeit `
  -validity 365 -dname "CN=localhost" -ext "SAN=DNS:localhost,IP:127.0.0.1"

keytool -exportcert -alias messenger-server -keystore server-keystore.p12 `
  -storepass changeit -rfc -file server-cert.pem

keytool -importcert -alias messenger-server -file server-cert.pem `
  -keystore client-truststore.p12 -storetype PKCS12 -storepass changeit -noprompt

# Start the TLS server
java "-Djavax.net.ssl.keyStore=server-keystore.p12" `
     "-Djavax.net.ssl.keyStorePassword=changeit" `
     -cp "." Server

# Register (first time)
java "-Djavax.net.ssl.trustStore=client-truststore.p12" `
     "-Djavax.net.ssl.trustStorePassword=changeit" `
     Client --register alice mypassword bob

# Login (after registering)
java "-Djavax.net.ssl.trustStore=client-truststore.p12" `
     "-Djavax.net.ssl.trustStorePassword=changeit" `
     Client alice mypassword bob
```

To use a custom host or port:

```powershell
java -Dserver.host=192.168.1.10 -Dserver.port=6000 `
     "-Djavax.net.ssl.trustStore=client-truststore.p12" `
     "-Djavax.net.ssl.trustStorePassword=changeit" `
     Client alice mypassword bob
```


## Data Storage

| What | Where |
|---|---|
| Account credentials | `~/.messenger-server/accounts.dat` |
| Identity key pair | `~/.messenger/<username>/identity.priv` + `identity.pub` |
| Chat history | `~/.messenger/<username>/history/<peer>.log` |


## Security Properties

| Property | Implementation |
|---|---|
| Confidentiality | AES-256-GCM |
| Integrity & authenticity | GCM 128-bit authentication tag |
| Key agreement | X25519 Diffie-Hellman |
| Key derivation | HKDF-Extract + HKDF-Expand (RFC 5869, HMAC-SHA256) |
| IV generation | 12-byte cryptographically random per message |
| Password storage | PBKDF2WithHmacSHA256, 310,000 iterations, random salt |
| Server access | Zero — relays ciphertext only |


## Planned Improvements

- Web frontend (React + WebCrypto API)
- ~~TLS transport layer (SSLServerSocket) to protect key exchange in transit~~
- ~~Persistent key storage~~
- ~~Persistent message history~~
- Group messaging
- Message sequence numbers for replay protection
