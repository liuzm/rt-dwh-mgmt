package com.rtdwh.dto;

import java.util.List;
import java.util.Map;

public record LineageGraphDTO(List<Node> nodes, List<Edge> edges) {
    public record Node(String id, String name, String qualifiedName, String type,
                       String layer, String status, Map<String, Object> metadata) {}
    public record Edge(String id, String source, String target, String type,
                       String label, Long taskId) {}
}
