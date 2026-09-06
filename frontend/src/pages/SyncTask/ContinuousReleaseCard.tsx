import React, { useState } from 'react';
import { Alert, Button, Card, Form, Input, Modal, Space, Table, Tabs, Tag, Typography, message } from 'antd';
import { useAccess, useRequest } from '@umijs/max';
import { getContinuousVersions, getTaskDeployments, publishContinuousTask } from '@/api';

const states: Record<string, string> = {
  deploying: '部署中', submitted: '已提交，待观测', running: '运行中', stopped: '已停止', failed: '失败', unknown: '待协调',
};

export default function ContinuousReleaseCard({ taskId, onPublished }: { taskId: number; onPublished: () => void }) {
  const access = useAccess();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  const versions = useRequest(() => getContinuousVersions(taskId), { refreshDeps: [taskId] });
  const deployments = useRequest(() => getTaskDeployments(taskId), { refreshDeps: [taskId], pollingInterval: 10000 });
  return <Card title="发布与部署" style={{ marginBottom: 16 }} extra={<Space>
    <Button onClick={() => { versions.refresh(); deployments.refresh(); }}>刷新</Button>
    {access.canManageTask && <Button type="primary" onClick={() => setOpen(true)}>发布当前配置</Button>}
  </Space>}>
    <Alert type="info" showIcon message="启动使用已发布版本；首次启动会自动发布。恢复使用原部署版本与 Savepoint，新发布不会改变正在运行的作业。" style={{ marginBottom: 12 }} />
    {(deployments.data || []).some(item => item.status === 'unknown') && <Alert type="warning" showIcon
      message="存在待协调部署。请先核对 Flink 作业；提交结果不明确时不能直接重试。" style={{ marginBottom: 12 }} />}
    <Tabs items={[
      { key: 'deployments', label: '部署记录', children: <Table<API.TaskDeploymentRevision> size="small" rowKey="id"
        dataSource={deployments.data || []} loading={deployments.loading} scroll={{ x: 720 }} pagination={{ pageSize: 5 }} columns={[
          { title: '部署', dataIndex: 'id', width: 70 },
          { title: '发布版本 ID', dataIndex: 'definitionVersionId', width: 110 },
          { title: '动作', dataIndex: 'actionType', width: 70, render: v => v === 'resume' ? '恢复' : '启动' },
          { title: '状态', dataIndex: 'status', width: 140, render: v => <Tag color={v === 'running' ? 'blue' : v === 'failed' ? 'red' : 'gold'}>{states[v] || v}</Tag> },
          { title: 'Flink Job', dataIndex: 'flinkJobId', render: v => v ? <Typography.Text code copyable>{v}</Typography.Text> : '—' },
          { title: '说明', dataIndex: 'errorMessage', render: v => v || '—' },
        ]} /> },
      { key: 'versions', label: '发布版本', children: <Table<API.TaskDefinitionVersion> size="small" rowKey="id"
        dataSource={versions.data || []} loading={versions.loading} pagination={{ pageSize: 5 }} columns={[
          { title: '版本', dataIndex: 'versionNo', width: 90, render: v => <Tag>V{v}</Tag> },
          { title: '变更说明', dataIndex: 'changeSummary' },
          { title: '契约', dataIndex: 'contractProvenance', width: 140, render: v => v === 'frozen-v1' ? '已冻结' : '历史未验证' },
        ]} /> },
    ]} />
    <Modal title="发布持续任务版本" open={open} confirmLoading={saving} onCancel={() => setOpen(false)} onOk={() => form.submit()}>
      <Form form={form} layout="vertical" onFinish={async values => {
        setSaving(true);
        try { await publishContinuousTask(taskId, values.changeSummary); message.success('版本已发布'); setOpen(false); form.resetFields(); versions.refresh(); onPublished(); }
        catch (error: any) { message.error(error?.message || '发布失败'); }
        finally { setSaving(false); }
      }}><Form.Item name="changeSummary" label="变更说明" rules={[{ required: true, whitespace: true }]}><Input.TextArea rows={3} maxLength={256} showCount /></Form.Item></Form>
    </Modal>
  </Card>;
}
