package com.rtdwh.dto;

import java.time.LocalDateTime;

public record DwhSnapshotDTO(
        long snapshotId,
        long schemaId,
        String commitKind,
        LocalDateTime commitTime,
        long recordCount,
        long deltaRecordCount,
        long manifestSizeBytes
) {}
