package com.rtdwh.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.SyncTask;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class RuntimeEnvironmentServiceTest {
    @Test void changingWarehouseRequiresRepublish() {
        MockEnvironment env = new MockEnvironment().withProperty("paimon.warehouse-path", "/lake/a");
        RuntimeEnvironmentService service = new RuntimeEnvironmentService(env, new ObjectMapper());
        SyncTask task = SyncTask.builder().flinkSql("select 1").build();
        service.freeze(task); service.validate(task);
        env.setProperty("paimon.warehouse-path", "/lake/b");
        assertThrows(IllegalArgumentException.class, () -> service.validate(task));
        service.freeze(task); assertDoesNotThrow(() -> service.validate(task));
    }
    @Test void credentialRotationDoesNotFreezeSecretOrChangeFingerprint() {
        MockEnvironment env = new MockEnvironment().withProperty("paimon.jdbc-password", "private-secret")
                .withProperty("paimon.jdbc-uri", "jdbc:mysql://db/meta?password=private-secret");
        RuntimeEnvironmentService service = new RuntimeEnvironmentService(env, new ObjectMapper());
        SyncTask task = SyncTask.builder().sourceConfigId(4L).build(); service.freeze(task);
        assertFalse(task.getRuntimeConfigJson().contains("private-secret"));
        assertTrue(task.getRuntimeConfigJson().contains("datasource:4"));
        env.setProperty("paimon.jdbc-password", "rotated-secret"); service.validate(task);
    }
    @Test void inlineCredentialsCannotBePublishedButCdcReferencesCan() {
        RuntimeEnvironmentService service = new RuntimeEnvironmentService(new MockEnvironment(), new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> service.freeze(SyncTask.builder().flinkSql("create table t with ('password'='private-secret')").build()));
        assertDoesNotThrow(() -> service.freeze(SyncTask.builder().flinkSql("create table t with ('password'='__RTDWH_SOURCE_CREDENTIAL__')").build()));
    }
}
