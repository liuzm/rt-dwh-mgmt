import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Table, Tag, Button, Space, Select, Input, Modal, message, Popconfirm, Tooltip, Badge } from 'antd';
import { PlusOutlined, SearchOutlined, SyncOutlined, ExclamationCircleOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { history } from '@umijs/max';
import {
  getSyncTasks, startSyncTask, pauseSyncTask, resumeSyncTask,
  stopSyncTask, deleteSyncTask, retrySyncTask, syncAllTaskStatus,
} from '@/api';

const statusConfig: Record<string, { color: string; label: string; badge: string }> = {
  draft: { color: 'default', label: 'Draft', badge: 'default' },
  submitting: { color: 'processing', label: '提交中', badge: 'processing' },
  running: { color: 'blue', label: 'Running', badge: 'processing' },
  saving_point: { color: 'warning', label: '保存中', badge: 'warning' },
  paused: { color: 'orange', label: 'Paused', badge: 'warning' },
  failed: { color: 'red', label: 'Failed', badge: 'error' },
  finished: { color: 'green', label: 'Finished', badge: 'success' },
};

const taskTypeMap: Record<string, string> = {
  cdc_sync: 'CDC同步',
  etl: 'ETL',
  materialized: '物化表',
};

const formatBackendDateTime = (value: unknown) => {
  if (!value) return '—';
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value.map(Number);
    const date = new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1_000_000));
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false });
  }
  const date = new Date(value as string | number | Date);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false });
};

