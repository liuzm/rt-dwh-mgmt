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
    token?: string;
  }

  /** 同步任务 */
  interface SyncTask {
    id: number;
    name: string;
    description: string;
    taskType: string; // CDC | SQL | BATCH | cdc_sync | etl | materialized
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
    status: string;
    flinkJobId?: string;
    savepointTriggerId?: string;
    checkpointCount: number;
    lastCheckpointTime?: string;
    errorMessage?: string;
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
    name: string;
    reportName?: string;
    description: string;
    sql: string;
    sqlQuery?: string;
    chartType: string;
    reportType?: string;
    config: string;
    createdBy: string;
    isPublished?: boolean;
    createdAt: string;
    updatedAt: string;
  }

  /** 告警规则 */
  interface AlertRule {
    id: number;
    ruleName: string;
    ruleType: string;
    expression?: string;
    enabled: boolean;
    notifyChannel?: string;
    createdAt: string;
    updatedAt: string;
  }

  /** 告警记录 */
  interface AlertRecord {
    id: number;
    ruleType: string;
    message?: string;
    level?: string;
    resolved: boolean;
    resolvedAt?: string;
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
    warehousePath?: string;
    metastoreUri?: string;
    databaseCount?: number;
    database?: string;
    dbProduct?: string;
    dbVersion?: string;
    driver?: string;
    readOnly?: boolean;
    versionMatch?: boolean;
    expectedVersion?: string;
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
    lastCheckedComponent?: 'flink' | 'paimon' | 'mysql';
    flink: HealthComponent;
    paimon: HealthComponent;
    mysql: HealthComponent;
  }

  /** 数据质量规则 */
  interface QualityRule {
    id: number;
    ruleName: string;
    layer: string;
    ruleType: string; // null_rate | uniqueness | volume_compare | range_check
    targetTable: string;
    targetColumn?: string;
    expression: string;
    threshold: number;
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
  }

  /** 数据质量告警 */
  interface QualityAlert {
    id: number;
    ruleId: number;
    ruleType: string;
    targetTable: string;
    targetColumn?: string;
    level: string;
    message: string;
    actualValue: number;
    thresholdValue: number;
    triggeredAt: string;
    resolved: boolean;
    resolvedAt?: string;
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
    tableId?: number;
    tableMetaId?: number;
    tableName?: string;
    operation: string; // compact | expire | clean
    triggerType: string; // manual | auto
    status: string;
    detail: string;
    operator: string;
    createdAt: string;
    // Additional fields from backend entity
    operationType?: string;
    triggerTypeStr?: string;
    strategy?: string;
    retainLast?: number;
    startedAt?: string;
    finishedAt?: string;
    durationMs?: number;
    errorMsg?: string;
    sqlContent?: string;
    paimonDb?: string;
    database?: string;
  }
}
