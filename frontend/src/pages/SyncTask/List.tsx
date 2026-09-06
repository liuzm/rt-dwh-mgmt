import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Badge, Button, Card, Col, Empty, Input, message, Popconfirm, Row, Select,
  Space, Statistic, Table, Tag, Tooltip,
} from 'antd';
import {
  ClockCircleOutlined, DatabaseOutlined, ExclamationCircleOutlined, PauseOutlined,
  PlayCircleOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, SyncOutlined,
} from '@ant-design/icons';
import { history, useAccess, useRequest } from '@umijs/max';
import {
  deleteSyncTask, getSyncTasks, pauseSyncTask, resumeSyncTask, retrySyncTask,
  startSyncTask, stopSyncTask, syncAllTaskStatus,
} from '@/api';
import {
  availableTaskScenarios, getTaskScenarioColor, getTaskScenarioLabel,
} from './scenarios';
import './index.less';

const statusConfig: Record<string, { color: string; label: string; badge: string; hint: string }> = {
  draft: { color: 'default', label: '未启动', badge: 'default', hint: '配置已保存' },
  submitting: { color: 'processing', label: '提交中', badge: 'processing', hint: '正在提交 Flink' },
  running: { color: 'blue', label: '运行中', badge: 'processing', hint: '持续处理数据' },
  saving_point: { color: 'warning', label: '保存断点', badge: 'warning', hint: '正在创建 Savepoint' },
  paused: { color: 'orange', label: '已暂停', badge: 'warning', hint: '可从断点恢复' },
  failed: { color: 'red', label: '启动／运行失败', badge: 'error', hint: '需要处理' },
  finished: { color: 'green', label: '已终止', badge: 'success', hint: '任务不再运行' },
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
  const access = useAccess();
  const [statusFilter, setStatusFilter] = useState<string>();
  const [scenarioFilter, setScenarioFilter] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [actionLoading, setActionLoading] = useState<Record<number, string>>({});

  const { data, loading, refresh } = useRequest(() => getSyncTasks(), {
    pollingInterval: 10_000,
    pollingWhenHidden: false,
  });

  const tasks = (data || []) as API.SyncTask[];
  const taskMetrics = useMemo(() => ({
    total: tasks.length,
    running: tasks.filter((task) => task.status === 'running').length,
    draft: tasks.filter((task) => task.status === 'draft').length,
    failed: tasks.filter((task) => task.status === 'failed').length,
  }), [tasks]);
  const visibleTasks = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return tasks.filter((task) => {
      if (statusFilter && task.status !== statusFilter) return false;
      if (scenarioFilter && task.scenarioCode !== scenarioFilter) return false;
      if (!normalizedKeyword) return true;
      return [task.taskName, task.description, task.flinkJobId]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(normalizedKeyword));
    });
  }, [keyword, scenarioFilter, statusFilter, tasks]);

  const handleAction = async (action: string, id: number) => {
    setActionLoading((previous) => ({ ...previous, [id]: action }));
    try {
      let result: API.SyncTask | undefined;
      switch (action) {
        case 'start':
          result = await startSyncTask(id);
          if (result.status === 'failed') {
            message.error(result.lastErrorMsg || '任务启动失败，请进入详情查看原因后重试');
          } else {
            message.success(result.status === 'running' ? '任务启动成功' : '任务正在提交到 Flink');
          }
          break;
        case 'pause':
          result = await pauseSyncTask(id);
          message.success(result?.status === 'saving_point' ? '正在创建 Savepoint，完成后任务将暂停' : '任务已暂停');
          break;
        case 'resume':
          result = await resumeSyncTask(id);
          message.success(result?.status === 'running' ? '任务已从断点恢复' : '任务正在恢复');
          break;
        case 'stop':
          await stopSyncTask(id);
          message.success('任务已终止');
          break;
        case 'retry':
          result = await retrySyncTask(id);
          if (result.status === 'failed') message.error(result.lastErrorMsg || '重试失败，请进入详情查看原因');
          else message.success(result.status === 'running' ? '任务重试成功' : '任务正在重新提交');
          break;
        default:
          break;
      }
      refresh();
    } catch (error: any) {
      message.error(error?.message || '任务操作失败');
    } finally {
      setActionLoading((previous) => ({ ...previous, [id]: '' }));
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteSyncTask(id);
      message.success('任务已删除');
      refresh();
    } catch (error: any) {
      message.error(error?.message || '任务删除失败');
    }
  };

  const handleSyncAll = async () => {
    try {
      const count = await syncAllTaskStatus();
      message.success(`已从 Flink 刷新 ${count || 0} 个活跃任务状态`);
      refresh();
    } catch (error: any) {
      message.error(error?.message || '状态刷新失败');
    }
  };

  const getActionButtons = (task: API.SyncTask) => {
    if (!access.canManageTask) return null;
    const { status, id } = task;
    const loadingAction = actionLoading[id];
    if (task.executionMode === 'scheduled') {
      return (
        <>
          <Button size="small" type="primary" onClick={() => history.push('/sync-task/workflow')}>编排</Button>
          {task.status === 'draft' && <Popconfirm title="确定删除这个周期任务草稿？" onConfirm={() => handleDelete(id)}>
            <Button size="small" type="link" danger>删除</Button>
          </Popconfirm>}
        </>
      );
    }
    const actionButton = (
      action: string,
      label: string,
      icon?: React.ReactNode,
      type?: 'primary' | 'default',
      danger?: boolean,
    ) => (
      <Button
        size="small"
        type={type || 'default'}
        danger={danger}
        icon={icon}
        loading={loadingAction === action}
        onClick={() => handleAction(action, id)}
      >
        {label}
      </Button>
    );

    switch (status) {
      case 'draft':
        return (
          <>
            {actionButton('start', '启动', <PlayCircleOutlined />, 'primary')}
            <Popconfirm title="确定删除这个未启动任务？" onConfirm={() => handleDelete(id)}>
              <Button size="small" type="link" danger>删除</Button>
            </Popconfirm>
          </>
        );
      case 'running':
        return (
          <>
            {actionButton('pause', '暂停', <PauseOutlined />)}
            <Popconfirm title="确定立即终止？本次不会保留 Savepoint。" onConfirm={() => handleAction('stop', id)}>
              <Button size="small" danger loading={loadingAction === 'stop'}>终止</Button>
            </Popconfirm>
          </>
        );
      case 'saving_point':
        return <Badge status="warning" text="正在保存断点" />;
      case 'submitting':
        return <Badge status="processing" text="正在提交" />;
      case 'paused':
        return (
          <>
            {actionButton('resume', '恢复', <PlayCircleOutlined />, 'primary')}
            <Popconfirm title="确定终止这个暂停任务？" onConfirm={() => handleAction('stop', id)}>
              <Button size="small" danger>终止</Button>
            </Popconfirm>
          </>
        );
      case 'failed':
        return (
          <>
            {actionButton('retry', '重新启动', <ReloadOutlined />, 'primary')}
            <Button size="small" type="link" onClick={() => history.push(`/sync-task/detail/${id}`)}>查看原因</Button>
          </>
        );
      case 'finished':
        return (
          <Popconfirm title="确定删除这个已终止任务？" onConfirm={() => handleDelete(id)}>
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        );
      default:
        return null;
    }
  };

  const setQuickStatus = (status?: string) => {
    setStatusFilter((current) => current === status ? undefined : status);
  };

  return (
    <PageContainer
      title="开发任务"
      subTitle="创建、启动并持续管理 Flink 数据任务"
      className="task-list-page"
      extra={access.canCreateTask && (
        <Button type="primary" icon={<PlusOutlined />} onClick={() => history.push('/sync-task/create')}>
          创建并启动任务
        </Button>
      )}
    >
      <Row gutter={[16, 16]} className="task-list-summary">
        <Col xs={12} lg={6}>
          <Card hoverable className="task-summary-card" onClick={() => setQuickStatus(undefined)}>
            <Statistic title="全部任务" value={taskMetrics.total} prefix={<DatabaseOutlined />} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card hoverable className="task-summary-card" onClick={() => setQuickStatus('running')}>
            <Statistic title="运行中" value={taskMetrics.running} prefix={<PlayCircleOutlined />} valueStyle={{ color: '#1677ff' }} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card hoverable className="task-summary-card" onClick={() => setQuickStatus('draft')}>
            <Statistic title="等待启动" value={taskMetrics.draft} prefix={<ClockCircleOutlined />} valueStyle={{ color: '#8c8c8c' }} />
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card hoverable className="task-summary-card" onClick={() => setQuickStatus('failed')}>
            <Statistic title="需要处理" value={taskMetrics.failed} prefix={<ExclamationCircleOutlined />} valueStyle={{ color: taskMetrics.failed ? '#cf1322' : '#52c41a' }} />
          </Card>
        </Col>
      </Row>

      {taskMetrics.draft > 0 && (
        <Alert
          className="task-list-onboarding"
          type="info"
          showIcon
          message={`有 ${taskMetrics.draft} 个任务已完成配置，正在等待首次启动`}
          action={<Button size="small" onClick={() => setStatusFilter('draft')}>查看等待启动任务</Button>}
        />
      )}

      <Card>
        <div className="task-list-toolbar">
          <div className="task-list-filters">
            <Input
              placeholder="搜索任务名称、说明或 Job ID"
              prefix={<SearchOutlined />}
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              allowClear
            />
            <Select
              placeholder="全部状态"
              allowClear
              value={statusFilter}
              onChange={setStatusFilter}
              options={Object.entries(statusConfig).map(([value, config]) => ({ label: config.label, value }))}
            />
            <Select
              placeholder="全部场景"
              allowClear
              value={scenarioFilter}
              onChange={setScenarioFilter}
              options={availableTaskScenarios.map((scenario) => ({ value: scenario.code, label: scenario.title }))}
            />
          </div>
          <Space>
            {access.canManageTask && (
              <Tooltip title="从 Flink 集群刷新活跃任务状态">
                <Button icon={<SyncOutlined />} onClick={handleSyncAll}>刷新运行状态</Button>
              </Tooltip>
            )}
          </Space>
        </div>

        <Table<API.SyncTask>
          dataSource={visibleTasks}
          rowKey="id"
          loading={loading}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={tasks.length ? '没有匹配的任务' : '还没有任务，创建后可直接启动'} /> }}
          pagination={{ pageSize: 15, showSizeChanger: true, showTotal: (total) => `共 ${total} 个任务` }}
          columns={[
            {
              title: '任务', dataIndex: 'taskName', key: 'taskName', width: 260,
              render: (value, record) => (
                <div className="task-name-cell">
                  <button type="button" onClick={() => history.push(`/sync-task/detail/${record.id}`)}>{value || `任务 #${record.id}`}</button>
                  <small>{record.description || `创建于 ${formatBackendDateTime(record.createdAt)}`}</small>
                </div>
              ),
            },
            {
              title: '任务场景', key: 'scenarioCode', width: 150,
              render: (_, record) => (
                <Tag color={getTaskScenarioColor(record.scenarioCode, record.taskType)}>
                  {getTaskScenarioLabel(record.scenarioCode, record.taskType)}
                </Tag>
              ),
            },
            {
              title: '状态', dataIndex: 'status', key: 'status', width: 150,
              render: (value, record) => {
                if (record.executionMode === 'scheduled') return (
                  <div className="task-status-cell">
                    <Tag color={record.definitionStatus === 'published' ? 'blue' : 'default'}>{record.definitionStatus === 'published' ? '已发布' : '草稿'}</Tag>
                    <small>周期实例运行</small>
                  </div>
                );
                const config = statusConfig[value] || { color: 'default', label: value, hint: '' };
                return (
                  <div className="task-status-cell">
                    <Tag color={config.color}>{config.label}</Tag>
                    <small>{record.flinkJobId ? `Job ${record.flinkJobId.slice(0, 12)}…` : config.hint}</small>
                  </div>
                );
              },
            },
            {
              title: '实时运行指标', key: 'metrics', width: 250,
              render: (_, record) => record.executionMode === 'scheduled'
                ? <span style={{ color: '#8c8c8c' }}>在任务编排查看实例</span>
                : record.status === 'running' ? (
                <Space size={12} wrap>
                  <span>延迟 <b style={{ color: (record.currentLagMs || 0) > 5_000 ? '#cf1322' : '#389e0d' }}>{record.currentLagMs ?? '—'} ms</b></span>
                  <span>吞吐 <b>{record.throughputQps ?? '—'}</b></span>
                  <span>CP <b>{record.checkpointCount ?? 0}</b></span>
                </Space>
              ) : <span style={{ color: '#bfbfbf' }}>启动后展示</span>,
            },
            {
              title: '最近更新', dataIndex: 'updatedAt', key: 'updatedAt', width: 170,
              render: (value, record) => formatBackendDateTime(value || record.createdAt),
            },
            {
              title: '操作', key: 'action', width: 250, fixed: 'right',
              render: (_, record) => (
                <Space size={6} wrap>
                  {getActionButtons(record)}
                  {!['failed'].includes(record.status) && (
                    <Button size="small" type="link" onClick={() => history.push(`/sync-task/detail/${record.id}`)}>详情</Button>
                  )}
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
