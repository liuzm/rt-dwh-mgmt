import { request } from '@umijs/max';

const API_PREFIX = '/api/v1';

/**
 * Normalize list endpoints across Umi's possible return shapes:
 * business array, ApiResponse, or Axios-style response wrapper.
 */
function normalizeListResponse<T>(response: unknown, resourceName: string): T[] {
  let candidate: unknown = response;
  for (let level = 0; level < 3; level += 1) {
    if (Array.isArray(candidate)) {
      return candidate as T[];
    }
    if (candidate && typeof candidate === 'object') {
      const responseObject = candidate as { code?: number; message?: string; data?: unknown };
      if (responseObject.code != null && responseObject.code !== 0) {
        throw new Error(responseObject.message || `${resourceName}读取失败`);
      }
      if (!('data' in responseObject)) break;
      candidate = responseObject.data;
      continue;
    }
    break;
  }
  throw new Error(`${resourceName}接口返回格式异常`);
}

// Auth
export async function login(data: { username: string; password: string }) {
  return request<API.CurrentUser & { token: string }>(
    `${API_PREFIX}/auth/login`,
    { method: 'POST', data },
  );
}

export async function getCurrentUser() {
  return request<API.CurrentUser>(
    `${API_PREFIX}/auth/current-user`,
  );
}

// Sync Tasks
export async function getSyncTasks(params?: { status?: string; taskType?: string; keyword?: string }) {
  const response = await request<unknown>(`${API_PREFIX}/sync-tasks`, { params });
  return normalizeListResponse<API.SyncTask>(response, '任务列表');
}

export async function getSyncTask(id: number) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}`);
}

export async function createSyncTask(data: any) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks`, { method: 'POST', data });
}

export async function startSyncTask(id: number) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}/start`, { method: 'POST' });
}

export async function pauseSyncTask(id: number) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}/pause`, { method: 'POST' });
}

export async function resumeSyncTask(id: number) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}/resume`, { method: 'POST' });
}

export async function stopSyncTask(id: number) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}/stop`, { method: 'POST' });
}

export async function deleteSyncTask(id: number) {
  return request<void>(`${API_PREFIX}/sync-tasks/${id}`, { method: 'DELETE' });
}

export async function retrySyncTask(id: number) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}/retry`, { method: 'POST' });
}

export async function triggerSavepoint(id: number) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}/savepoint`, { method: 'POST' });
}

export async function updateSyncTask(id: number, data: any) {
  return request<API.SyncTask>(`${API_PREFIX}/sync-tasks/${id}`, { method: 'PUT', data });
}

export async function getSyncTaskStatus(id: number) {
  return request<API.TaskStatusInfo>(`${API_PREFIX}/sync-tasks/${id}/status`);
}

export async function getSyncTaskScaling(id: number) {
  return request<API.FlinkJobScalingInfo>(`${API_PREFIX}/sync-tasks/${id}/scaling`);
}

export async function scaleSyncTask(id: number, data: API.FlinkJobScaleRequest) {
  return request<API.FlinkJobScalingInfo | API.FlinkJobScaleAccepted>(`${API_PREFIX}/sync-tasks/${id}/scale`, {
    method: 'POST',
    data,
  });
}

export async function getPostgresCdcStatus(id: number) {
  return request<API.PostgresCdcStatus>(`${API_PREFIX}/sync-tasks/${id}/postgres-cdc-status`);
}

export async function cleanupPostgresCdc(id: number) {
  return request<{ removedSlots: string[]; removedPublications: string[] }>(
    `${API_PREFIX}/sync-tasks/${id}/cleanup-postgres-cdc`, { method: 'POST' },
  );
}

export async function getSyncTaskLogs(id: number, params?: { type?: string; lines?: number }) {
  return request<{ logs: string; type: string; lines: number }>(`${API_PREFIX}/sync-tasks/${id}/logs`, { params });
}

export async function syncAllTaskStatus() {
  return request<number>(`${API_PREFIX}/sync-tasks/sync-status`, { method: 'POST' });
}

// Workflow orchestration
export async function getWorkflowGraph() {
  return request<API.WorkflowGraph>(`${API_PREFIX}/workflow/graph`);
}

export async function addTaskDependency(data: { upstreamTaskId: number; downstreamTaskId: number; conditionType?: string; outputDatasetId?: number }) {
  return request<API.TaskDependency>(`${API_PREFIX}/workflow/dependencies`, { method: 'POST', data });
}

