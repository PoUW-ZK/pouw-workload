package com.pouw.workload;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TransactionCodecTest {
    @Test
    void producesTheContractTransferVectorByteForByte() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 1);
        Wallet wallet = Wallet.fromSeed(seed, TransferOperationExecutor.GENESIS_ADDRESS);
        String recipient = "addr:" + "22".repeat(32);

        TransactionCodec.SignedTransfer transaction = TransactionCodec.signTransfer(
                1, wallet, 0, 10, recipient, 1000);

        assertEquals(
                "010000008a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
                        + "00000000000000000a0000000000000000"
                        + "22".repeat(32)
                        + "e803000000000000",
                HexFormat.of().formatHex(transaction.body()));
        assertEquals(
                "507d5e19dca04f6961f00fd6f6cb0e31dffa042c43357bada9160696778aa4f45c"
                        + "720c67845a2251dfcd4e0fd078790dec9cd32392b651e1ecb24128960f5304",
                HexFormat.of().formatHex(transaction.signature()));
        assertEquals(157, transaction.bytes().length);
        assertEquals(
                "tx:41ef667fcdab0af9fbf57805b90f90a60825c43d4a796b1f26980207e622e6f7",
                transaction.txid());
        assertArrayEquals(HexFormat.of().parseHex(
                HexFormat.of().formatHex(transaction.body())
                        + HexFormat.of().formatHex(transaction.signature())),
                transaction.bytes());
    }
}
