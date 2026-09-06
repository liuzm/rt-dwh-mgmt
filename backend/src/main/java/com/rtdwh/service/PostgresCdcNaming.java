package com.rtdwh.service;

import com.rtdwh.entity.SyncTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

final class PostgresCdcNaming {
    private PostgresCdcNaming() {}

    static String slot(SyncTask task, String sourceTable) {
        String taskKey = task.getId() == null ? task.getTaskName() : String.valueOf(task.getId());
        return limit("rtdwh_" + normalize(taskKey) + "_" + normalize(sourceTable), 63);
    }

    static String publication(SyncTask task, String sourceTable) {
        return limit(slot(task, sourceTable) + "_pub", 63);
    }

    private static String normalize(String value) {
        String normalized = (value == null ? "preview" : value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "preview" : normalized;
    }

    private static String limit(String value, int max) {
        if (value.length() <= max) return value;
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 10);
        } catch (Exception impossible) {
            hash = Integer.toUnsignedString(value.hashCode(), 36);
        }
        return value.substring(0, Math.max(1, max - hash.length() - 1)) + "_" + hash;
    }
}