export async function removeTaskDependency(upstreamTaskId: number, downstreamTaskId: number) {
  return request<void>(`${API_PREFIX}/workflow/dependencies`, {
    method: 'DELETE', params: { upstreamTaskId, downstreamTaskId },
  });
}

export async function publishTaskVersion(taskId: number, changeSummary: string) {
  return request<API.TaskDefinitionVersion>(`${API_PREFIX}/workflow/tasks/${taskId}/publish`, {
    method: 'POST', data: { changeSummary },
  });
}

export async function getTaskVersions(taskId: number) {
  return request<API.TaskDefinitionVersion[]>(`${API_PREFIX}/workflow/tasks/${taskId}/versions`);
}

export async function rollbackTaskVersion(taskId: number, versionNo: number) {
  return request<API.SyncTask>(`${API_PREFIX}/workflow/tasks/${taskId}/rollback/${versionNo}`, { method: 'POST' });
}

export async function createTaskBackfill(taskId: number, data: {
  startDate: string; endDate: string; parametersJson?: string; bindingPolicy?: string; taskParametersJson?: Record<number, string>;
}) {
  return request<API.TaskRunInstance[]>(`${API_PREFIX}/workflow/tasks/${taskId}/backfill`, { method: 'POST', data });
}

export async function getWorkflowInstances(params?: { taskId?: number; status?: string; limit?: number }) {
  return request<API.TaskRunInstance[]>(`${API_PREFIX}/workflow/instances`, { params });
}

export async function retryWorkflowInstance(instanceId: number) {
  return request<API.TaskRunInstance>(`${API_PREFIX}/workflow/instances/${instanceId}/retry`, { method: 'POST' });
}

export async function cancelWorkflowInstance(instanceId: number) {
  return request<API.TaskRunInstance>(`${API_PREFIX}/workflow/instances/${instanceId}/cancel`, { method: 'POST' });
}

export async function getTaskSchedules() { return request<API.TaskSchedule[]>(`${API_PREFIX}/workflow/schedules`); }
export async function configureTaskSchedule(taskId: number, data: any) { return request<API.TaskSchedule>(`${API_PREFIX}/workflow/tasks/${taskId}/schedule`, { method: 'PUT', data }); }
export async function deleteTaskSchedule(taskId: number) { return request<void>(`${API_PREFIX}/workflow/tasks/${taskId}/schedule`, { method: 'DELETE' }); }
export async function getTaskOutputs(taskId: number) { return request<API.TaskOutputDataset[]>(`${API_PREFIX}/workflow/tasks/${taskId}/outputs`); }
export async function configureTaskOutputs(taskId: number, data: any[]) { return request<API.TaskOutputDataset[]>(`${API_PREFIX}/workflow/tasks/${taskId}/outputs`, { method: 'PUT', data }); }
export async function getDatasetProductions(outputId: number) { return request<API.DatasetProduction[]>(`${API_PREFIX}/workflow/outputs/${outputId}/productions`); }

