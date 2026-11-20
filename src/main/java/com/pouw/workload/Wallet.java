package com.pouw.workload;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

final class Wallet {
    private static final byte[] ED25519_PKCS8_PREFIX =
            HexFormat.of().parseHex("302e020100300506032b657004220420");

    private final PrivateKey privateKey;
    private final byte[] publicKey;

    private Wallet(PrivateKey privateKey, byte[] publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey.clone();
    }

    static Wallet generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair pair = generator.generateKeyPair();
            byte[] encodedPublicKey = pair.getPublic().getEncoded();
            byte[] rawPublicKey = Arrays.copyOfRange(
                    encodedPublicKey, encodedPublicKey.length - 32, encodedPublicKey.length);
            return new Wallet(pair.getPrivate(), rawPublicKey);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Ed25519 key generation is unavailable", error);
        }
    }

    static Wallet fromSeed(byte[] seed, String address) {
        if (seed == null || seed.length != 32) {
            throw new IllegalArgumentException("Ed25519 seed must contain 32 bytes");
        }
        try {
            byte[] encodedPrivateKey = new byte[ED25519_PKCS8_PREFIX.length + seed.length];
            System.arraycopy(ED25519_PKCS8_PREFIX, 0, encodedPrivateKey, 0, ED25519_PKCS8_PREFIX.length);
            System.arraycopy(seed, 0, encodedPrivateKey, ED25519_PKCS8_PREFIX.length, seed.length);
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(encodedPrivateKey));
            return new Wallet(privateKey, TransactionCodec.decodeAddress(address));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to decode the Ed25519 seed", error);
        }
    }

    String address() {
        return "addr:" + HexFormat.of().formatHex(publicKey);
    }

    byte[] publicKey() {
        return publicKey.clone();
    }

    byte[] sign(byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to sign the transaction", error);
        }
    }
}
