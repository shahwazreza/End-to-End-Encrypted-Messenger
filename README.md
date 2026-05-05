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
├── Server.java
├── Client.java
├── Main.java
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

### Option 1 — Direct compile

```powershell
javac *.java crypto/*.java
```

### Option 2 — Maven

```powershell
mvn compile
```

### Start the Server

```powershell
java Server
```

To use a custom port:

```powershell
java -Dserver.port=6000 Server
```

### Start Clients (in separate terminals)

Terminal 1:
```powershell
java Client Alice Bob
```

Terminal 2:
```powershell
java Client Bob Alice
```

To connect to a remote server:

```powershell
java -Dserver.host=192.168.1.10 -Dserver.port=5000 Client Alice Bob
```

Type messages in either terminal. Each client waits up to 30 seconds for the peer to connect.


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

- TLS transport layer (SSLServerSocket) to protect key exchange in transit
- GUI chat interface
- Persistent key storage
- Group messaging
- Message sequence numbers for replay protection