// External data services
export async function getDataServices() { return request<API.DataServiceDefinition[]>(`${API_PREFIX}/data-services`); }
export async function createDataService(data: any) { return request<API.DataServiceDefinition>(`${API_PREFIX}/data-services`, { method: 'POST', data }); }
export async function updateDataService(id: number, data: any) { return request<API.DataServiceDefinition>(`${API_PREFIX}/data-services/${id}`, { method: 'PUT', data }); }
export async function getDataService(id: number) { return request<API.DataServiceDefinition>(`${API_PREFIX}/data-services/${id}`); }
export async function getDataServiceVersions(id: number) { return request<API.DataServiceVersion[]>(`${API_PREFIX}/data-services/${id}/versions`); }
export async function getDataServicePublished(id: number) { return request<API.DataServiceVersion>(`${API_PREFIX}/data-services/${id}/published`); }
export async function previewDataService(id: number, expectedRevision: number) { return request<API.DataServicePublicationPreview>(`${API_PREFIX}/data-services/${id}/preview`, { method: 'POST', data: { expectedRevision } }); }
export async function publishDataService(id: number, published: boolean, expectedRevision: number, changeSummary?: string) { return request<API.DataServiceDefinition>(`${API_PREFIX}/data-services/${id}/publish`, { method: 'POST', params: { published }, data: { expectedRevision, changeSummary } }); }
export async function rollbackDataService(id: number, versionId: number, expectedRevision: number, changeSummary?: string) { return request<API.DataServiceDefinition>(`${API_PREFIX}/data-services/${id}/rollback/${versionId}`, { method: 'POST', data: { expectedRevision, changeSummary } }); }
export async function deleteDataService(id: number) { return request<void>(`${API_PREFIX}/data-services/${id}`, { method: 'DELETE' }); }
export async function getDataServiceApps() { return request<API.DataServiceApp[]>(`${API_PREFIX}/data-services/apps`); }
export async function createDataServiceApp(data: any) { return request<API.DataServiceCredential>(`${API_PREFIX}/data-services/apps`, { method: 'POST', data }); }
export async function rotateDataServiceSecret(id: number) { return request<API.DataServiceCredential>(`${API_PREFIX}/data-services/apps/${id}/rotate-secret`, { method: 'POST' }); }
export async function toggleDataServiceApp(id: number) { return request<API.DataServiceApp>(`${API_PREFIX}/data-services/apps/${id}/toggle`, { method: 'POST' }); }
export async function getDataServiceGrants(appId: number) { return request<API.DataServiceGrant[]>(`${API_PREFIX}/data-services/apps/${appId}/grants`); }
export async function grantDataService(appId: number, serviceId: number) { return request<API.DataServiceGrant>(`${API_PREFIX}/data-services/apps/${appId}/grants`, { method: 'POST', data: { serviceId } }); }
export async function revokeDataService(appId: number, serviceId: number) { return request<void>(`${API_PREFIX}/data-services/apps/${appId}/grants/${serviceId}`, { method: 'DELETE' }); }
export async function getDataServiceLogs(limit = 200) { return request<API.DataServiceInvocationLog[]>(`${API_PREFIX}/data-services/logs`, { params: { limit } }); }

// Cross-cutting foundation capabilities
export async function getFoundationSummary() { return request<API.FoundationSummary>(`${API_PREFIX}/foundation/summary`); }
export async function searchFoundation(keyword: string, limit = 30) { return request<API.FoundationSearchItem[]>(`${API_PREFIX}/foundation/search`, { params: { keyword, limit } }); }
export async function getFoundationSlaRisks() { return request<API.FoundationSlaRisk[]>(`${API_PREFIX}/foundation/sla-risks`); }

export async function getOperationAudits(params?: {
  username?: string; keyword?: string; resourceType?: string; success?: boolean;
  from?: string; to?: string; page?: number; size?: number;
}) {
  return request<API.PageResult<API.OperationAudit>>(`${API_PREFIX}/audit`, { params });
}

// User and role administration
export async function getAdminUsers() {
  return request<API.AdminUser[]>(`${API_PREFIX}/admin/users`);
}

export async function createAdminUser(data: any) {
  return request<API.AdminUser>(`${API_PREFIX}/admin/users`, { method: 'POST', data });
}

export async function updateAdminUser(id: number, data: any) {
  return request<API.AdminUser>(`${API_PREFIX}/admin/users/${id}`, { method: 'PUT', data });
}

export async function toggleAdminUserStatus(id: number) {
  return request<API.AdminUser>(`${API_PREFIX}/admin/users/${id}/toggle-status`, { method: 'POST' });
}

export async function resetAdminUserPassword(id: number, password: string) {
  return request<void>(`${API_PREFIX}/admin/users/${id}/reset-password`, { method: 'POST', data: { password } });
}

export async function getAdminRoles() {
  return request<API.AdminRole[]>(`${API_PREFIX}/admin/roles`);
}

export async function createAdminRole(data: any) {
  return request<API.AdminRole>(`${API_PREFIX}/admin/roles`, { method: 'POST', data });
}

export async function updateAdminRole(id: number, data: any) {
  return request<API.AdminRole>(`${API_PREFIX}/admin/roles/${id}`, { method: 'PUT', data });
}

export async function deleteAdminRole(id: number) {
  return request<void>(`${API_PREFIX}/admin/roles/${id}`, { method: 'DELETE' });
}

export async function getAdminPermissions() {
  return request<API.AdminPermission[]>(`${API_PREFIX}/admin/permissions`);
}

