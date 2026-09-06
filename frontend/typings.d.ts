/// <reference types="@umijs/max" />

declare module 'slash-create';
declare module '*.css';
declare module '*.less';
declare module '*.scss';
declare module '*.sass';
declare module '*.svg';
declare module '*.png';
declare module '*.jpg';
declare module '*.jpeg';
declare module '*.gif';
declare module '*.bmp';
declare module '*.tiff';
declare module '*.wav';
declare module '*.mp3';
declare module '*.mp4';

declare namespace API {
  interface ApiResponse<T> {
    code: number;
    message: string;
    data: T;
  }

  interface PageResult<T> {
    content: T[];
    totalElements: number;
    totalPages: number;
    currentPage: number;
    pageSize: number;
  }

  interface CurrentUser {
    id: number;
    username: string;
    realName?: string;
    email?: string;
    roles: string[];
    permissions: string[];
  }

  interface SyncTask {
    id: number;
    taskName: string;
    description?: string;
    taskType: 'cdc_sync' | 'etl' | 'materialized';
    scenarioCode?: string;
    executionMode: 'continuous' | 'scheduled';
    definitionStatus: 'draft' | 'published' | 'disabled';
    publishedVersionId?: number;
    sourceConfigId?: number;
    targetConfigId?: number;
    flinkSql: string;
    syncStrategy: 'full_then_incremental' | 'incremental_only';
    tableMappings?: string;
    status: 'draft' | 'submitting' | 'running' | 'saving_point' | 'paused' | 'failed' | 'finished';
    flinkJobId?: string;
    flinkJarId?: string;
    savepointTriggerId?: string;
    checkpointInfo?: string;
    currentLagMs?: number;
    throughputQps?: number;
    lastErrorMsg?: string;
    creatorId: number;
    parallelism?: number;
    checkpointIntervalMs?: number;
    submittedAt?: string;
    lastCheckpointTime?: string;
    checkpointCount?: number;
    createdAt: string;
    updatedAt: string;
  }

  interface DatasourceConfig {
    id: number;
    configName: string;
    dbType: 'mysql' | 'postgresql' | 'paimon';
    host: string;
    port: number;
    database: string;
    username: string;
    status: string;
    createdAt: string;
  }

  interface DwhTableMeta {
    id: number;
    paimonDb: string;
    paimonTable: string;
    layer: 'ods' | 'dwd' | 'dws' | 'ads';
    businessDesc?: string;
    schemaJson?: string;
    partitionKeys?: string;
    primaryKeys?: string;
    snapshotCount?: number;
    latestSnapshotId?: number;
    fileCount?: number;
    totalSizeBytes?: number;
  }

  interface DwhColumnMeta {
    id: number;
    tableMetaId: number;
    columnName: string;
    columnType: string;
    businessComment?: string;
    isPk: boolean;
    isNullable: boolean;
    sourceColumn?: string;
    sortOrder: number;
  }

  interface ReportTemplate {
    id: number;
    reportName: string;
    reportType: 'line' | 'bar' | 'pie' | 'table' | 'mixed';
    sqlQuery: string;
    chartConfig?: string;
    filterConfig?: string;
    scheduleConfig?: string;
    isPublished: boolean;
    creatorId: number;
    createdAt: string;
  }

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

  interface TaskStatusInfo {
    taskStatus: string;
    taskId: number;
    taskName: string;
    flinkJobId?: string;
    flinkJobStatus?: string;
    currentLagMs?: number;
    throughputQps?: number;
    checkpointInfo?: any;
    lastErrorMsg?: string;
    checkpointCount?: number;
    submittedAt?: string;
    lastCheckpointTime?: string;
    savepointProgress?: string;
    savepointTriggerId?: string;
  }

  interface AlertRule {
    id: number;
    ruleName: string;
    ruleType: string;
    expression?: string;
    notifyChannel?: string;
    enabled: boolean;
    createdAt: string;
    updatedAt: string;
  }

  interface AlertRecord {
    id: number;
    ruleType: string;
    message?: string;
    level?: string;
    resolved: boolean;
    resolvedAt?: string;
    triggeredAt?: string;
  }

  interface QualityRule {
    id: number;
    tableName: string;
    paimonDb: string;
    layer: string;
    ruleName: string;
    ruleType: 'null_rate' | 'uniqueness' | 'row_count' | 'value_range' | 'freshness';
    column?: string;
    threshold: number;
    current: number;
    unit: string;
    status: 'pass' | 'warning' | 'fail';
    lastCheckedAt: string;
  }

  interface QualityAlert {
    id: number;
    tableName: string;
    ruleName: string;
    alertLevel: 'low' | 'medium' | 'high';
    message: string;
    detectedAt: string;
    resolved: boolean;
    resolvedAt?: string;
    resolver?: string;
  }

  // MaintenanceLog is defined with the persisted recovery contract in src/typings.d.ts.
}
