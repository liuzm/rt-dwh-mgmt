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

export async function getSyncTaskLogs(id: number, params?: { type?: string; lines?: number }) {
  return request<{ logs: string; type: string; lines: number }>(`${API_PREFIX}/sync-tasks/${id}/logs`, { params });
}

export async function syncAllTaskStatus() {
  return request<number>(`${API_PREFIX}/sync-tasks/sync-status`, { method: 'POST' });
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

export async function updateTableBusinessDesc(id: number, businessDesc: string) {
  return request<API.DwhTableMeta>(
    `${API_PREFIX}/dwh/tables/${id}/metadata`,
    { method: 'PUT', data: { businessDesc } },
  );
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
export async function executeQuery(data: { sql: string; maxRows?: number; timeoutSeconds?: number; requestId?: string }) {
  return request<API.QueryResult>(`${API_PREFIX}/query/execute`, { method: 'POST', data });
}

export async function exportQuery(data: { sql: string; maxRows?: number; timeoutSeconds?: number }) {
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

export async function getReportData(id: number) {
  return request<API.QueryResult>(`${API_PREFIX}/reports/${id}/data`);
}

export async function createReport(data: any) {
  return request<API.ReportTemplate>(`${API_PREFIX}/reports`, { method: 'POST', data });
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

export async function updateFlinkClusterConfig(data: API.FlinkClusterConfig) {
  return request<API.FlinkClusterConfig>(`${API_PREFIX}/settings/flink-cluster`, { method: 'PUT', data });
}

export async function testFlinkClusterConfig(data: API.FlinkClusterConfig) {
  return request<API.HealthComponent>(`${API_PREFIX}/settings/flink-cluster/test`, { method: 'POST', data });
}

export async function healthCheckComponent(component: 'flink' | 'paimon' | 'mysql') {
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

// Quality
export async function getQualityRules(params?: { layer?: string; ruleType?: string }) {
  return request<API.QualityRule[]>(`${API_PREFIX}/quality/rules`, { params });
}

export async function createQualityRule(data: any) {
  return request<API.QualityRule>(`${API_PREFIX}/quality/rules`, { method: 'POST', data });
}

export async function updateQualityRule(id: number, data: any) {
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

export async function runQualityCheck(ruleId?: number) {
  return request<number>(`${API_PREFIX}/quality/run-check`, { method: 'POST', data: { ruleId } });
}

export async function getQualityAlerts(params?: { level?: string; resolved?: boolean }) {
  return request<API.QualityAlert[]>(`${API_PREFIX}/quality/alerts`, { params });
}

export async function resolveQualityAlert(id: number) {
  return request<API.QualityAlert>(`${API_PREFIX}/quality/alerts/${id}/resolve`, { method: 'POST' });
}

// Maintenance
export async function getMaintenanceLogs(params?: { tableMetaId?: number; operation?: string; status?: string }) {
  return request<API.MaintenanceLog[]>(`${API_PREFIX}/dwh/maintenance/logs`, { params });
}

export async function batchCompact(data: { layer?: string; fileCountThreshold?: number }) {
  return request<{ triggered: number }>(`${API_PREFIX}/dwh/maintenance/batch-compact`, { method: 'POST', data });
}

export async function batchExpireSnapshots(data: { layer?: string; retainLast?: number }) {
  return request<{ triggered: number }>(`${API_PREFIX}/dwh/maintenance/batch-expire`, { method: 'POST', data });
}

export async function cleanOrphanFiles(tableId?: number) {
  return request(`${API_PREFIX}/dwh/maintenance/clean-orphan`, { method: 'POST', data: { tableId } });
}