// Datasources
export async function getDatasources(params?: { dbType?: string }) {
  const response = await request<unknown>(`${API_PREFIX}/datasources`, { params });
  return normalizeListResponse<API.DatasourceConfig>(response, '数据源');
}

export async function createDatasource(data: any) {
  return request<API.DatasourceConfig>(`${API_PREFIX}/datasources`, { method: 'POST', data });
}

export async function updateDatasource(id: number, data: any) {
  return request<API.DatasourceConfig>(`${API_PREFIX}/datasources/${id}`, { method: 'PUT', data });
}

export async function deleteDatasource(id: number) {
  return request<void>(`${API_PREFIX}/datasources/${id}`, { method: 'DELETE' });
}

export async function testDatasourceConnection(id: number) {
  return request<{ success: boolean; message: string; dbVersion: string }>(
    `${API_PREFIX}/datasources/${id}/test-connection`,
  );
}

export async function getIntrospectTables(datasourceId: number) {
  return request<string[]>(`${API_PREFIX}/datasources/${datasourceId}/tables`);
}

export async function getIntrospectTable(datasourceId: number, tableName: string) {
  return request<any>(`${API_PREFIX}/datasources/${datasourceId}/tables/${tableName}`);
}

// Sync Task preview
export async function previewCdcSql(data: any) {
  return request<{ sql: string }>(`${API_PREFIX}/sync-tasks/preview-cdc-sql`, { method: 'POST', data });
}

// DWH Tables
export async function getDwhTables(params?: { layer?: string; database?: string; keyword?: string }) {
  return request<API.DwhTableMeta[]>(`${API_PREFIX}/dwh/tables`, { params });
}

export async function getDwhTableDetail(id: number) {
  return request<API.DwhTableMeta>(`${API_PREFIX}/dwh/tables/${id}`);
}

export async function getDataAsset(assetId: string) {
  return request<API.DwhTableMeta>(`${API_PREFIX}/dwh/assets/${assetId}`);
}
export async function getAssetContext(assetId: string) {
  return request<API.AssetContext>(`${API_PREFIX}/dwh/assets/${assetId}/context`);
}
export async function getAssetSchemaRevisions(assetId: string) {
  return request<API.AssetSchemaRevision[]>(`${API_PREFIX}/dwh/assets/${assetId}/schema-revisions`);
}

export async function getDwhTableColumns(id: number) {
  return request<API.DwhColumnMeta[]>(`${API_PREFIX}/dwh/tables/${id}/columns`);
}

export async function getDwhTableSnapshots(id: number) {
  return request<API.DwhSnapshot[]>(`${API_PREFIX}/dwh/tables/${id}/snapshots`);
}

export async function updateDwhColumnComment(id: number, comment: string) {
  return request<API.DwhColumnMeta>(`${API_PREFIX}/dwh/columns/${id}/comment`, {
    method: 'PUT',
    data: { comment },
  });
}

export async function syncMetadataFromPaimon() {
  return request<number>(`${API_PREFIX}/dwh/sync-metadata`, { method: 'POST' });
}

export async function updateTableMetadata(id: number, data: {
  businessDesc?: string; owner?: string; businessDomain?: string; tags?: string[];
  sensitivityLevel?: string; lifecycleStatus?: string;
}) {
  return request<API.DwhTableMeta>(
    `${API_PREFIX}/dwh/tables/${id}/metadata`,
    { method: 'PUT', data },
  );
}

export async function updateTableBusinessDesc(id: number, businessDesc: string) {
  return updateTableMetadata(id, { businessDesc });
}

export async function getQueryGovernanceStats() {
  return request<API.QueryGovernanceStats>(`${API_PREFIX}/query/governance/stats`);
}

export async function triggerCompact(id: number, compactStrategy?: string) {
  return request(`${API_PREFIX}/dwh/tables/${id}/compact`, {
    method: 'POST',
    params: { compactStrategy },
  });
}

export async function triggerExpireSnapshots(id: number, retainLast?: number) {
  return request(`${API_PREFIX}/dwh/tables/${id}/expire-snapshots`, {
    method: 'POST',
    params: { retainLast },
  });
}

// Query
export async function executeQuery(data: { sql: string; maxRows?: number; timeoutSeconds?: number; requestId?: string; catalog?: string; database?: string }) {
  return request<API.QueryResult>(`${API_PREFIX}/query/execute`, { method: 'POST', data });
}

