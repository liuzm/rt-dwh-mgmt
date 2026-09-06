package com.rtdwh.controller;

import com.rtdwh.dto.ApiResponse;
import com.rtdwh.entity.OperationAudit;
import com.rtdwh.repository.OperationAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.criteria.Predicate;

@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {
    private final OperationAuditRepository repository;

    @GetMapping
    @PreAuthorize("hasAuthority('audit:view')")
    public ApiResponse<Page<OperationAudit>> list(@RequestParam(defaultValue = "") String username,
                                                  @RequestParam(defaultValue = "") String keyword,
                                                  @RequestParam(defaultValue = "") String resourceType,
                                                  @RequestParam(required = false) Boolean success,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                                  @RequestParam(required = false)
                                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<OperationAudit> filters = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!username.isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("username")),
                        "%" + username.trim().toLowerCase() + "%"));
            }
            if (!resourceType.isBlank()) {
                predicates.add(builder.equal(root.get("resourceType"), resourceType.trim()));
            }
            if (success != null) predicates.add(builder.equal(root.get("success"), success));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            if (!keyword.isBlank()) {
                String pattern = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("requestPath")), pattern),
                        builder.like(builder.lower(root.get("action")), pattern),
                        builder.like(builder.lower(root.get("resourceId")), pattern),
                        builder.like(builder.lower(root.get("errorMessage")), pattern)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return ApiResponse.success(repository.findAll(filters, pageable));
    }
}
