import React, { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Form, Input, message, Select, Space, Spin, Table, Tag, Typography } from 'antd';
import { getMaintenanceRecovery, recoverMaintenance } from '@/api';
import { formatBackendDateTime as time } from '@/utils/backendDateTime';

export const maintenanceStatus: Record<string, string> = { success: '成功', failed: '失败', running: '协调中', unknown: '结果待确认', timed_out: '超时，继续协调', pending: '尚未提交' };
export const cleanupStatus: Record<string, string> = { held: '等待执行结果', pending: '待清理，自动重试', done: '已清理', unresolved: '缺少会话句柄', untracked: '历史未记录', not_required: '未创建会话，无需清理' };
const phaseNames: Record<string, string> = { REQUESTED: '请求已记录', SESSION: '创建会话', SESSION_READY: '会话已绑定', CATALOG: '初始化 Catalog', USE: '切换 Catalog', CALL: '执行维护', JOB: '核对 Flink Job' };
const actions = { observe: '重验并续查', retry_cleanup: '重试会话清理', attach_job: '校验并关联 Job', cancel_preparation: '取消未提交的准备', cancel_pending: '取消尚未提交的请求', note: '补充处置记录' };
const eventNames: Record<string, string> = { requested: '记录请求', session_bound: '绑定会话', submit_intent: '记录提交意图', operation_bound: '绑定操作', job_bound: '绑定 Job', engine_observed: '观测引擎', progress: '更新进展', uncertain: '结果待确认', terminal_observed: '确认执行结果', cleanup_intent: '发起会话清理', session_cleaned: '会话清理完成', cleanup_deferred: '会话清理待重试', manual_note: '补充处置记录', manual_observe: '人工续查', manual_cleanup: '人工重试清理', manual_job_bound: '人工核验 Job', preparation_cancelled: '取消准备阶段', pending_cancelled: '取消未提交请求' };
const RecoveryDrawer: React.FC<{ id?: number; canManage: boolean; onClose: () => void; onChanged: () => void }> = ({ id, canManage, onClose, onChanged }) => {
  const [detail, setDetail] = useState<API.MaintenanceRecoveryDetail>();
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [form] = Form.useForm();
  const action = Form.useWatch('action', form);
  const operation = detail?.operation;
  const terminal = operation && ['success', 'failed'].includes(operation.status);
  const bound = operation?.contractOrigin === 'bound_v1';
  const options = Object.entries(actions).filter(([key]) => key === 'note'
    || key === 'cancel_pending' && operation?.status === 'pending' && !operation.sessionId && !operation.operationId && !operation.flinkJobId
    || bound && (key === 'observe'
    || key === 'retry_cleanup' && terminal && operation?.cleanupStatus === 'pending'
    || key === 'attach_job' && !terminal && operation?.executionPhase === 'CALL' && !operation.flinkJobId
    || key === 'cancel_preparation' && !terminal && ['REQUESTED', 'SESSION', 'SESSION_READY', 'CATALOG', 'USE'].includes(operation?.executionPhase || '')))
    .map(([value, label]) => ({ value, label }));
  useEffect(() => {
    let active = true;
    setDetail(undefined); setError(undefined); form.resetFields();
    if (id !== undefined) {
      setLoading(true);
      getMaintenanceRecovery(id).then(value => { if (active) setDetail(value); })
        .catch(() => { if (active) setError('无法读取维护证据，请检查权限和连接'); })
        .finally(() => { if (active) setLoading(false); });
    }
    return () => { active = false; };
  }, [id]);
  const reload = async () => {
    if (id === undefined) return;
    setLoading(true); setError(undefined);
    try { setDetail(await getMaintenanceRecovery(id)); } catch (e: any) { setError(e?.message || '刷新失败'); }
    finally { setLoading(false); }
  };
  return <Drawer rootClassName="rtdwh-evidence-surface" title={`维护恢复${id ? ` · #${id}` : ''}`} width={Math.min(920, window.innerWidth)} open={id !== undefined} onClose={busy ? undefined : onClose} destroyOnClose>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
    <Button disabled={busy || loading} onClick={reload} style={{ marginBottom: 12 }}>刷新证据</Button>
    <Spin spinning={loading || busy}>
      {operation && <>
        <Alert showIcon type={bound ? 'info' : 'warning'} message={bound ? '按原提交环境协调，未知操作不会自动重发' : '历史记录缺少绑定环境，不自动推断执行结果；尚未提交的请求可取消'}
          description="执行结果与会话清理分别记录。关联 Job 必须匹配原提交标识；业务 CALL 可能已提交时不能取消准备或清空状态。" style={{ marginBottom: 16 }} />
        <Descriptions column={1} size="small" bordered items={[
          { key: 'target', label: '原目标', children: bound ? `${operation.catalogName}.${operation.databaseName}.${operation.tableName}` : '历史目标未认证' },
          { key: 'status', label: '执行状态', children: <Space><Tag>{maintenanceStatus[operation.status] || operation.status}</Tag>{phaseNames[operation.executionPhase || ''] || operation.executionPhase}</Space> },
          { key: 'observer', label: '最近观测', children: `${operation.observedState || '尚无证据'} · ${operation.observedState ? time(operation.observedAt) : '—'}` },
          { key: 'gateway', label: '提交时 Gateway', children: operation.gatewayUrl || '未记录' },
          { key: 'flink', label: '提交时 Flink', children: operation.flinkUrl || '未记录' },
          { key: 'session', label: 'Session / Operation', children: `${operation.sessionId || '未取得'} / ${operation.operationId || '未取得'}` },
          { key: 'job', label: 'Job / 关联标识', children: <Typography.Text style={{ overflowWrap: 'anywhere' }}>{operation.flinkJobId || '未取得'} / {operation.correlationName || '未记录'}</Typography.Text> },
          { key: 'cleanup', label: '会话清理', children: `${cleanupStatus[operation.cleanupStatus] || operation.cleanupStatus} · 已尝试 ${operation.cleanupAttempts} 次 · 下次 ${time(operation.cleanupNextAt)}` },
          { key: 'actor', label: '发起人 ID', children: operation.requestedBy ?? '未记录' },
        ]} />
        {operation.errorMsg && <Alert type="warning" showIcon message={operation.errorMsg} style={{ marginTop: 12 }} />}
        {operation.cleanupError && <Alert type="warning" showIcon message={operation.cleanupError} style={{ marginTop: 12 }} />}
        <Typography.Paragraph style={{ marginTop: 16 }}>原维护 SQL</Typography.Paragraph>
        <pre className="rtdwh-code-panel" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{operation.sqlContent || '未记录'}</pre>
        {canManage && <Form form={form} layout="vertical" initialValues={{ action: 'note' }} style={{ marginTop: 16 }} onFinish={async values => {
          if (id === undefined) return;
          setBusy(true); setError(undefined);
          try {
            setDetail(await recoverMaintenance(id, { ...values, jobId: values.action === 'attach_job' ? values.jobId : undefined, expectedRevision: operation.revision }));
            message.success('处置已记录，请查看最新执行和清理状态'); form.resetFields(); onChanged();
          } catch (e: any) { setError(e?.data?.message || e?.message || '恢复操作失败，请刷新证据后重试'); }
          finally { setBusy(false); }
        }}>
          <Form.Item name="action" label="处置动作" rules={[{ required: true }]}><Select options={options} /></Form.Item>
          {action === 'attach_job' && <Form.Item name="jobId" label="待核验的 Job ID" rules={[{ required: true }, { pattern: /^[a-fA-F0-9]{32}$/, message: '请输入 32 位 Job ID' }]}><Input /></Form.Item>}
          <Form.Item name="reason" label="处置原因与证据说明" rules={[{ required: true, whitespace: true }]}><Input.TextArea rows={3} maxLength={1000} showCount /></Form.Item>
          <Button type="primary" htmlType="submit" loading={busy}>执行处置并记录</Button>
        </Form>}
        <Typography.Title level={5}>恢复记录（最近 200 条）</Typography.Title>
        <Table size="small" rowKey="id" dataSource={detail?.events} scroll={{ x: 700 }} columns={[
          { title: '时间', dataIndex: 'createdAt', width: 175, render: time },
          { title: '动作', dataIndex: 'action', width: 150, render: value => eventNames[value] || value },
          { title: '操作人', dataIndex: 'actorId', width: 90, render: value => value ?? '协调器' },
          { title: '说明', dataIndex: 'reason' },
        ]} expandable={{ expandedRowRender: value => <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(JSON.parse(value.evidenceJson), null, 2)}</pre> }} />
      </>}
    </Spin>
  </Drawer>;
};
export default RecoveryDrawer;