export async function exportQuery(data: { sql: string; maxRows?: number; timeoutSeconds?: number; catalog?: string; database?: string }) {
  return request<Blob>(`${API_PREFIX}/query/export`, { method: 'POST', data, responseType: 'blob' });
}

export async function cancelQuery(historyId: number) {
  return request<void>(`${API_PREFIX}/query/cancel/${historyId}`, { method: 'POST' });
}

export async function cancelQueryByRequestId(requestId: string) {
  return request<void>(`${API_PREFIX}/query/cancel-request/${requestId}`, { method: 'POST' });
}

export async function getQueryHistory(params?: { page?: number; size?: number }) {
  return request<API.PageResult<any>>(`${API_PREFIX}/query/history`, { params });
}

export async function getQueryProfile(historyId: number) {
  return request<{ queryId: string; profile: string }>(`${API_PREFIX}/query/history/${historyId}/profile`);
}

export async function getQueryCatalog() {
  return request<API.QueryCatalog>(`${API_PREFIX}/query/catalog`);
}

export async function getSavedQueries() {
  return request<API.SavedQuery[]>(`${API_PREFIX}/query/saved`);
}

export async function createSavedQuery(data: API.SavedQueryPayload) {
  return request<API.SavedQuery>(`${API_PREFIX}/query/saved`, { method: 'POST', data });
}

export async function updateSavedQuery(id: number, data: API.SavedQueryPayload) {
  return request<API.SavedQuery>(`${API_PREFIX}/query/saved/${id}`, { method: 'PUT', data });
}

export async function deleteSavedQuery(id: number) {
  return request<void>(`${API_PREFIX}/query/saved/${id}`, { method: 'DELETE' });
}

// Reports
export async function getReports() {
  return request<API.ReportTemplate[]>(`${API_PREFIX}/reports`);
}

export async function getReportData(id: number, parameters?: Record<string, unknown>) {
  return request<API.QueryResult>(`${API_PREFIX}/reports/${id}/data`, {
    method: 'POST',
    data: parameters || {},
  });
}

export async function createReport(data: any) {
  return request<API.ReportTemplate>(`${API_PREFIX}/reports`, { method: 'POST', data });
}

export async function updateReport(id: number, data: API.ReportTemplate) {
  return request<API.ReportTemplate>(`${API_PREFIX}/reports/${id}`, { method: 'PUT', data });
}

export async function deleteReport(id: number) {
  return request<void>(`${API_PREFIX}/reports/${id}`, { method: 'DELETE' });
}

export async function runReportNow(id: number, parameters?: Record<string, unknown>) {
  return request<API.ReportRun>(`${API_PREFIX}/reports/${id}/run`, {
    method: 'POST',
    data: parameters || {},
  });
}

export async function getReportRuns(id: number, limit = 50) {
  return request<API.ReportRun[]>(`${API_PREFIX}/reports/${id}/runs`, { params: { limit } });
}

export async function getReportRunResult(reportId: number, runId: number) {
  return request<{ run: API.ReportRun; result: API.QueryResult }>(`${API_PREFIX}/reports/${reportId}/runs/${runId}`);
}

// Settings
export async function getHealthStatus() {
  return request<API.SystemHealth>(`${API_PREFIX}/settings/health-status`);
}

export async function healthCheck() {
  return request<API.SystemHealth>(`${API_PREFIX}/settings/health-status/refresh`, { method: 'POST' });
}

export async function getFlinkClusterConfig() {
  return request<API.FlinkClusterConfig>(`${API_PREFIX}/settings/flink-cluster`);
}

export async function getFlinkClusterCapacity() {
  return request<API.FlinkClusterCapacity>(`${API_PREFIX}/settings/flink-cluster/capacity`);
}

export async function updateFlinkClusterConfig(data: API.FlinkClusterConfig) {
  return request<API.FlinkClusterConfig>(`${API_PREFIX}/settings/flink-cluster`, { method: 'PUT', data });
}

export async function testFlinkClusterConfig(data: API.FlinkClusterConfig) {
  return request<API.HealthComponent>(`${API_PREFIX}/settings/flink-cluster/test`, { method: 'POST', data });
}

export async function getDorisConfig() {
  return request<API.DorisConfig>(`${API_PREFIX}/settings/doris`);
}