const SyncTaskList: React.FC = () => {
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [typeFilter, setTypeFilter] = useState<string | undefined>();
  const [keyword, setKeyword] = useState<string>('');
  const [actionLoading, setActionLoading] = useState<Record<number, string>>({});

  const { data, loading, refresh } = useRequest(() =>
    getSyncTasks({ status: statusFilter, taskType: typeFilter, keyword }),
    {
      refreshDeps: [statusFilter, typeFilter, keyword],
    },
  );

  const tasks = (data || []) as API.SyncTask[];

  const handleAction = async (action: string, id: number) => {
    setActionLoading((prev) => ({ ...prev, [id]: action }));
    try {
      let res;
      switch (action) {
        case 'start':
          res = await startSyncTask(id);
          message.success(res?.status === 'running' ? '任务已启动' : '任务正在提交中...');
          break;
        case 'pause':
          res = await pauseSyncTask(id);
          message.success(res?.status === 'saving_point' ? '正在创建 Savepoint，请等待...' : '任务已暂停');
          break;
        case 'resume':
          res = await resumeSyncTask(id);
          message.success(res?.status === 'running' ? '任务已从 Savepoint 恢复' : '恢复操作进行中');
          break;
        case 'stop':
          res = await stopSyncTask(id);
          message.success('任务已停止（未保留 Savepoint）');
          break;
        case 'retry':
          res = await retrySyncTask(id);
          message.success(res?.status === 'running' ? '任务重试成功' : '重试操作进行中');
          break;
      }
      refresh();
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    } finally {
      setActionLoading((prev) => ({ ...prev, [id]: '' }));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteSyncTask(id);
      message.success('任务已删除');
      refresh();
    } catch (e: any) {
      message.error(e?.message || '删除失败');
    }
  };

  const handleSyncAll = async () => {
    try {
      const count = await syncAllTaskStatus();
      message.success(`状态同步完成，同步 ${count || 0} 个任务`);
      refresh();
    } catch (e) {
      message.error('同步失败');
    }
  };

  const getActionButtons = (task: API.SyncTask) => {
    const { status, id } = task;
    const isLoading = actionLoading[id];
    const btn = (action: string, label: string, type?: 'primary' | 'default' | 'dashed' | 'link', danger?: boolean) => (
      <Button
        size="small"
        type={type || 'default'}
        danger={danger}
        loading={isLoading === action}
        onClick={() => handleAction(action, id)}
      >
        {label}
      </Button>
    );

    switch (status) {
      case 'running':
        return [
          <Tooltip title="创建 Savepoint 后暂停，可从断点恢复"><span>{btn('pause', '暂停', 'primary', true)}</span></Tooltip>,
          <Tooltip title="立即停止，不保留 Savepoint"><span>{btn('stop', '停止', undefined, true)}</span></Tooltip>,
        ];
      case 'saving_point':
        return [
          <Badge status="warning" text="正在保存 Savepoint..." />,
          <Button size="small" danger onClick={() => handleAction('stop', id)}>强制停止</Button>,
        ];
      case 'submitting':
        return [
          <Badge status="processing" text="正在提交到 Flink..." />,
        ];
      case 'paused':
        return [
          <Tooltip title="从 Savepoint 恢复运行"><span>{btn('resume', '恢复', 'primary')}</span></Tooltip>,
          <Button size="small" danger onClick={() => handleAction('stop', id)}>停止</Button>,
        ];
      case 'failed':
        return [
          <Tooltip title="重新提交任务到 Flink"><span>{btn('retry', '重试', 'primary')}</span></Tooltip>,
          <Button size="small" onClick={() => handleAction('stop', id)}>标记终止</Button>,
        ];
      case 'draft':
        return [
          <Tooltip title="提交任务到 Flink 集群"><span>{btn('start', '启动', 'primary')}</span></Tooltip>,
          <Popconfirm title="确认删除此任务？" onConfirm={() => handleDelete(id)}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>,
        ];
      case 'finished':
        return [
          <Popconfirm title="确认删除此任务？" onConfirm={() => handleDelete(id)}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>,
        ];
      default:
        return [];
    }
  };

  return (
    <PageContainer>
      <Card>
        <Space style={{ marginBottom: 16 }} wrap>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => history.push('/sync-task/create')}
          >
            创建任务
          </Button>
          <Input
            placeholder="搜索任务名称"
            prefix={<SearchOutlined />}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            style={{ width: 200 }}
            allowClear
          />
          <Select
            placeholder="全部状态"
            allowClear
            value={statusFilter}
            onChange={setStatusFilter}
            style={{ width: 140 }}
            options={Object.entries(statusConfig).map(([k, v]) => ({ label: v.label, value: k }))}
          />
          <Select
            placeholder="全部类型"
            allowClear
            value={typeFilter}
            onChange={setTypeFilter}
            style={{ width: 140 }}
            options={[
              { label: 'CDC同步', value: 'cdc_sync' },
              { label: 'ETL', value: 'etl' },
              { label: '物化表', value: 'materialized' },
            ]}
          />
          <Tooltip title="从 Flink 集群同步所有任务状态">
            <Button icon={<SyncOutlined />} onClick={handleSyncAll}>同步状态</Button>
          </Tooltip>
        </Space>

        <Table<API.SyncTask>
          dataSource={tasks}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 20, showSizeChanger: true }}
          columns={[
            { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
            { title: '任务名称', dataIndex: 'taskName', key: 'taskName', ellipsis: true },
            {
              title: '类型',
              dataIndex: 'taskType',
              key: 'taskType',
              render: (v) => <Tag>{taskTypeMap[v] || v}</Tag>,
              width: 100,
            },
            {
              title: '状态',
              dataIndex: 'status',
              key: 'status',
              render: (v) => {
                const cfg = statusConfig[v] || { color: 'default', label: v };
                return <Tag color={cfg.color}>{cfg.label}</Tag>;
              },
              width: 110,
            },
            {
              title: 'Flink Job ID',
              dataIndex: 'flinkJobId',
              key: 'flinkJobId',
              render: (v) => v ? <Tooltip title={v}><Tag color="geekblue">{v.substring(0, 16)}...</Tag></Tooltip> : '—',
              width: 130,
            },
            {
              title: '延迟(ms)',
              dataIndex: 'currentLagMs',
              key: 'lag',
              render: (v) => v != null ? <span style={{ color: v > 5000 ? '#ff4d4f' : '#52c41a' }}>{v}</span> : '—',
              width: 90,
            },
            {
              title: '吞吐(QPS)',
              dataIndex: 'throughputQps',
              key: 'qps',
              render: (v) => v ?? '—',
              width: 90,
            },
            {
              title: 'CP数',
              dataIndex: 'checkpointCount',
              key: 'cp',
              render: (v) => v ?? '—',
              width: 70,
            },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              key: 'createdAt',
              render: formatBackendDateTime,
              width: 150,
            },
            {
              title: '操作',
              key: 'action',
              width: 220,
              fixed: 'right' as const,
              render: (_, record) => (
                <Space size={4} wrap>
                  {getActionButtons(record)}
                  <Button size="small" type="link" onClick={() => history.push(`/sync-task/detail/${record.id}`)}>详情</Button>
                </Space>
              ),
            },
          ]}
          scroll={{ x: 1200 }}
        />
      </Card>
    </PageContainer>
  );
};

export default SyncTaskList;
