package com.rtdwh.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncryptionUtilTest {

    @Test
    void ciphertextCanBeDecryptedAfterRecreatingUtilityWithShortKey() {
        EncryptionUtil firstProcess = new EncryptionUtil("local-dev-key");
        String encrypted = firstProcess.encrypt("root123123");

        EncryptionUtil restartedProcess = new EncryptionUtil("local-dev-key");

        assertEquals("root123123", restartedProcess.decrypt(encrypted));
    }

    @Test
    void emptyConfiguredKeyIsStableAcrossRestarts() {
        EncryptionUtil firstProcess = new EncryptionUtil("");
        String encrypted = firstProcess.encrypt("root123123");

        EncryptionUtil restartedProcess = new EncryptionUtil("");

        assertEquals("root123123", restartedProcess.decrypt(encrypted));
    }

    @Test
    void emptyStoredPasswordStaysEmpty() {
        assertEquals("", new EncryptionUtil("local-dev-key").decrypt(""));
    }
}
