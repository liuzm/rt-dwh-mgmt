/**
 * API 类型定义
 */
declare namespace API {
  /** 统一响应结构 */
  interface ApiResponse<T> {
    code: number;
    message: string;
    data: T;
  }

  interface PageResult<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    number: number;
    size: number;
  }

  /** 当前用户（与后端 LoginResponse 对齐） */
  interface CurrentUser {
    id: number;
    username: string;
    realName: string;
    email: string;
    role: string;  // 逗号分隔的角色字符串，如 "ADMIN" 或 "ADMIN,DEVELOPER"
    permissions: string[];
    token?: string;
  }

  /** 同步任务 */
  interface SyncTask {
    parameterSchemaJson?: string;
    id: number;
    name: string;
    description: string;
    taskType: string; // CDC | SQL | BATCH | cdc_sync | etl | materialized
    scenarioCode?: string; // product scenario, e.g. table_realtime_sync
    executionMode: 'continuous' | 'scheduled';
    definitionStatus: 'draft' | 'published' | 'disabled';
    publishedVersionId?: number;
    status: string; // draft | submitting | running | paused | failed | cancelled | finished | saving_point
    sourceDatasourceId: number;
    sourceDatasourceName?: string;
    sourceDatabase: string;
    sourceTable: string;
    targetDatabase: string;
    targetTable: string;
    flinkJarId?: number;
    parallelism: number;
    checkpointIntervalMs: number;
    savepointPath: string;
    savepointTriggerId?: string;
    flinkJobId?: string;
    config: string;
    remark: string;
    checkpointCount: number;
    submittedAt?: string;
    lastCheckpointTime?: string;
    createdAt: string | number[];
    updatedAt: string | number[];
    // Additional fields from backend entity
    taskName?: string;
    sourceConfigId?: number;
    targetConfigId?: number;
    flinkSql?: string;
    syncStrategy?: string;
    tableMappings?: string;
    creatorId?: number;
    checkpointInfo?: any;
    currentLagMs?: number;
    throughputQps?: number;
    lastErrorMsg?: string;
  }

  /** 任务状态详情 */
  interface TaskStatusInfo {
    taskId: number;
    definitionVersionId: number;
    status: string;
    flinkJobId?: string;
    savepointTriggerId?: string;
    checkpointCount: number;
    lastCheckpointTime?: string;
    errorMessage?: string;
    sourceDbType?: string;
  }

  interface PostgresCdcStatus {
    ready: boolean;
    walLevel: string;
    replicationRole: boolean;
    canCreatePublication: boolean;
    maxReplicationSlots: number;
    usedReplicationSlots: number;
    requiredNewSlots: number;
    error?: string;
    resources: Array<{ sourceTable: string; slot: string; publication: string }>;
  }

  /** Flink 作业动态并行度调整请求 */
  interface FlinkJobScaleRequest {
    targetParallelism: number;
    expectedJobId: string;
    expectedConfiguredParallelism: number;
    reason: string;
  }

  /** Flink 作业顶点的当前资源需求 */
  interface FlinkJobScalingVertex {
    vertexId?: string;
    id?: string;
    name: string;
    currentParallelism: number;
    lowerBound?: number;
    upperBound?: number;
    requestedLowerBound?: number;
    requestedUpperBound?: number;
    minParallelism?: number;
    maxParallelism?: number;
  }

  interface FlinkJobScalingCapacity {
    currentTaskManagers: number;
    slotsTotal: number;
    slotsAvailable: number;
    slotsUsed?: number;
    slotUtilization?: number;
  }

  /** Flink 集群实时容量，只用于观测集群资源，不代表平台可直接扩缩 TaskManager。 */
  interface FlinkClusterCapacity {
    status: string;
    provider: string;
    autoExpansionSupported: boolean;
    jobRescalingSupported: boolean;
    adaptiveScheduler: boolean;
    currentTaskManagers: number;
    slotsTotal: number;
    slotsAvailable: number;
    slotsUsed: number;
    slotUtilization: number;
    runningJobs: number;
    reason?: string;
    observedAt: string;
  }

  /** 运行中 Flink 作业的动态并行度能力与当前状态 */
  interface FlinkJobScalingInfo {
    supported: boolean;
    reason?: string;
    jobId: string;
    flinkState: string;
    configuredParallelism: number;
    currentParallelism?: number;
    requestedLowerBound?: number;
    requestedUpperBound?: number;
    minTargetParallelism: number;
    maxTargetParallelism: number;
    provider: string;
    autoExpansionSupported: boolean;
    vertices: FlinkJobScalingVertex[];
    capacity: FlinkJobScalingCapacity;
    acceptedAt?: string;
    observedAt?: string;
    jobType?: string;
    adaptiveScheduler?: boolean;
  }

  interface FlinkJobScaleAccepted {
    accepted?: boolean;
    jobId: string;
    targetParallelism: number;
    affectedVertices?: number;
    provider?: string;
    autoExpansionSupported?: boolean;
    acceptedAt: string;
    message?: string;
    configuredParallelism?: number;
    reason?: string;
    requestedBy?: string;
  }

  interface TaskDependency {
    outputDatasetId?: number;
    id: number;
    upstreamTaskId: number;
    downstreamTaskId: number;
    conditionType: string;
    createdAt: string | number[];
  }

  interface TaskDeploymentRevision {
    id: number;
    taskId: number;
    definitionVersionId: number;
    requestedBy: number;
    actionType: string;
    status: string;
    flinkJobId?: string;
    contractHash?: string;
    restorePath?: string;
    desiredParallelism?: number;
    errorMessage?: string;
  }

  interface TaskDefinitionVersion {
    contractJson?: string;
    contractProvenance?: 'legacy-inferred' | 'frozen-v1';
    contractHash?: string;
    id: number;
    taskId: number;
    versionNo: number;
    changeSummary: string;
    createdBy: number;
    createdAt: string | number[];
  }

  interface TaskRunInstance {
    activeAttemptId?: number;
    attemptCount?: number;
    windowStart?: string;
    windowEnd?: string;
    bindingPolicy?: string;
    deliveryStatus?: string;
    deliveryError?: string;
    scheduleRevisionId?: number;
    scheduledAt?: string;
    accessCheckedAt?: string;
    id: number;
    taskId: number;
    batchId: string;
    businessDate: string;
    triggerType: string;
    status: 'waiting' | 'queued' | 'running' | 'success' | 'failed' | 'cancelled';
    executorId?: string;
    externalJobId?: string;
    retryCount: number;
    errorMessage?: string;
    startedAt?: string | number[];
    finishedAt?: string | number[];
    heartbeatAt?: string | number[];
    leaseExpiresAt?: string | number[];
    nextRetryAt?: string | number[];
    createdAt: string | number[];
  }

  interface WorkflowGraph {
    tasks: SyncTask[];
    dependencies: TaskDependency[];
  }

  interface TaskRunAttempt { id: number; instanceId: number; attemptNo: number; executorId: string; externalJobId?: string; status: string; errorMessage?: string; startedAt: string; submittedAt?: string; finishedAt?: string; }
  interface TaskRunBinding { id: number; dependencyId: number; upstreamTaskId: number; upstreamVersionId: number; upstreamInstanceId?: number; outputDatasetId?: number; productionId?: number; conditionType: string; bindingPolicy: string; windowStart: string; windowEnd: string; boundAt?: string; }
  interface ProductionCheck { id: number; productionId: number; qualityBatchId?: string; status: string; reason: string; checkedAt: string; }
  interface TaskScheduleRevision { id: number; taskId: number; revisionNo: number; cronExpression: string; timezone: string; businessDateOffset: number; parametersJson?: string; enabled: boolean; action: string; createdBy: number; createdAt: string; }
  interface TaskAccessCheck { id: number; definitionVersionId?: number; instanceId?: number; actorId?: number; action: string; allowed: boolean; reason: string; checkedAt: string; }
  interface TaskSchedule { activeRevisionId?: number; lastError?: string; id: number; taskId: number; cronExpression: string; timezone: string; businessDateOffset: number; parametersJson?: string; enabled: boolean; nextRunAt?: string; lastRunAt?: string; }
  interface TaskOutputDataset { id: number; taskId: number; catalogName: string; databaseName: string; tableName: string; layer: string; owner?: string; businessDesc?: string; slaMinutes: number; qualityGateEnabled: boolean; lastProducedAt?: string; lastInstanceId?: number; }
  interface DatasetProduction { windowStart?: string; windowEnd?: string; definitionVersionId?: number; attemptId?: number; qualityBatchId?: string; reason?: string; checkedAt?: string; id: number; outputDatasetId: number; taskId: number; instanceId: number; businessDate: string; status: string; producedAt: string; }

  interface DataServiceDefinition { id: number; serviceCode: string; serviceName: string; description?: string; creatorId: number; sqlTemplate: string; parameterConfig?: string; catalogName: string; databaseName: string; maxRows: number; timeoutSeconds: number; rateLimitPerMinute: number; status: 'draft'|'published'|'offline'; apiVersion: number; publishedVersionId?: number; revision: number; hasDraftChanges?: boolean; manageable?: boolean; publishedAt?: string; updatedAt: string; }
  interface DataServiceColumn { name: string; type: string; precision: number; scale: number; nullable: boolean; }
  interface DataServiceVersion { id: number; serviceId: number; versionNo: number; serviceCode: string; serviceName: string; description?: string; creatorId: number; sqlTemplate: string; parameterConfig?: string; catalogName: string; databaseName: string; maxRows: number; timeoutSeconds: number; rateLimitPerMinute: number; resultColumnsJson?: string; dependenciesJson?: string; sourceRevision: number; origin: 'publish'|'rollback'|'legacy_capture'; sourceVersionId?: number; publishedBy?: number; changeSummary?: string; createdAt: string; }
  interface DataServicePublicationPreview { revision: number; currentVersionId?: number; publishable: boolean; changes: string[]; breakingChanges: string[]; resultColumns: DataServiceColumn[]; dependencies: { catalog: string; database: string; table: string }[]; compatibilityBasis: string; }
  interface DataServiceApp { id: number; appName: string; appKey: string; enabled: boolean; expiresAt?: string; createdAt: string; }
  interface DataServiceCredential { id: number; appName: string; appKey: string; appSecret: string; enabled: boolean; expiresAt?: string; }
  interface DataServiceGrant { id: number; appId: number; serviceId: number; createdAt: string; }
  interface DataServiceInvocationLog { id: number; serviceId?: number; appId?: number; versionId?: number; apiVersion?: number; executionUserId?: number; serviceCode: string; status: string; httpStatus: number; rowCount?: number; durationMs?: number; clientIp?: string; errorMessage?: string; createdAt: string; }

  interface FoundationCapability { key: 'asset'|'security'|'quality'|'observability'|'audit'; name: string; description: string; status: 'healthy'|'attention'|'risk'; score: number; riskCount: number; metrics: Record<string, number>; path: string; }
  interface FoundationSlaRisk { outputId: number; taskId: number; qualifiedName: string; layer: string; owner?: string; slaMinutes: number; lastProducedAt?: string; overdueMinutes: number; severity: 'warning'|'high'|'critical'; }
  interface FoundationSummary { capabilities: FoundationCapability[]; slaRisks: FoundationSlaRisk[]; overallScore: number; generatedAt: string; }
  interface FoundationSearchItem { type: 'table'|'task'|'report'|'data_service'; id: number; title: string; subtitle: string; status: string; path: string; }

  interface OperationAudit {
    id: number;
    username: string;
    httpMethod: string;
    requestPath: string;
    action: string;
    resourceType: string;
    resourceId?: string;
    clientIp?: string;
    success: boolean;
    responseStatus: number;
    durationMs: number;
    errorMessage?: string;
    createdAt: string | number[];
  }

  interface AdminPermission {
    id: number;
    permCode: string;
    permName: string;
    resourceType: string;
  }

  interface AdminRoleSummary {
    id: number;
    roleCode: string;
    roleName: string;
  }

  interface AdminRole extends AdminRoleSummary {
    description?: string;
    permissions: AdminPermission[];
    dataScopes: Array<{
      id?: number;
      catalogPattern: string;
      databasePattern: string;
      tablePattern: string;
    }>;
  }

  interface AdminUser {
    id: number;
    username: string;
    realName?: string;
    email?: string;
    phone?: string;
    status: 'active' | 'disabled';
    roles: AdminRoleSummary[];
    createdAt: string;
    updatedAt: string;
  }

  /** 数据源配置（与后端 DatasourceConfig 实体对齐） */
  interface DatasourceConfig {
    id: number;
    creatorId: number;
    configName: string;
    dbType: string; // mysql | postgresql | paimon
    host: string;
    port: number;
    database: string;
    username: string;
    passwordEncrypted: string;
    extraParams: string;
    createdAt: string;
    updatedAt: string;
  }

  /** 数仓表元数据 */
  interface DwhTableMeta {
    assetId: string;
    catalogName?: string;
    assetType?: string;
    discoveryStatus?: string;
    schemaStatus?: string;
    lastSeenAt?: string | number[];
    schemaObservedAt?: string | number[];
    id: number;
    paimonDb: string;
    paimonTable: string;
    database?: string;
    tableName?: string;
    layer: string; // ods | dwd | dws | ads
    tableType: string;
    storageFormat: string;
    recordCount?: number;
    totalSizeBytes?: number;
    totalSize?: number;
    fileCount: number;
    lastModifiedTime: string;
    businessDesc: string;
    owner: string;
    businessDomain?: string;
    tags?: string;
    sensitivityLevel?: 'public' | 'internal' | 'confidential' | 'restricted';
    lifecycleStatus?: 'active' | 'deprecated' | 'offline';
    createdAt: string;
    updatedAt: string;
    // Additional fields from backend entity
    partitionKeys?: string;
    primaryKeys?: string;
    snapshotCount?: number;
    latestSnapshotId?: number;
    latestCommitTime?: string | number[];
    schemaJson?: string;
  }

  /** 数仓表列元数据 */
  interface DwhColumnMeta {
    engineFieldId?: number;
    id: number;
    tableId: number;
    tableMetaId?: number;
    columnName: string;
    columnType: string;
    nullable: boolean;
    isNullable?: boolean;
    comment: string;
    businessComment?: string;
    isPartitionKey: boolean;
    isPk?: boolean;
    ordinalPosition: number;
    sortOrder?: number;
    sourceColumn?: string;
    defaultValue?: string;
  }

  interface DwhSnapshot {
    snapshotId: number;
    schemaId: number;
    commitKind: string;
    commitTime?: string | number[];
    recordCount: number;
    deltaRecordCount: number;
    manifestSizeBytes: number;
  }

  /** 查询结果 */
  interface QueryResult {
    columns: string[];
    rows: any[][];
    totalRows?: number;
    rowCount?: number;
    executionTime?: number;
    durationMs?: number;
    status?: 'running' | 'success' | 'failed' | 'cancelled' | string;
    errorMsg?: string;
    historyId?: number;
    requestId?: string;
    truncated?: boolean;
    engine?: 'doris' | string;
    catalog?: string;
    database?: string;
    traceId?: string;
    queryId?: string;
    scannedRows?: number;
    scannedBytes?: number;
    cpuMs?: number;
    peakMemoryBytes?: number;
    localScanBytes?: number;
    remoteScanBytes?: number;
    cacheWriteBytes?: number;
    queueWaitMs?: number;
    costScore?: number;
    budgetExceeded?: boolean;
    budgetReason?: string;
  }

  interface QueryGovernanceStats {
    sampleSize: number;
    successCount: number;
    failedCount: number;
    successRate: number;
    p95DurationMs: number;
    runningCount: number;
    queuedCount: number;
    averageQueueWaitMs: number;
    concurrencyLimit: number;
    budgetExceededCount: number;
    budget: { scannedBytes: number; cpuMs: number; peakMemoryBytes: number };
    slowQueries: any[];
    costlyQueries: any[];
  }

  interface QueryCatalog {
    catalogName: string;
    catalogKey: string;
    databases: Array<{
      name: string;
      tables: Array<{
        name: string;
        layer: string;
        columns: Array<{ name: string; type: string; primaryKey: boolean; nullable: boolean }>;
      }>;
    }>;
  }

  interface SavedQuery {
    id: number;
    name: string;
    sqlText: string;
    description?: string;
    tags?: string;
    createdAt: string | number[];
    updatedAt: string | number[];
  }

  interface SavedQueryPayload {
    name: string;
    sqlText: string;
    description?: string;
    tags?: string;
  }

  /** 报表模板 */
  interface ReportTemplate {
    id: number;
    creatorId: number;
    reportName: string;
    reportType: 'line' | 'bar' | 'pie' | 'table' | 'mixed';
    sqlQuery: string;
    chartConfig?: string;
    filterConfig?: string;
    scheduleConfig?: string;
    scheduleEnabled?: boolean;
    nextRunAt?: string;
    lastRunAt?: string;
    isPublished: boolean;
    createdAt: string;
    updatedAt: string;
  }

  interface ReportParameterDefinition {
    name: string;
    label?: string;
    type: 'string' | 'number' | 'boolean' | 'date' | 'datetime' | 'stringList';
    required?: boolean;
    defaultValue?: unknown;
    placeholder?: string;
  }

  interface ReportRun {
    id: number;
    reportId: number;
    triggerType: 'manual' | 'scheduled';
    status: 'running' | 'success' | 'failed';
    scheduledAt?: string;
    startedAt: string;
    finishedAt?: string;
    durationMs?: number;
    rowCount?: number;
    attemptCount?: number;
    errorMessage?: string;
    deliveryStatus?: 'skipped' | 'success' | 'partial' | 'failed';
    deliveryError?: string;
    executedBy: number;
  }

  /** 告警规则 */
  interface AlertRule {
    id: number;
    ruleName: string;
    ruleType: string;
    expression?: string;
    enabled: boolean;
    version?: number;
    notifyChannel?: string;
    createdAt: string;
    updatedAt: string;
  }

  /** 告警记录 */
  interface AlertRecord {
    id: number;
    ruleId?: number;
    dedupKey?: string;
    ruleType: string;
    message?: string;
    level?: string;
    resolved: boolean;
    resolvedAt?: string;
    resolutionReason?: 'recovered' | 'acknowledged' | 'suppressed' | string;
    recoveredAt?: string;
    lastEvaluatedAt?: string;
    notificationStatus?: 'pending' | 'sending' | 'sent' | 'partial' | 'skipped' | string;
    recoveryNotificationStatus?: 'pending' | 'sending' | 'sent' | 'partial' | 'skipped' | string;
    triggeredAt?: string;
  }

  /** Flink 集群运行配置 */
  interface FlinkClusterConfig {
    restApiUrl: string;
    submissionMode: 'application' | 'session';
    savepointDir: string;
    sqlGatewayEnabled: boolean;
    sqlGatewayUrl: string;
    flinkVersion: string;
    source?: 'environment' | 'database';
    updatedAt?: string;
    updatedBy?: string;
    loadError?: string;
  }

  interface DorisConfig {
    enabled: boolean;
    jdbcUrl: string;
    httpUrl: string;
    username: string;
    password?: string;
    passwordConfigured?: boolean;
    catalog: string;
    database: string;
    source?: 'environment' | 'database';
    updatedAt?: string;
  }

  /** 单项依赖健康检查结果 */
  interface HealthComponent {
    status: 'healthy' | 'degraded' | 'unhealthy' | 'unreachable' | 'unknown' | string;
    checkedAt?: string;
    responseTimeMs?: number;
    error?: string;
    endpoint?: string;
    flinkVersion?: string;
    runningJobs?: number;
    finishedJobs?: number;
    failedJobs?: number;
    cancelledJobs?: number;
    taskSlotsAvailable?: number;
    taskSlotsTotal?: number;
    taskManagers?: number;
    catalogKey?: string;
    warehousePath?: string;
    metastoreUri?: string;
    metastoreProduct?: string;
    database?: string;
    dbProduct?: string;
    dbVersion?: string;
    driver?: string;
    readOnly?: boolean;
    versionMatch?: boolean;
    expectedVersion?: string;
    dorisVersion?: string;
    httpEndpoint?: string;
    catalog?: string;
    aliveBackends?: number;
    diagnosticCode?: string;
    suggestion?: string;
    contentType?: string;
  }

  /** 全量系统健康检查结果 */
  interface SystemHealth {
    overall: 'healthy' | 'degraded' | 'unhealthy' | string;
    checkedAt?: string;
    durationMs?: number;
    source?: 'scheduled' | 'manual' | 'none' | string;
    lastCheckedComponent?: 'flink' | 'paimon' | 'mysql' | 'doris';
    flink: HealthComponent;
    paimon: HealthComponent;
    mysql: HealthComponent;
    doris: HealthComponent;
  }

  /** 数据质量规则 */
  interface QualityScopeConfig {
    checkScope?: 'full_table' | 'business_window';
    timeColumn?: string;
    emptyPolicy?: 'fail' | 'allow';
  }

  interface QualityRule extends QualityScopeConfig {
    id: number;
    ruleName: string;
    layer: string;
    ruleType: string; // null_rate | uniqueness | volume_compare | range_check
    targetTable: string;
    targetColumn?: string;
    expression?: string;
    threshold: number;
    enabled: boolean;
    version?: number;
    createdAt: string;
    updatedAt: string;
  }

  interface QualityRuleInput extends QualityScopeConfig {
    ruleName: string;
    layer: string;
    ruleType: string;
    targetTable: string;
    targetColumn?: string;
    expression?: string;
    threshold: number;
    enabled: boolean;
  }

  /** 数据质量告警 */
  interface QualityEvidence {
    scopeKey?: string;
    windowStart?: string;
    windowEnd?: string;
    layer?: string;
  }

  interface QualityPreview {
    checkSql: string;
    scopeKey: string;
    window?: { start: string; end: string };
    emptyPolicy: string;
  }

  interface QualityAlert extends QualityEvidence {
    id: number;
    ruleId: number;
    ruleType: string;
    targetTable: string;
    targetColumn?: string;
    level: string;
    message: string;
    actualValue?: number;
    thresholdValue?: number;
    triggeredAt: string;
    resolved: boolean;
    resolvedAt?: string;
    resolutionReason?: 'recovered' | 'acknowledged' | 'suppressed' | string;
  }

  interface QualityCheckRun extends QualityEvidence {
    checkedRows?: number;
    violationRows?: number;
    timeColumn?: string;
    emptyPolicy?: string;
    id: number;
    batchId: string;
    ruleId: number;
    ruleName: string;
    ruleType?: string;
    targetTable?: string;
    targetColumn?: string;
    ruleVersion?: number;
    triggerType: 'manual' | 'scheduled' | 'production';
    engine: string;
    status: 'running' | 'passed' | 'failed' | 'error';
    checkSql: string;
    actualValue?: number;
    thresholdValue?: number;
    durationMs?: number;
    errorMessage?: string;
    startedAt: string;
    finishedAt?: string;
  }

  interface QualityCheckSummary {
    batchId: string;
    total: number;
    passed: number;
    failed: number;
    errorCount: number;
    abnormalCount: number;
    startedAt: string;
    finishedAt: string;
    durationMs: number;
  }

  interface QualityDailyRunSummary {
    date: string;
    total: number;
    passed: number;
    abnormal: number;
  }

  interface QualityOverviewSummary {
    latestRuns: QualityCheckRun[];
    dailyRuns: QualityDailyRunSummary[];
    last24hRuns: number;
    averageDurationMs: number;
  }

  interface LineageNode {
    id: string;
    name: string;
    qualifiedName: string;
    type: 'datasource' | 'source_table' | 'task' | 'table';
    layer?: string;
    status?: string;
    metadata: Record<string, any>;
  }

  interface LineageEdge {
    id: string;
    source: string;
    target: string;
    type: string;
    label: string;
    taskId?: number;
  }

  interface LineageGraph {
    nodes: LineageNode[];
    edges: LineageEdge[];
  }

  /** 表映射（CDC 同步任务） */
  interface TableMapping {
    sourceTable: string;
    targetDb: string; // ods | dwd | dws | ads
    targetTable: string;
    syncMode: string; // full+incremental | incremental
  }

  /** 维护日志 */
  interface MaintenanceLog {
    id: number;
    revision: number;
    contractOrigin: string;
    assetId?: string;
    catalogName?: string;
    databaseName?: string;
    requestedBy?: number;
    gatewayUrl?: string;
    flinkUrl?: string;
    correlationName?: string;
    sessionId?: string;
    operationId?: string;
    flinkJobId?: string;
    executionPhase?: string;
    observedAt?: string | number[];
    observedState?: string;
    cleanupStatus: string;
    cleanupAttempts: number;
    cleanupNextAt?: string | number[];
    cleanupError?: string;
    cleanedAt?: string | number[];
    tableId?: number;
    tableMetaId?: number;
    tableName?: string;
    operation: 'compact' | 'expire_snapshots' | 'orphan_cleanup';
    triggerType: string; // manual | auto
    status: 'running' | 'success' | 'failed' | 'pending' | 'unknown' | 'timed_out';
    detail?: string;
    operator?: string;
    createdAt?: string | number[];
    // Additional fields from backend entity
    operationType?: string;
    triggerTypeStr?: string;
    strategy?: string;
    retainLast?: number;
    startedAt?: string | number[];
    finishedAt?: string | number[];
    durationMs?: number;
    errorMsg?: string;
    sqlContent?: string;
    paimonDb?: string;
    database?: string;
  }
  interface MaintenanceRecoveryDetail {
    operation: MaintenanceLog;
    events: { id: number; actorId?: number; action: string; reason?: string; evidenceJson: string; createdAt: string | number[] }[];
  }
  interface AssetUsage {
    kind: string; id: number; name: string; relation: string; versionId?: number; versionNo?: number; evidence: string; href: string;
  }
  interface AssetContext {
    usages: AssetUsage[];
    productions: DatasetProduction[];
    relatedAssets: { assetId: string; name: string; direction: string }[];
    coverage: string;
  }
  interface AssetSchemaRevision {
    id: number; revisionNo: number; severity: string; evidenceSource: string;
    fingerprint: string; beforeSchema?: string; afterSchema: string; changesJson: string;
    observedAt: string | number[];
  }

}
