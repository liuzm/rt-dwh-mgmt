package com.rtdwh.config;

import com.rtdwh.entity.OperationAudit;
import com.rtdwh.repository.OperationAuditRepository;
import com.rtdwh.util.SecurityContextUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class OperationAuditInterceptor implements HandlerInterceptor {
    private static final String STARTED_AT = OperationAuditInterceptor.class.getName() + ".startedAt";
    private static final String USER_ID = OperationAuditInterceptor.class.getName() + ".userId";
    private static final String USERNAME = OperationAuditInterceptor.class.getName() + ".username";
    private final OperationAuditRepository repository;
    private final SecurityContextUtil securityContextUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(STARTED_AT, System.currentTimeMillis());
        try {
            request.setAttribute(USER_ID, securityContextUtil.getCurrentUserId());
            request.setAttribute(USERNAME, securityContextUtil.getCurrentUsername());
        } catch (Exception ignored) {
            // Anonymous/public requests are excluded from persistence below.
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        if (!(handler instanceof HandlerMethod) || "GET".equalsIgnoreCase(request.getMethod())
                || request.getRequestURI().startsWith("/auth/")) return;
        try {
            if (request.getAttribute(USERNAME) == null) return;
            String[] segments = request.getRequestURI().replaceFirst("^/api/v1/", "").split("/");
            String resourceType = segments.length == 0 || segments[0].isBlank() ? "unknown" : segments[0];
            String resourceId = segments.length > 1 && segments[1].matches("\\d+") ? segments[1] : null;
            long started = request.getAttribute(STARTED_AT) instanceof Long value ? value : System.currentTimeMillis();
            repository.save(OperationAudit.builder()
                    .userId((Long) request.getAttribute(USER_ID))
                    .username((String) request.getAttribute(USERNAME))
                    .httpMethod(request.getMethod())
                    .requestPath(request.getRequestURI())
                    .action(request.getMethod() + " " + resourceType)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .clientIp(clientIp(request))
                    .success(exception == null && response.getStatus() < 400)
                    .responseStatus(response.getStatus())
                    .durationMs(System.currentTimeMillis() - started)
                    .errorMessage(exception == null ? null : concise(exception.getMessage()))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception auditError) {
            log.warn("Operation audit persistence failed: {}", auditError.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    private String concise(String value) {
        if (value == null) return null;
        return value.length() > 1800 ? value.substring(0, 1800) + "..." : value;
    }
}
