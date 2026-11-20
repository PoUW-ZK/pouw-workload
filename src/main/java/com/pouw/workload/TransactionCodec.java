package com.pouw.workload;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class TransactionCodec {
    private static final byte[] SIGNING_DOMAIN = "pouw-zk/tx-sig/v1".getBytes(StandardCharsets.US_ASCII);

    private TransactionCodec() {
    }

    static SignedTransfer signTransfer(
            long chainId,
            Wallet sender,
            long nonce,
            long fee,
            String recipient,
            long amount
    ) {
        if (sender == null) {
            throw new IllegalArgumentException("sender must not be null");
        }
        if (chainId < 0 || chainId > 0xffff_ffffL) {
            throw new IllegalArgumentException("chainId must fit in u32");
        }
        if (nonce < 0 || fee < 0 || amount < 0) {
            throw new IllegalArgumentException("nonce, fee, and amount must be non-negative");
        }

        ByteBuffer bodyBuffer = ByteBuffer.allocate(93).order(ByteOrder.LITTLE_ENDIAN);
        bodyBuffer.putInt((int) chainId);
        bodyBuffer.put(sender.publicKey());
        bodyBuffer.putLong(nonce);
        bodyBuffer.putLong(fee);
        bodyBuffer.put((byte) 0);
        bodyBuffer.put(decodeAddress(recipient));
        bodyBuffer.putLong(amount);
        byte[] body = bodyBuffer.array();

        ByteArrayOutputStream signingBytes = new ByteArrayOutputStream();
        signingBytes.writeBytes(SIGNING_DOMAIN);
        signingBytes.writeBytes(body);
        byte[] signature = sender.sign(signingBytes.toByteArray());
        if (signature.length != 64) {
            throw new IllegalStateException("Ed25519 signatures must contain 64 bytes");
        }

        ByteArrayOutputStream signedTransaction = new ByteArrayOutputStream();
        signedTransaction.writeBytes(body);
        signedTransaction.writeBytes(signature);
        byte[] bytes = signedTransaction.toByteArray();
        return new SignedTransfer(body, signature, bytes, "tx:" + sha256Hex(bytes));
    }

    static byte[] decodeAddress(String address) {
        if (address == null || !address.startsWith("addr:") || address.length() != 69) {
            throw new IllegalArgumentException("address must be addr: followed by 64 lowercase hex digits");
        }
        String hex = address.substring(5);
        if (!hex.equals(hex.toLowerCase(java.util.Locale.ROOT))
                || !hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("address must contain 64 lowercase hex digits");
        }
        return HexFormat.of().parseHex(hex);
    }

    static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    record SignedTransfer(byte[] body, byte[] signature, byte[] bytes, String txid) {
        SignedTransfer {
            body = body.clone();
            signature = signature.clone();
            bytes = bytes.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
