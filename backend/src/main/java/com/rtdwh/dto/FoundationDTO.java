package com.rtdwh.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class FoundationDTO {
    private FoundationDTO() {}

    public record Summary(List<Capability> capabilities, List<SlaRisk> slaRisks,
                          int overallScore, LocalDateTime generatedAt) {}

    public record Capability(String key, String name, String description, String status,
                             int score, int riskCount, Map<String, Long> metrics, String path) {}

    public record SlaRisk(Long outputId, Long taskId, String qualifiedName, String layer,
                          String owner, Integer slaMinutes, LocalDateTime lastProducedAt,
                          long overdueMinutes, String severity) {}

    public record SearchItem(String type, Long id, String title, String subtitle,
                             String status, String path) {}
}
