package com.rtdwh.service;

import com.rtdwh.dto.DataServiceDTO;
import com.rtdwh.entity.*;
import com.rtdwh.exception.DataServiceAuthException;
import com.rtdwh.exception.DataServiceRateLimitException;
import com.rtdwh.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class DataServiceService {
    private final DataServiceDefinitionRepository definitionRepository;
    private final DataServiceAppRepository appRepository;
    private final DataServiceGrantRepository grantRepository;
    private final DataServiceInvocationLogRepository logRepository;
    private final ReportParameterRenderer parameterRenderer;
    private final QueryService queryService;
    private final PasswordEncoder passwordEncoder;
    private final QueryAccessScopeService accessScopeService;
    private final DataServiceVersionRepository versionRepository;
    private final DataServiceContractService contracts;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    public List<DataServiceDefinition> definitions(Long userId) {
        return definitionRepository.findAll().stream().filter(value -> canReadDefinition(value, userId))
                .map(value -> decorate(value, userId)).toList();
    }
    public DataServiceDefinition definition(Long id, Long userId) {
        DataServiceDefinition value = requireDefinition(id);
        assertDefinitionAccess(value, userId);
        return decorate(value, userId);
    }
    public List<DataServiceVersion> versions(Long id, Long userId) {
        return versionRepository.findByServiceIdOrderByVersionNoDesc(id).stream()
                .filter(version -> canAccessVersion(version, userId)).toList();
    }
    public DataServiceVersion publishedVersion(Long id, Long userId) {
        DataServiceVersion version = currentVersion(requireDefinition(id));
        if (version == null) throw new IllegalArgumentException("该服务尚无发布版本");
        assertVersionAccess(version, userId);
        return version;
    }
    public List<DataServiceDefinition> publishedDefinitions(Long userId) {
        return definitionRepository.findAll().stream().filter(value -> value.getStatus() == DataServiceDefinition.ServiceStatus.published)
                .map(this::currentVersion).filter(Objects::nonNull).filter(value -> canAccessVersion(value, userId))
                .map(this::asDefinition).toList();
    }
    public List<DataServiceApp> apps(Long userId) {
        return appRepository.findAll().stream().filter(app -> accessScopeService.isAdmin(userId)
                || Objects.equals(app.getCreatedBy(), userId)).toList();
    }
    public List<DataServiceGrant> grants(Long appId, Long userId) {
        assertAppAccess(appId, userId);
        Set<Long> allowed = new HashSet<>();
        definitions(userId).forEach(value -> allowed.add(value.getId()));
        return grantRepository.findByAppId(appId).stream().filter(value -> allowed.contains(value.getServiceId())).toList();
    }
    public List<DataServiceInvocationLog> logs(int limit, Long userId) {
        return logRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .stream().filter(value -> accessScopeService.isAdmin(userId) || value.getVersionId() != null
                        && versionRepository.findById(value.getVersionId()).filter(v -> Objects.equals(v.getServiceId(), value.getServiceId()))
                        .map(v -> canAccessVersion(v, userId)).orElse(false)).toList();
    }

    @Transactional
    public DataServiceDefinition createDefinition(DataServiceDTO.DefinitionRequest request, Long userId) {
        if (definitionRepository.existsByServiceCode(request.getServiceCode())) throw new IllegalStateException("服务编码已存在");
        validate(request);
        assertSqlAccess(request, userId);
        return decorate(definitionRepository.saveAndFlush(apply(new DataServiceDefinition(), request, userId)), userId);
    }

    @Transactional
    public DataServiceDefinition updateDefinition(Long id, DataServiceDTO.DefinitionRequest request, Long userId) {
        DataServiceDefinition definition = requireDefinitionForUpdate(id);
        assertManage(definition, userId);
        checkRevision(definition, request == null ? null : request.getExpectedRevision());
        if (!definition.getServiceCode().equals(request.getServiceCode())) throw new IllegalArgumentException("服务编码发布后不可修改");
        validate(request);
        assertSqlAccess(request, userId);
        return decorate(definitionRepository.saveAndFlush(apply(definition, request, definition.getCreatorId())), userId);
    }

    @Transactional
    public DataServiceDefinition publish(Long id, boolean published, Long userId, DataServiceDTO.PublicationRequest request) {
        DataServiceDefinition definition = requireDefinitionForUpdate(id);
        assertManage(definition, userId);
        checkRevision(definition, request == null ? null : request.getExpectedRevision());
        if (!published) {
            definition.setStatus(DataServiceDefinition.ServiceStatus.offline);
            return decorate(definitionRepository.saveAndFlush(definition), userId);
        }
        return release(definition, definition, userId, request, "publish", null);
    }

    @Transactional(readOnly = true)
    public DataServiceDTO.PublicationPreview preview(Long id, Long userId, DataServiceDTO.PublicationRequest request) {
        DataServiceDefinition definition = requireDefinition(id);
        assertManage(definition, userId);
        checkRevision(definition, request.getExpectedRevision());
        return inspectPublication(definition, definition, userId);
    }

    @Transactional
    public DataServiceDefinition rollback(Long id, Long versionId, Long userId, DataServiceDTO.PublicationRequest request) {
        DataServiceDefinition definition = requireDefinitionForUpdate(id);
        assertManage(definition, userId);
        checkRevision(definition, request.getExpectedRevision());
        DataServiceVersion version = versionRepository.findById(versionId)
                .filter(value -> Objects.equals(id, value.getServiceId()))
                .orElseThrow(() -> new IllegalArgumentException("发布版本不存在"));
        assertVersionAccess(version, userId);
        return release(definition, asDefinition(version), userId, request, "rollback", versionId);
    }

    private DataServiceDefinition release(DataServiceDefinition definition, DataServiceDefinition candidate, Long actor,
                                           DataServiceDTO.PublicationRequest request, String origin, Long sourceVersionId) {
        var preview = inspectPublication(definition, candidate, actor);
        if (!preview.publishable()) throw new IllegalStateException(String.join("；", preview.breakingChanges()));
        int versionNo = versionRepository.findFirstByServiceIdOrderByVersionNoDesc(definition.getId())
                .map(v -> v.getVersionNo() + 1).orElse(1);
        DataServiceVersion version = versionRepository.saveAndFlush(DataServiceVersion.builder()
                .serviceId(definition.getId()).versionNo(versionNo).serviceCode(definition.getServiceCode())
                .serviceName(candidate.getServiceName()).description(candidate.getDescription()).creatorId(definition.getCreatorId())
                .sqlTemplate(candidate.getSqlTemplate()).parameterConfig(candidate.getParameterConfig())
                .catalogName(candidate.getCatalogName()).databaseName(candidate.getDatabaseName()).maxRows(candidate.getMaxRows())
                .timeoutSeconds(candidate.getTimeoutSeconds()).rateLimitPerMinute(candidate.getRateLimitPerMinute())
                .resultColumnsJson(contracts.json(preview.resultColumns())).dependenciesJson(contracts.json(preview.dependencies()))
                .sourceRevision(definition.getRevision()).origin(origin).sourceVersionId(sourceVersionId)
                .publishedBy(actor).changeSummary(request.getChangeSummary()).createdAt(LocalDateTime.now()).build());
        definition.setPublishedVersionId(version.getId()); definition.setApiVersion(version.getVersionNo());
        definition.setPublishedAt(version.getCreatedAt()); definition.setStatus(DataServiceDefinition.ServiceStatus.published);
        return decorate(definitionRepository.saveAndFlush(definition), actor);
    }

    private DataServiceDTO.PublicationPreview inspectPublication(DataServiceDefinition definition, DataServiceDefinition candidate, Long actor) {
        var inspection = contracts.inspect(candidate, actor);
        var current = currentVersion(definition);
        List<String> breaking = List.of();
        String basis = "first_publication";
        if (current != null) {
            assertVersionAccess(current, actor);
            var previous = current.getResultColumnsJson() == null
                    ? contracts.inspect(asDefinition(current), actor).columns() : contracts.columns(current.getResultColumnsJson());
            basis = current.getResultColumnsJson() == null ? "legacy_current_schema" : "published_contract";
            breaking = contracts.breakingChanges(current, candidate, previous, inspection.columns());
        }
        return new DataServiceDTO.PublicationPreview(definition.getRevision(), definition.getPublishedVersionId(), breaking.isEmpty(),
                changes(current, candidate), breaking, inspection.columns(), inspection.dependencies(), basis);
    }

    @Transactional
    public void deleteDefinition(Long id, Long userId) {
        DataServiceDefinition definition = requireDefinitionForUpdate(id);
        assertManage(definition, userId);
        if (definition.getStatus() == DataServiceDefinition.ServiceStatus.published) throw new IllegalStateException("请先下线数据服务");
        definitionRepository.delete(definition);
    }

    @Transactional
    public DataServiceDTO.AppCredential createApp(DataServiceDTO.AppRequest request, Long userId) {
        String secret = token(32);
        DataServiceApp app = appRepository.save(DataServiceApp.builder().appName(request.getAppName().trim())
                .appKey("dsa_" + token(12)).secretHash(passwordEncoder.encode(secret)).enabled(true)
                .expiresAt(request.getExpiresAt()).createdBy(userId).build());
        return credential(app, secret);
    }

    @Transactional
    public DataServiceDTO.AppCredential rotateSecret(Long appId, Long userId) {
        DataServiceApp app = assertAppAccess(appId, userId);
        String secret = token(32);
        app.setSecretHash(passwordEncoder.encode(secret));
        appRepository.save(app);
        return credential(app, secret);
    }

    @Transactional
    public DataServiceApp toggleApp(Long appId, Long userId) {
        DataServiceApp app = assertAppAccess(appId, userId);
        app.setEnabled(!Boolean.TRUE.equals(app.getEnabled()));
        return appRepository.save(app);
    }

    @Transactional
    public DataServiceGrant grant(Long appId, Long serviceId, Long userId) {
        assertAppAccess(appId, userId); assertDefinitionAccess(requireDefinition(serviceId), userId);
        if (grantRepository.existsByAppIdAndServiceId(appId, serviceId)) throw new IllegalStateException("应用已获得该服务授权");
        return grantRepository.save(DataServiceGrant.builder().appId(appId).serviceId(serviceId).createdBy(userId).build());
    }

    @Transactional
    public void revoke(Long appId, Long serviceId, Long userId) {
        assertAppAccess(appId, userId);
        assertDefinitionAccess(requireDefinition(serviceId), userId);
        grantRepository.deleteByAppIdAndServiceId(appId, serviceId);
    }

    public Map<String, Object> invoke(String code, String appKey, String appSecret,
                                      Map<String, Object> parameters, String clientIp) {
        long started = System.currentTimeMillis();
        DataServiceDefinition definition = definitionRepository.findByServiceCode(code).orElse(null);
        DataServiceApp app = null;
        DataServiceVersion version = null;
        try {
            if (definition == null || definition.getStatus() != DataServiceDefinition.ServiceStatus.published) {
                throw new IllegalArgumentException("数据服务不存在或未发布");
            }
            app = authenticate(appKey, appSecret);
            if (!grantRepository.existsByAppIdAndServiceId(app.getId(), definition.getId())) {
                throw new DataServiceAuthException("应用未获得该数据服务授权");
            }
            version = currentVersion(definition);
            if (version == null) throw new IllegalStateException("数据 API 缺少发布快照，请重新发布");
            accessScopeService.assertDataServiceExecutionIdentity(version.getCreatorId());
            DataServiceDefinition executable = asDefinition(version);
            checkRate(app.getId(), executable);
            String sql = parameterRenderer.render(version.getSqlTemplate(), version.getParameterConfig(), parameters);
            Map<String, Object> result = queryService.executeDataServiceQuery(sql, version.getCreatorId(),
                    version.getCatalogName(), version.getDatabaseName(), version.getMaxRows(), version.getTimeoutSeconds());
            boolean success = "success".equals(result.get("status"));
            if (!success) throw new IllegalStateException("数据服务查询失败: " + result.get("errorMsg"));
            contracts.validateResult(version, result.get("columnSchema"));
            saveLog(definition, version, app, "success", 200, number(result.get("rowCount")),
                    System.currentTimeMillis() - started, null, clientIp);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("serviceCode", code); response.put("apiVersion", version.getVersionNo());
            response.put("versionId", version.getId()); response.put("contractOrigin", version.getOrigin());
            response.put("columns", result.get("columns")); response.put("rows", result.get("rows"));
            response.put("rowCount", result.get("rowCount")); response.put("truncated", result.get("truncated"));
            response.put("durationMs", result.get("durationMs")); response.put("requestId", result.get("requestId"));
            return response;
        } catch (RuntimeException exception) {
            if (definition != null) {
                int status = exception instanceof DataServiceAuthException ? 401 : exception instanceof DataServiceRateLimitException ? 429
                        : exception instanceof org.springframework.security.access.AccessDeniedException ? 403
                        : exception instanceof IllegalStateException ? 409 : 400;
                saveLog(definition, version, app, "failed", status, null, System.currentTimeMillis() - started, exception.getMessage(), clientIp);
            }
            throw exception;
        }
    }

    public boolean canReadDefinition(DataServiceDefinition definition, Long userId) {
        try {
            if (accessScopeService.isAdmin(userId)) return true;
            if (!accessScopeService.canAccessDorisSql(userId,
                    parameterRenderer.sqlForAccessCheck(definition.getSqlTemplate(), definition.getParameterConfig()),
                    definition.getCatalogName(), definition.getDatabaseName())) return false;
            DataServiceVersion published = currentVersion(definition);
            return published == null || canAccessVersion(published, userId);
        } catch (RuntimeException invalid) { return false; }
    }

    private void assertDefinitionAccess(DataServiceDefinition definition, Long userId) {
        if (!canReadDefinition(definition, userId)) throw new org.springframework.security.access.AccessDeniedException("无权访问该数据服务");
    }

    private void assertManage(DataServiceDefinition definition, Long userId) {
        assertDefinitionAccess(definition, userId);
        if (!accessScopeService.isAdmin(userId) && !Objects.equals(userId, definition.getCreatorId())) {
            throw new org.springframework.security.access.AccessDeniedException("只有创建者或管理员可以修改数据 API");
        }
    }

    private boolean canAccessVersion(DataServiceVersion version, Long userId) {
        try {
            return accessScopeService.isAdmin(userId) || accessScopeService.canAccessDorisSql(userId,
                    parameterRenderer.sqlForAccessCheck(version.getSqlTemplate(), version.getParameterConfig()),
                    version.getCatalogName(), version.getDatabaseName());
        } catch (RuntimeException invalid) { return false; }
    }

    private void assertVersionAccess(DataServiceVersion version, Long userId) {
        if (!canAccessVersion(version, userId)) throw new org.springframework.security.access.AccessDeniedException("无权访问该发布版本");
    }

    private void checkRevision(DataServiceDefinition definition, Long expected) {
        if (expected == null) throw new IllegalArgumentException("请提供 expectedRevision，刷新后重试");
        if (!Objects.equals(expected, definition.getRevision())) throw new IllegalStateException("数据 API 已被修改，请刷新后重新预览");
    }

    private DataServiceVersion currentVersion(DataServiceDefinition definition) {
        if (definition.getPublishedVersionId() == null) return null;
        return versionRepository.findById(definition.getPublishedVersionId())
                .filter(value -> Objects.equals(value.getServiceId(), definition.getId())
                        && Objects.equals(value.getCreatorId(), definition.getCreatorId())
                        && Objects.equals(value.getServiceCode(), definition.getServiceCode()))
                .orElseThrow(() -> new IllegalStateException("数据 API 发布指针无效"));
    }

    private DataServiceDefinition decorate(DataServiceDefinition definition, Long userId) {
        definition.setHasDraftChanges(!changes(currentVersion(definition), definition).isEmpty());
        definition.setManageable(accessScopeService.isAdmin(userId) || Objects.equals(userId, definition.getCreatorId()));
        return definition;
    }

    private List<String> changes(DataServiceVersion current, DataServiceDefinition candidate) {
        if (current == null) return List.of("首次发布");
        List<String> result = new ArrayList<>();
        if (!Objects.equals(current.getSqlTemplate(), candidate.getSqlTemplate())) result.add("SQL");
        if (!contracts.sameJson(current.getParameterConfig(), candidate.getParameterConfig())) result.add("参数定义");
        if (!Objects.equals(current.getCatalogName(), candidate.getCatalogName()) || !Objects.equals(current.getDatabaseName(), candidate.getDatabaseName())) result.add("查询环境");
        if (!Objects.equals(current.getMaxRows(), candidate.getMaxRows()) || !Objects.equals(current.getTimeoutSeconds(), candidate.getTimeoutSeconds())
                || !Objects.equals(current.getRateLimitPerMinute(), candidate.getRateLimitPerMinute())) result.add("运行限制");
        if (!Objects.equals(current.getServiceName(), candidate.getServiceName()) || !Objects.equals(current.getDescription(), candidate.getDescription())) result.add("名称与说明");
        return result;
    }

    private DataServiceDefinition asDefinition(DataServiceVersion version) {
        return DataServiceDefinition.builder().id(version.getServiceId()).creatorId(version.getCreatorId())
                .serviceCode(version.getServiceCode()).serviceName(version.getServiceName()).description(version.getDescription())
                .sqlTemplate(version.getSqlTemplate()).parameterConfig(version.getParameterConfig())
                .catalogName(version.getCatalogName()).databaseName(version.getDatabaseName()).maxRows(version.getMaxRows())
                .timeoutSeconds(version.getTimeoutSeconds()).rateLimitPerMinute(version.getRateLimitPerMinute())
                .status(DataServiceDefinition.ServiceStatus.published).apiVersion(version.getVersionNo())
                .publishedVersionId(version.getId()).publishedAt(version.getCreatedAt()).revision(version.getSourceRevision()).build();
    }

    private void assertSqlAccess(DataServiceDTO.DefinitionRequest request, Long userId) {
        accessScopeService.validateDoris(userId, parameterRenderer.sqlForAccessCheck(request.getSqlTemplate(), request.getParameterConfig()),
                request.getCatalogName(), request.getDatabaseName());
    }

    private DataServiceApp assertAppAccess(Long appId, Long userId) {
        DataServiceApp app = requireApp(appId);
        if (!accessScopeService.isAdmin(userId) && !Objects.equals(app.getCreatedBy(), userId)) {
            throw new org.springframework.security.access.AccessDeniedException("无权管理该调用应用");
        }
        return app;
    }

    private DataServiceDefinition apply(DataServiceDefinition value, DataServiceDTO.DefinitionRequest request, Long creatorId) {
        value.setServiceCode(request.getServiceCode()); value.setServiceName(request.getServiceName().trim());
        value.setDescription(trim(request.getDescription())); value.setCreatorId(creatorId);
        value.setSqlTemplate(request.getSqlTemplate().trim()); value.setParameterConfig(trim(request.getParameterConfig()));
        value.setCatalogName(identifier(request.getCatalogName())); value.setDatabaseName(identifier(request.getDatabaseName()));
        value.setMaxRows(request.getMaxRows()); value.setTimeoutSeconds(request.getTimeoutSeconds());
        value.setRateLimitPerMinute(request.getRateLimitPerMinute());
        if (value.getStatus() == null) value.setStatus(DataServiceDefinition.ServiceStatus.draft);
        if (value.getApiVersion() == null) value.setApiVersion(1);
        return value;
    }
    private void validate(DataServiceDTO.DefinitionRequest request) {
        queryService.validateReadOnlySql(request.getSqlTemplate());
        parameterRenderer.validateTemplate(request.getSqlTemplate(), request.getParameterConfig());
    }
    private DataServiceApp authenticate(String key, String secret) {
        if (key == null || secret == null) throw new DataServiceAuthException("缺少 X-App-Key 或 X-App-Secret");
        DataServiceApp app = appRepository.findByAppKey(key).orElseThrow(() -> new DataServiceAuthException("应用凭证无效"));
        if (!Boolean.TRUE.equals(app.getEnabled()) || app.getExpiresAt() != null && app.getExpiresAt().isBefore(LocalDateTime.now())
                || !passwordEncoder.matches(secret, app.getSecretHash())) throw new DataServiceAuthException("应用凭证无效或已过期");
        return app;
    }
    private void checkRate(Long appId, DataServiceDefinition definition) {
        String key = appId + ":" + definition.getId();
        long minute = Instant.now().getEpochSecond() / 60;
        RateWindow window = rateWindows.compute(key, (ignored, current) -> current == null || current.minute != minute
                ? new RateWindow(minute, new AtomicInteger(1)) : new RateWindow(minute, new AtomicInteger(current.count.incrementAndGet())));
        if (window.count.get() > definition.getRateLimitPerMinute()) throw new DataServiceRateLimitException("接口调用频率超过每分钟限制");
    }
    private void saveLog(DataServiceDefinition definition, DataServiceVersion version, DataServiceApp app, String status, int httpStatus,
                         Integer rows, long duration, String error, String ip) {
        logRepository.save(DataServiceInvocationLog.builder().serviceId(definition.getId())
                .versionId(version == null ? null : version.getId()).apiVersion(version == null ? null : version.getVersionNo())
                .executionUserId(version == null ? null : version.getCreatorId())
                .appId(app == null ? null : app.getId()).serviceCode(definition.getServiceCode()).status(status)
                .httpStatus(httpStatus).rowCount(rows).durationMs(duration).clientIp(ip).errorMessage(trimError(error)).build());
    }
    private String token(int bytes) { byte[] value = new byte[bytes]; secureRandom.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private DataServiceDTO.AppCredential credential(DataServiceApp app, String secret) { return new DataServiceDTO.AppCredential(app.getId(), app.getAppName(), app.getAppKey(), secret, app.getEnabled(), app.getExpiresAt()); }
    private DataServiceDefinition requireDefinition(Long id) { return definitionRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("数据服务不存在: " + id)); }
    private DataServiceDefinition requireDefinitionForUpdate(Long id) { return definitionRepository.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("数据服务不存在: " + id)); }
    private DataServiceApp requireApp(Long id) { return appRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("调用应用不存在: " + id)); }
    private String identifier(String value) { String result=value.trim(); if(!result.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException("Catalog 或 Database 名称格式不正确"); return result; }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String trimError(String value) { if(value==null)return null; return value.length()>2000?value.substring(0,2000):value; }
    private Integer number(Object value) { return value instanceof Number number ? number.intValue() : null; }
    private record RateWindow(long minute, AtomicInteger count) {}
}
