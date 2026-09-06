package com.rtdwh.service;

import com.rtdwh.entity.SyncTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresCdcNamingTest {
    @Test
    void createsStableValidPostgresResourceNames() {
        SyncTask task = new SyncTask();
        task.setId(42L);
        task.setTaskName("ignored");

        assertThat(PostgresCdcNaming.slot(task, "Order_Items")).isEqualTo("rtdwh_42_order_items");
        assertThat(PostgresCdcNaming.publication(task, "Order_Items")).isEqualTo("rtdwh_42_order_items_pub");
    }

    @Test
    void truncatesNamesToPostgresIdentifierLimitWithoutCollisions() {
        SyncTask task = new SyncTask();
        task.setTaskName("very-long-preview-task-name-with-unsafe 中文 characters");
        String left = PostgresCdcNaming.slot(task, "a".repeat(90));
        String right = PostgresCdcNaming.slot(task, "a".repeat(89) + "b");

        assertThat(left).hasSizeLessThanOrEqualTo(63).matches("[a-z0-9_]+");
        assertThat(right).hasSizeLessThanOrEqualTo(63).isNotEqualTo(left);
    }
}