export async function updateDorisConfig(data: API.DorisConfig) {
  return request<API.DorisConfig>(`${API_PREFIX}/settings/doris`, { method: 'PUT', data });
}

export async function testDorisConfig(data: API.DorisConfig) {
  return request<API.HealthComponent>(`${API_PREFIX}/settings/doris/test`, { method: 'POST', data });
}

export async function healthCheckComponent(component: 'flink' | 'paimon' | 'mysql' | 'doris') {
  return request<API.SystemHealth>(`${API_PREFIX}/settings/health-status/${component}/refresh`, { method: 'POST' });
}

// Alerts
export async function getAlertRules() {
  return request<API.AlertRule[]>(`${API_PREFIX}/alert/rules`);
}

export async function createAlertRule(data: any) {
  return request<API.AlertRule>(`${API_PREFIX}/alert/rules`, { method: 'POST', data });
}

export async function updateAlertRule(id: number, data: any) {
  return request<API.AlertRule>(`${API_PREFIX}/alert/rules/${id}`, { method: 'PUT', data });
}

export async function deleteAlertRule(id: number) {
  return request<void>(`${API_PREFIX}/alert/rules/${id}`, { method: 'DELETE' });
}

export async function toggleAlertRule(id: number) {
  return request<void>(`${API_PREFIX}/alert/rules/${id}/toggle`, { method: 'POST' });
}

export async function getAlertRecords(params?: { level?: string; resolved?: boolean }) {
  return request<API.AlertRecord[]>(`${API_PREFIX}/alert/records`, { params });
}

export async function resolveAlertRecord(id: number) {
  return request<void>(`${API_PREFIX}/alert/records/${id}/resolve`, { method: 'POST' });
}

export async function evaluateAlertRules() {
  return request<{ evaluated: number; triggered: number; recovered: number }>(`${API_PREFIX}/alert/evaluate`, { method: 'POST' });
}

// Quality
export async function getQualityRules(params?: { layer?: string; ruleType?: string }) {
  return request<API.QualityRule[]>(`${API_PREFIX}/quality/rules`, { params });
}

export async function getQualityOverview(params?: { businessDate?: string }) {
  return request<API.QualityOverviewSummary>(`${API_PREFIX}/quality/overview`, { params });
}

export async function createQualityRule(data: API.QualityRuleInput) {
  return request<API.QualityRule>(`${API_PREFIX}/quality/rules`, { method: 'POST', data });
}

export async function updateQualityRule(id: number, data: API.QualityRuleInput) {
  return request<API.QualityRule>(`${API_PREFIX}/quality/rules/${id}`, { method: 'PUT', data });
}

export async function toggleQualityRule(id: number, enabled: boolean) {
  return request<API.QualityRule>(`${API_PREFIX}/quality/rules/${id}/toggle`, {
    method: 'POST',
    data: { enabled },
  });
}

export async function deleteQualityRule(id: number) {
  return request<void>(`${API_PREFIX}/quality/rules/${id}`, { method: 'DELETE' });
}

export async function runQualityCheck(ruleId?: number, businessDate?: string) {
  const path = ruleId == null ? '/quality/run-check/all' : `/quality/rules/${ruleId}/run`;
  return request<API.QualityCheckSummary>(`${API_PREFIX}${path}`, { method: 'POST', params: { businessDate } });
}

export async function getQualityAlerts(params?: { level?: string; resolved?: boolean }) {
  return request<API.QualityAlert[]>(`${API_PREFIX}/quality/alerts`, { params });
}

export async function getQualityRuns(params?: { ruleId?: number }) {
  return request<API.QualityCheckRun[]>(`${API_PREFIX}/quality/runs`, { params });
}

export async function resolveQualityAlert(id: number) {
  return request<API.QualityAlert>(`${API_PREFIX}/quality/alerts/${id}/resolve`, { method: 'POST' });
}

// Lineage
export async function getLineageGraph(params?: { layer?: string; keyword?: string }) {
  return request<API.LineageGraph>(`${API_PREFIX}/lineage/graph`, { params });
}

