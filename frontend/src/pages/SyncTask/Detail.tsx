import React, { useState, useEffect, useCallback } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Card, Descriptions, Statistic, Row, Col, Tag, Button, Space,
  Tabs, Alert, Badge, Tooltip, Spin, Popconfirm, message, Modal,
  Timeline, Divider, Progress, Empty, Typography,
} from 'antd';
import {
  PlayCircleOutlined, PauseCircleOutlined, StopOutlined,
  RedoOutlined, SaveOutlined, EditOutlined, DeleteOutlined,
  SyncOutlined, ExclamationCircleOutlined, CloudUploadOutlined,
  ClockCircleOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { useAccess, useParams, history } from '@umijs/max';
import {
  getSyncTask, getSyncTaskStatus, getSyncTaskLogs,
  startSyncTask, pauseSyncTask, resumeSyncTask, stopSyncTask,
  retrySyncTask, triggerSavepoint, updateSyncTask, deleteSyncTask,
  syncAllTaskStatus, getPostgresCdcStatus, cleanupPostgresCdc,
} from '@/api';
import { getTaskScenarioColor, getTaskScenarioLabel, taskTypeLabel } from './scenarios';
import FlinkJobScalingCard from './FlinkJobScalingCard';
import ContinuousReleaseCard from './ContinuousReleaseCard';

const statusConfig: Record<string, { color: string; label: string; badge: string }> = {
  draft: { color: 'default', label: '未启动', badge: 'default' },
  submitting: { color: 'processing', label: '提交中', badge: 'processing' },
  running: { color: 'blue', label: '运行中', badge: 'processing' },
  saving_point: { color: 'warning', label: '保存断点', badge: 'warning' },
  paused: { color: 'orange', label: '已暂停', badge: 'warning' },
  failed: { color: 'red', label: '启动／运行失败', badge: 'error' },
  finished: { color: 'green', label: '已终止', badge: 'success' },
};

const syncStrategyMap: Record<string, string> = {
  full_then_incremental: '全量+增量',
  incremental_only: '仅增量',
};

const SyncTaskDetail: React.FC = () => {
  const access = useAccess();
  const { id } = useParams<{ id: string }>();
  const taskId = parseInt(id || '0');

  // State
  const [actionLoading, setActionLoading] = useState<string>('');
  const [logTab, setLogTab] = useState<string>('jobmanager');
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editForm, setEditForm] = useState<any>({});

  // State for task data, status, and logs
  const [task, setTask] = useState<API.SyncTask | null>(null);
  const [taskLoading, setTaskLoading] = useState(true);
  const [statusInfo, setStatusInfo] = useState<any>(null);
  const [logData, setLogData] = useState<any>(null);
  const [logLoading, setLogLoading] = useState(false);
  const [postgresStatus, setPostgresStatus] = useState<API.PostgresCdcStatus>();
  const [postgresLoading, setPostgresLoading] = useState(false);

  // Fetch task data
  const fetchTask = useCallback(async () => {
    try {
      const data = await getSyncTask(taskId);
      setTask(data);
    } catch { /* ignore */ }
    setTaskLoading(false);
  }, [taskId]);

  useEffect(() => { fetchTask(); }, [fetchTask]);

  // Fetch status
  const fetchStatus = useCallback(async () => {
    try {
      const data = await getSyncTaskStatus(taskId);
      setStatusInfo(data);
      setTask((previous) => previous && data?.taskStatus && previous.status !== data.taskStatus
        ? { ...previous, status: data.taskStatus as API.SyncTask['status'] }
        : previous);
    } catch { /* ignore */ }
  }, [taskId]);

  useEffect(() => { fetchStatus(); }, [fetchStatus]);

  const fetchPostgresStatus = useCallback(async () => {
    setPostgresLoading(true);
    try { setPostgresStatus(await getPostgresCdcStatus(taskId)); }
    catch (error: any) { message.error(error?.message || 'PostgreSQL CDC 预检失败'); }
    finally { setPostgresLoading(false); }
  }, [taskId]);

  useEffect(() => {
    if (statusInfo?.sourceDbType === 'postgresql') fetchPostgresStatus();
  }, [statusInfo?.sourceDbType, fetchPostgresStatus]);

  // Poll status for active tasks
  const taskStatus = task?.status || '';
  const isActive = ['running', 'submitting', 'saving_point'].includes(taskStatus);

  useEffect(() => {
    if (isActive) {
      const timer = setInterval(() => fetchStatus(), 5000);
      return () => clearInterval(timer);
    }
  }, [isActive, fetchStatus]);

  // Fetch logs manually
  const fetchLogs = useCallback(async () => {
    setLogLoading(true);
    try {
      const data = await getSyncTaskLogs(taskId, { type: logTab, lines: 200 });
      setLogData(data);
    } catch { /* ignore */ }
    setLogLoading(false);
  }, [taskId, logTab]);

  // Auto-fetch logs when flinkJobId is available
  useEffect(() => {
    if (task?.flinkJobId) {
      fetchLogs();
    }
  }, [task?.flinkJobId, fetchLogs]);

  if (taskLoading) return <PageContainer><Spin size="large" /></PageContainer>;
  if (!task) return <PageContainer><Empty description="任务不存在" /></PageContainer>;

  const currentStatus = statusInfo?.taskStatus || task.status;
  const isScheduled = task.executionMode === 'scheduled';
  const statusCfg = isScheduled
    ? { color: task.definitionStatus === 'published' ? 'blue' : 'default', label: task.definitionStatus === 'published' ? '已发布' : '草稿' }
    : (statusConfig[currentStatus] || { color: 'default', label: currentStatus });

  // ========================================================================
  // Action handlers
  // ========================================================================

  const handleAction = async (action: string) => {
    setActionLoading(action);
    try {
      let res;
      switch (action) {
        case 'start':
          res = await startSyncTask(taskId);
          if (res?.status === 'failed') message.error(res.lastErrorMsg || '任务启动失败，请根据错误信息修复后重试');
          else message.success(res?.status === 'running' ? '任务启动成功' : '任务正在提交到 Flink');
          break;
        case 'pause':
          res = await pauseSyncTask(taskId);
          message.success(res?.status === 'saving_point' ? '正在创建 Savepoint，请等待...' : '任务已暂停');
          break;
        case 'resume':
          res = await resumeSyncTask(taskId);
          message.success(res?.status === 'running' ? '任务已从 Savepoint 恢复' : '恢复操作进行中');
          break;
        case 'stop':
          res = await stopSyncTask(taskId);
          message.success('任务已停止（未保留 Savepoint）');
          break;
        case 'retry':
          res = await retrySyncTask(taskId);
          if (res?.status === 'failed') message.error(res.lastErrorMsg || '任务重试失败，请检查配置和 Flink 状态');
          else message.success(res?.status === 'running' ? '任务重试成功' : '任务正在重新提交');
          break;
        case 'savepoint':
          res = await triggerSavepoint(taskId);
          message.success('Savepoint 已触发，请轮询状态查看进度');
          break;
        case 'sync':
          res = await syncAllTaskStatus();
          message.success(`状态同步完成，同步 ${res || 0} 个任务`);
          break;
      }
      fetchTask();
      fetchStatus();
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    } finally {
      setActionLoading('');
    }
  };

  const handleDelete = async () => {
    try {
      await deleteSyncTask(taskId);
      message.success('任务已删除');
      history.push('/sync-task/list');
    } catch (e: any) {
      message.error(e?.message || '删除失败');
    }
  };

  const handlePostgresCleanup = async () => {
    setPostgresLoading(true);
    try {
      const result = await cleanupPostgresCdc(taskId);
      message.success(`已清理 ${result.removedSlots.length} 个 Slot、${result.removedPublications.length} 个 Publication`);
      await fetchPostgresStatus();
    } catch (error: any) { message.error(error?.message || 'PostgreSQL CDC 资源清理失败'); }
    finally { setPostgresLoading(false); }
  };

  const handleEdit = () => {
    setEditForm({
      taskName: task.taskName,
      description: task.description || '',
      flinkSql: task.flinkSql || '',
      parallelism: task.parallelism || 1,
      checkpointIntervalMs: task.checkpointIntervalMs || 60000,
    });
    setEditModalOpen(true);
  };

  const handleEditSubmit = async () => {
    try {
      await updateSyncTask(taskId, editForm);
      message.success('任务配置已更新');
      setEditModalOpen(false);
      fetchTask();
    } catch (e: any) {
      message.error(e?.message || '更新失败');
    }
  };

  // ========================================================================
  // Action buttons per status
  // ========================================================================

  const getActionButtons = () => {
    if (!access.canManageTask) return [];
    if (isScheduled) return [
      <Button key="workflow" type="primary" icon={<ClockCircleOutlined />} onClick={() => history.push('/sync-task/workflow')}>进入任务编排</Button>,
      <Button key="edit" icon={<EditOutlined />} onClick={handleEdit}>编辑草稿</Button>,
      <Popconfirm key="delete" title="确认删除此周期任务？" onConfirm={handleDelete}>
        <Button danger icon={<DeleteOutlined />}>删除</Button>
      </Popconfirm>,
    ];
    const btn = (action: string, label: string, icon: React.ReactNode, type?: 'primary' | 'default', danger?: boolean) => (
      <Button
        type={type || 'default'}
        danger={danger}
        icon={icon}
        loading={actionLoading === action}
        onClick={() => handleAction(action)}
      >
        {label}
      </Button>
    );

    switch (currentStatus) {
      case 'draft':
        return [
          <Tooltip key="start" title="提交任务到 Flink 集群">{btn('start', '启动', <PlayCircleOutlined />, 'primary')}</Tooltip>,
          <Tooltip key="edit" title="编辑任务配置">
            <Button icon={<EditOutlined />} onClick={handleEdit}>编辑</Button>
          </Tooltip>,
          <Popconfirm key="delete" title="确认删除此任务？" onConfirm={handleDelete}>
            <Button danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>,
        ];
      case 'submitting':
        return [
          <Badge key="submitting" status="processing" text="正在提交到 Flink..." />,
          <Tooltip key="sync" title="手动同步状态">{btn('sync', '同步', <SyncOutlined />)}</Tooltip>,
        ];
      case 'running':
        return [
          <Tooltip key="pause" title="创建 Savepoint 后暂停，可从断点恢复">{btn('pause', '暂停', <PauseCircleOutlined />, 'primary', true)}</Tooltip>,
          <Tooltip key="savepoint" title="手动触发 Savepoint（不停止任务）">{btn('savepoint', 'Savepoint', <SaveOutlined />)}</Tooltip>,
          <Tooltip key="stop" title="立即停止，不保留 Savepoint">
            <Popconfirm title="确认停止？将不保留 Savepoint，无法恢复。" onConfirm={() => handleAction('stop')}>
              <Button danger icon={<StopOutlined />} loading={actionLoading === 'stop'}>停止</Button>
            </Popconfirm>
          </Tooltip>,
        ];
      case 'saving_point':
        return [
          <Badge key="saving-point" status="warning" text="正在保存 Savepoint..." />,
          <Tooltip key="sync" title="手动同步状态">{btn('sync', '同步', <SyncOutlined />)}</Tooltip>,
          <Popconfirm key="force-stop" title="确认强制停止？将不保留 Savepoint。" onConfirm={() => handleAction('stop')}>
            <Button danger icon={<StopOutlined />}>强制停止</Button>
          </Popconfirm>,
        ];
      case 'paused':
        return [
          <Tooltip key="resume" title="从 Savepoint 恢复运行">{btn('resume', '恢复', <PlayCircleOutlined />, 'primary')}</Tooltip>,
          <Popconfirm key="stop" title="确认停止？暂停任务将被终止。" onConfirm={() => handleAction('stop')}>
            <Button danger icon={<StopOutlined />}>停止</Button>
          </Popconfirm>,
        ];
      case 'failed':
        return [
          <Tooltip key="retry" title="重新提交任务到 Flink">{btn('retry', '重试', <RedoOutlined />, 'primary')}</Tooltip>,
          <Tooltip key="stop" title="标记为终止，不再重试">{btn('stop', '标记终止', <StopOutlined />, undefined, true)}</Tooltip>,
        ];
      case 'finished':
        return [
          <Popconfirm key="delete" title="确认删除此任务？" onConfirm={handleDelete}>
            <Button danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>,
        ];
      default:
        return [];
    }
  };

  // ========================================================================
  // Parse checkpoint info
  // ========================================================================

  let checkpointDetail: any = null;
  if (task.checkpointInfo) {
    try {
      checkpointDetail = JSON.parse(task.checkpointInfo);
    } catch { /* ignore */ }
  }

  // Savepoint path (for paused tasks)
  const savepointPath = checkpointDetail?.savepointPath;

  // ========================================================================
  // Render
  // ========================================================================

  return (
    <PageContainer
      title={task.taskName || `任务 #${task.id}`}
      subTitle={<Tag color={statusCfg.color}>{statusCfg.label}</Tag>}
      extra={
        <Space wrap>
          {getActionButtons()}
          <Tooltip title="刷新页面数据">
            <Button icon={<SyncOutlined />} onClick={() => { fetchTask(); fetchStatus(); fetchLogs(); }}>
              刷新
            </Button>
          </Tooltip>
        </Space>
      }
    >
      {currentStatus === 'draft' && (
        <Alert
          type="info"
          showIcon
          icon={<PlayCircleOutlined />}
          message={isScheduled ? '周期任务草稿已保存' : '任务配置已保存，尚未启动'}
          description={isScheduled ? '请进入任务编排发布不可变版本，再配置周期调度、依赖或补数。' : '启动使用已发布配置；首次启动自动发布版本。修改草稿后请重新发布，新版本不会影响在运行作业。'}
          action={access.canManageTask && !isScheduled ? (
            <Button type="primary" icon={<PlayCircleOutlined />} loading={actionLoading === 'start'} onClick={() => handleAction('start')}>
              立即启动
            </Button>
          ) : undefined}
          style={{ marginBottom: 16 }}
        />
      )}

      {/* Status Banner */}
      {(currentStatus === 'failed' || currentStatus === 'saving_point' || currentStatus === 'submitting') && (
        <Alert
          type={currentStatus === 'failed' ? 'error' : currentStatus === 'saving_point' ? 'warning' : 'info'}
          showIcon
          icon={
            currentStatus === 'failed' ? <ExclamationCircleOutlined /> :
            currentStatus === 'saving_point' ? <ClockCircleOutlined /> :
            <CloudUploadOutlined />
          }
          message={
            currentStatus === 'failed' ? '任务执行失败' :
            currentStatus === 'saving_point' ? '正在创建 Savepoint...' :
            '正在提交到 Flink 集群...'
          }
          description={
            currentStatus === 'failed' ? (task.lastErrorMsg || statusInfo?.lastErrorMsg || '请查看日志获取详细信息') :
            currentStatus === 'saving_point' ? `触发 ID: ${task.savepointTriggerId || statusInfo?.savepointTriggerId || '—'}，等待 Savepoint 完成...` :
            '等待 Flink 集群响应...'
          }
          style={{ marginBottom: 16 }}
          action={
            access.canManageTask && currentStatus === 'failed' ? (
              <Button size="small" type="primary" danger icon={<RedoOutlined />} onClick={() => handleAction('retry')}>
                重试
              </Button>
            ) : undefined
          }
        />
      )}

      {/* Paused with savepoint banner */}
      {currentStatus === 'paused' && savepointPath && (
        <Alert
          type="success"
          showIcon
          message="任务已暂停，Savepoint 已保存"
          description={`Savepoint 路径: ${savepointPath}`}
          style={{ marginBottom: 16 }}
          action={
            access.canManageTask ? <Button size="small" type="primary" icon={<PlayCircleOutlined />} onClick={() => handleAction('resume')}>
              从 Savepoint 恢复
            </Button> : undefined
          }
        />
      )}

      {/* Savepoint Progress (for saving_point state) */}
      {currentStatus === 'saving_point' && (
        <Card title="Savepoint 进度" style={{ marginBottom: 16 }}>
          <Row gutter={16}>
            <Col span={8}>
              <Statistic title="触发 ID" value={task.savepointTriggerId || '—'} />
            </Col>
            <Col span={8}>
              <Statistic
                title="Savepoint 状态"
                value={statusInfo?.savepointProgress || 'IN_PROGRESS'}
                valueStyle={{
                  color: statusInfo?.savepointProgress === 'COMPLETED' ? '#52c41a' :
                         statusInfo?.savepointProgress === 'FAILED' ? '#ff4d4f' : '#faad14',
                }}
              />
            </Col>
            <Col span={8}>
              <Statistic title="自动轮询" value="5秒" suffix="/次" />
            </Col>
          </Row>
        </Card>
      )}

      {/* Basic Info */}
      <Card title="基本信息" style={{ marginBottom: 16 }}>
        <Descriptions column={3} bordered size="small">
          <Descriptions.Item label="任务名称">{task.taskName}</Descriptions.Item>
          <Descriptions.Item label="任务场景">
            <Tag color={getTaskScenarioColor(task.scenarioCode, task.taskType)}>
              {getTaskScenarioLabel(task.scenarioCode, task.taskType)}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={statusCfg.color} style={{ fontSize: 13 }}>
              {statusCfg.label}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="运行方式">{isScheduled ? '周期实例' : '持续作业'}</Descriptions.Item>
          <Descriptions.Item label="定义状态">{task.definitionStatus === 'published' ? '已发布' : '草稿'}</Descriptions.Item>
          <Descriptions.Item label="同步策略">{syncStrategyMap[task.syncStrategy] || task.syncStrategy}</Descriptions.Item>
          <Descriptions.Item label="执行器">{taskTypeLabel[task.taskType as keyof typeof taskTypeLabel] || task.taskType}</Descriptions.Item>
          <Descriptions.Item label="并行度">{task.parallelism || 1}</Descriptions.Item>
          <Descriptions.Item label="Checkpoint 间隔">
            {(task.checkpointIntervalMs || 60000) / 1000}秒
          </Descriptions.Item>
          <Descriptions.Item label="Flink Job ID">
            {task.flinkJobId ? (
              <Tooltip title={task.flinkJobId}>
                <Tag color="geekblue">{task.flinkJobId.substring(0, 16)}...</Tag>
              </Tooltip>
            ) : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="Flink Jar ID">{task.flinkJarId || '—'}</Descriptions.Item>
          <Descriptions.Item label="创建者 ID">{task.creatorId}</Descriptions.Item>
          <Descriptions.Item label="描述" span={3}>{task.description || '—'}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{task.createdAt ? new Date(task.createdAt).toLocaleString() : '—'}</Descriptions.Item>
          <Descriptions.Item label="提交时间">{task.submittedAt ? new Date(task.submittedAt).toLocaleString() : '—'}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{task.updatedAt ? new Date(task.updatedAt).toLocaleString() : '—'}</Descriptions.Item>
        </Descriptions>
      </Card>

      {!isScheduled && <ContinuousReleaseCard taskId={taskId} onPublished={fetchTask} />}

      {/* Real-time Metrics */}
      {statusInfo?.sourceDbType === 'postgresql' && (
        <Card title="PostgreSQL CDC 资源" style={{ marginBottom: 16 }}
          extra={<Space>
            <Button icon={<SyncOutlined />} loading={postgresLoading} onClick={fetchPostgresStatus}>重新预检</Button>
            {access.canManageTask && <Popconfirm title="确认清理该任务的 Slot 与 Publication？再次启动会重新创建。" onConfirm={handlePostgresCleanup}>
              <Button danger disabled={isActive} loading={postgresLoading}>清理资源</Button>
            </Popconfirm>}
          </Space>}>
          <Alert showIcon type={postgresStatus?.ready ? 'success' : 'error'}
            message={postgresStatus?.ready ? 'CDC 环境已就绪' : (postgresStatus?.error || '正在检查 CDC 环境')}
            description={postgresStatus && `wal_level=${postgresStatus.walLevel}；Slot ${postgresStatus.usedReplicationSlots}/${postgresStatus.maxReplicationSlots}；需新建 ${postgresStatus.requiredNewSlots} 个`} />
          {postgresStatus?.resources?.length ? (
            <Descriptions size="small" bordered column={1} style={{ marginTop: 12 }}>
              {postgresStatus.resources.map((resource) => (
                <Descriptions.Item key={resource.slot} label={resource.sourceTable}>
                  Slot: <Typography.Text code copyable>{resource.slot}</Typography.Text>
                  {' · '}Publication: <Typography.Text code copyable>{resource.publication}</Typography.Text>
                </Descriptions.Item>
              ))}
            </Descriptions>
          ) : null}
        </Card>
      )}

      {!isScheduled && <Card title="实时监控指标" style={{ marginBottom: 16 }}>
        <Row gutter={16}>
          <Col span={6}>
            <Statistic
              title="当前延迟 (Lag)"
              value={statusInfo?.currentLagMs ?? task.currentLagMs ?? 0}
              suffix="ms"
              valueStyle={{
                color: (statusInfo?.currentLagMs ?? task.currentLagMs ?? 0) > 5000 ? '#ff4d4f' : '#1a73e8',
              }}
              prefix={<ClockCircleOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="吞吐量 (QPS)"
              value={statusInfo?.throughputQps ?? task.throughputQps ?? 0}
              precision={1}
              valueStyle={{ color: '#52c41a' }}
              prefix={<ThunderboltOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="Checkpoint 数"
              value={statusInfo?.checkpointCount ?? task.checkpointCount ?? 0}
              suffix="次"
              valueStyle={{ color: '#722ed1' }}
              prefix={<SaveOutlined />}
            />
          </Col>
          <Col span={6}>
            <Statistic
              title="Flink 状态"
              value={statusInfo?.flinkJobStatus ?? 'NO_JOB'}
              valueStyle={{
                color: statusInfo?.flinkJobStatus === 'RUNNING' ? '#52c41a' :
                       statusInfo?.flinkJobStatus === 'FAILED' ? '#ff4d4f' :
                       '#1890ff',
              }}
            />
          </Col>
        </Row>
        <Divider />
        <Row gutter={16}>
          <Col span={8}>
            <Statistic
              title="最近 Checkpoint"
              value={
                statusInfo?.lastCheckpointTime ?? task.lastCheckpointTime
                  ? new Date(statusInfo?.lastCheckpointTime ?? task.lastCheckpointTime!).toLocaleString()
                  : '—'
              }
            />
          </Col>
          <Col span={8}>
            <Statistic title="Savepoint 路径" value={savepointPath || '—'} />
          </Col>
          <Col span={8}>
            <Statistic
              title="Checkpoint 间隔"
              value={(task.checkpointIntervalMs || 60000) / 1000}
              suffix="秒"
            />
          </Col>
        </Row>
      </Card>}

      {!isScheduled && currentStatus === 'running' && task.flinkJobId && <FlinkJobScalingCard taskId={taskId} />}

      {/* Error Detail */}
      {(currentStatus === 'failed' || task.lastErrorMsg) && (task.lastErrorMsg || statusInfo?.lastErrorMsg) && (
        <Card title="错误详情" style={{ marginBottom: 16 }}>
          <Alert
            type="error"
            showIcon
            message="任务执行错误"
            description={task.lastErrorMsg || statusInfo?.lastErrorMsg || '未知错误'}
          />
        </Card>
      )}

      {/* Flink SQL */}
      <Card title="Flink SQL 定义" style={{ marginBottom: 16 }}>
        <pre style={{
          background: '#1e1e1e',
          color: '#d4d4d4',
          padding: 16,
          borderRadius: 8,
          fontFamily: 'Courier New, monospace',
          fontSize: 13,
          lineHeight: 1.6,
          overflow: 'auto',
          maxHeight: 400,
        }}>
          {task.flinkSql || '-- 无 SQL 定义 --'}
        </pre>
      </Card>

      {/* Checkpoint Detail */}
      {checkpointDetail && !checkpointDetail.savepointPath && (
        <Card title="Checkpoint 详情" style={{ marginBottom: 16 }}>
          <Descriptions column={2} bordered size="small">
            {Object.entries(checkpointDetail).map(([key, value]) => (
              <Descriptions.Item label={key} key={key}>
                {typeof value === 'object' ? JSON.stringify(value) : String(value)}
              </Descriptions.Item>
            ))}
          </Descriptions>
        </Card>
      )}

      {/* Task Logs */}
      {task.flinkJobId && (
        <Card title="任务日志" style={{ marginBottom: 16 }}>
          <Tabs
            activeKey={logTab}
            onChange={(key) => setLogTab(key)}
            items={[
              {
                key: 'jobmanager',
                label: 'JobManager',
                children: (
                  <div style={{
                    background: '#0d1117',
                    color: '#c9d1d9',
                    padding: 12,
                    borderRadius: 6,
                    fontFamily: 'Courier New, monospace',
                    fontSize: 12,
                    lineHeight: 1.5,
                    overflow: 'auto',
                    maxHeight: 500,
                    whiteSpace: 'pre-wrap',
                  }}>
                    {logLoading ? <Spin /> : (logData?.logs || '暂无日志')}
                  </div>
                ),
              },
              {
                key: 'taskmanager',
                label: 'TaskManager',
                children: (
                  <div style={{
                    background: '#0d1117',
                    color: '#c9d1d9',
                    padding: 12,
                    borderRadius: 6,
                    fontFamily: 'Courier New, monospace',
                    fontSize: 12,
                    lineHeight: 1.5,
                    overflow: 'auto',
                    maxHeight: 500,
                    whiteSpace: 'pre-wrap',
                  }}>
                    {logLoading ? <Spin /> : (logData?.logs || '暂无日志')}
                  </div>
                ),
              },
            ]}
            tabBarExtraContent={
              <Button
                size="small"
                icon={<SyncOutlined />}
                onClick={() => fetchLogs()}
                loading={logLoading}
              >
                刷新日志
              </Button>
            }
          />
        </Card>
      )}

      {/* State Machine Timeline */}
      <Card title="任务生命周期" style={{ marginBottom: 16 }}>
        <Timeline
          items={[
            { color: 'green', children: `创建 Draft — ${task.createdAt ? new Date(task.createdAt).toLocaleString() : '—'}` },
            task.submittedAt ? { color: 'blue', children: `提交到 Flink — ${new Date(task.submittedAt).toLocaleString()}` } : null,
            task.flinkJobId ? { color: 'blue', children: `获得 Job ID: ${task.flinkJobId}` } : null,
            task.lastCheckpointTime ? { color: 'purple', children: `首次 Checkpoint — ${new Date(task.lastCheckpointTime).toLocaleString()}` } : null,
            savepointPath ? { color: 'orange', children: `Savepoint 已保存: ${savepointPath}` } : null,
            currentStatus === 'failed' ? { color: 'red', children: `任务失败: ${task.lastErrorMsg || '未知错误'}` } : null,
            currentStatus === 'finished' ? { color: 'green', children: '任务已完成' } : null,
          ].filter(Boolean) as any}
        />
      </Card>

      {/* Edit Modal */}
      <Modal
        title="编辑任务配置"
        open={editModalOpen}
        onCancel={() => setEditModalOpen(false)}
        onOk={handleEditSubmit}
        okText="保存"
        width={700}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div>
            <label style={{ fontWeight: 500, marginBottom: 4, display: 'block' }}>任务名称</label>
            <input
              style={{ width: '100%', padding: '4px 11px', border: '1px solid #d9d9d9', borderRadius: 6 }}
              value={editForm.taskName}
              onChange={(e) => setEditForm({ ...editForm, taskName: e.target.value })}
            />
          </div>
          <div>
            <label style={{ fontWeight: 500, marginBottom: 4, display: 'block' }}>描述</label>
            <textarea
              style={{ width: '100%', padding: '4px 11px', border: '1px solid #d9d9d9', borderRadius: 6, minHeight: 60 }}
              value={editForm.description}
              onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
            />
          </div>
          <div>
            <label style={{ fontWeight: 500, marginBottom: 4, display: 'block' }}>并行度</label>
            <input
              type="number"
              style={{ width: 200, padding: '4px 11px', border: '1px solid #d9d9d9', borderRadius: 6 }}
              value={editForm.parallelism}
              onChange={(e) => setEditForm({ ...editForm, parallelism: parseInt(e.target.value) || 1 })}
            />
          </div>
          <div>
            <label style={{ fontWeight: 500, marginBottom: 4, display: 'block' }}>Checkpoint 间隔 (ms)</label>
            <input
              type="number"
              style={{ width: 200, padding: '4px 11px', border: '1px solid #d9d9d9', borderRadius: 6 }}
              value={editForm.checkpointIntervalMs}
              onChange={(e) => setEditForm({ ...editForm, checkpointIntervalMs: parseInt(e.target.value) || 60000 })}
            />
          </div>
          <div>
            <label style={{ fontWeight: 500, marginBottom: 4, display: 'block' }}>Flink SQL</label>
            <textarea
              style={{ width: '100%', padding: '8px 11px', border: '1px solid #d9d9d9', borderRadius: 6, minHeight: 200, fontFamily: 'monospace', fontSize: 13 }}
              value={editForm.flinkSql}
              onChange={(e) => setEditForm({ ...editForm, flinkSql: e.target.value })}
            />
          </div>
        </div>
      </Modal>
    </PageContainer>
  );
};

export default SyncTaskDetail;