// Maintenance
export async function getMaintenanceLogs(params?: { tableMetaId?: number; operation?: string; status?: string }) {
  return request<API.MaintenanceLog[]>(`${API_PREFIX}/dwh/maintenance/logs`, { params });
}
export const getMaintenanceRecovery = (id: number) => request<API.MaintenanceRecoveryDetail>(`${API_PREFIX}/dwh/maintenance/${id}`);
export const recoverMaintenance = (id: number, data: { expectedRevision: number; action: string; reason: string; jobId?: string }) =>
  request<API.MaintenanceRecoveryDetail>(`${API_PREFIX}/dwh/maintenance/${id}/recovery`, { method: 'POST', data });

export async function batchCompact(data: { layer?: string; fileCountThreshold?: number }) {
  return request<{ triggered: number; failed: number; failures: { tableId: number; table: string; message: string }[] }>(`${API_PREFIX}/dwh/maintenance/batch-compact`, { method: 'POST', data });
}

export async function batchExpireSnapshots(data: { layer?: string; retainLast?: number }) {
  return request<{ triggered: number; failed: number; failures: { tableId: number; table: string; message: string }[] }>(`${API_PREFIX}/dwh/maintenance/batch-expire`, { method: 'POST', data });
}

export async function cleanOrphanFiles(tableId?: number) {
  return request(`${API_PREFIX}/dwh/maintenance/clean-orphan`, { method: 'POST', data: { tableId } });
}

export async function publishContinuousTask(id: number, changeSummary: string) {
  return request<API.TaskDefinitionVersion>(`${API_PREFIX}/sync-tasks/${id}/publish`, { method: 'POST', data: { changeSummary } });
}
export async function getContinuousVersions(id: number) {
  return request<API.TaskDefinitionVersion[]>(`${API_PREFIX}/sync-tasks/${id}/versions`);
}
export async function getTaskDeployments(id: number) {
  return request<API.TaskDeploymentRevision[]>(`${API_PREFIX}/sync-tasks/${id}/deployments`);
}

export async function configureTaskParameters(taskId: number, parameterSchemaJson: string) {
  return request<API.SyncTask>(`${API_PREFIX}/workflow/tasks/${taskId}/parameters`, { method: 'PUT', data: { parameterSchemaJson } });
}
export async function getTaskScheduleRevisions(taskId: number) {
  return request<API.TaskScheduleRevision[]>(`${API_PREFIX}/workflow/tasks/${taskId}/schedule/revisions`);
}
export async function getTaskAccessChecks(taskId: number) {
  return request<API.TaskAccessCheck[]>(`${API_PREFIX}/workflow/tasks/${taskId}/access-checks`);
}

export async function getWorkflowAttempts(instanceId: number) { return request<API.TaskRunAttempt[]>(`${API_PREFIX}/workflow/instances/${instanceId}/attempts`); }
export async function getWorkflowBindings(instanceId: number) { return request<API.TaskRunBinding[]>(`${API_PREFIX}/workflow/instances/${instanceId}/bindings`); }
export async function recheckWorkflowDelivery(instanceId: number) { return request<API.TaskRunInstance>(`${API_PREFIX}/workflow/instances/${instanceId}/recheck-delivery`, { method: 'POST' }); }
export async function getProductionChecks(productionId: number) { return request<API.ProductionCheck[]>(`${API_PREFIX}/workflow/productions/${productionId}/checks`); }

// Managed ordinary Doris Views: publication uses the dedicated DDL channel.
export const createManagedView = (data: {name:string;sql:string;description?:string}) => request<any>(`${API_PREFIX}/dwh/views`, {method:'POST',data});
export const getManagedView = (assetId:string) => request<any>(`${API_PREFIX}/dwh/views/${assetId}`);
export const saveManagedView = (assetId:string,data:{sql:string;description?:string;expectedVersion:number}) => request<any>(`${API_PREFIX}/dwh/views/${assetId}`, {method:'PUT',data});
export const previewManagedView = (assetId:string) => request<any>(`${API_PREFIX}/dwh/views/${assetId}/preview`, {method:'POST'});
export const publishManagedView = (assetId:string,expectedVersion:number) => request<any>(`${API_PREFIX}/dwh/views/${assetId}/publish`, {method:'POST',data:{expectedVersion}});
export const checkManagedView = (assetId:string) => request<any>(`${API_PREFIX}/dwh/views/${assetId}/health`);

export async function previewQualityRule(data: API.QualityRuleInput, businessDate?: string) {
  return request<API.QualityPreview>(`${API_PREFIX}/quality/preview`, { method: 'POST', data, params: { businessDate } });
}
