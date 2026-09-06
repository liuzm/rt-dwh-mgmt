import React from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Col, Empty, List, Progress, Row, Space, Statistic, Table, Tag, Tooltip, Typography } from 'antd';
import { useAccess, useRequest, history } from '@umijs/max';
import {
  AlertOutlined, ApiOutlined, BarChartOutlined, CloseCircleOutlined, CodeOutlined,
  DatabaseOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { getAlertRecords, getDwhTables, getFoundationSummary, getSyncTasks } from '@/api';

const statusConfig: Record<string, { color: string; label: string; priority: number }> = {
  failed: { color: 'error', label: '失败', priority: 0 },
  saving_point: { color: 'warning', label: '保存点处理中', priority: 1 },
  submitting: { color: 'processing', label: '提交中', priority: 2 },
  running: { color: 'processing', label: '运行中', priority: 3 },
  paused: { color: 'warning', label: '已暂停', priority: 4 },
  finished: { color: 'success', label: '已完成', priority: 5 },
  draft: { color: 'default', label: '草稿', priority: 6 },
};

const taskTypeMap: Record<string, string> = { cdc_sync: 'CDC 同步', etl: 'ETL', materialized: '物化表' };
const alertLevelColor: Record<string, string> = { info: 'blue', low: 'blue', warn: 'warning', medium: 'warning', error: 'error', high: 'error' };

const Dashboard: React.FC = () => {
  const access = useAccess();
  const tasksRequest = useRequest(getSyncTasks, { pollingInterval: 15000, pollingWhenHidden: false });
  const tablesRequest = useRequest(getDwhTables);
  const alertsRequest = useRequest(() => getAlertRecords({ resolved: false }), { pollingInterval: 30000, pollingWhenHidden: false });
  const foundationRequest = useRequest(getFoundationSummary, { pollingInterval: 30000, pollingWhenHidden: false });

  const tasks = (tasksRequest.data || []) as API.SyncTask[];
  const tables = (tablesRequest.data || []) as API.DwhTableMeta[];
  const alerts = (alertsRequest.data || []) as API.AlertRecord[];
  const foundation = foundationRequest.data as API.FoundationSummary | undefined;
  const runningCount = tasks.filter((task) => task.status === 'running').length;
  const failedCount = tasks.filter((task) => task.status === 'failed').length;
  const attentionTasks = [...tasks].filter((task) => task.status !== 'draft').sort((left, right) =>
    (statusConfig[left.status]?.priority ?? 99) - (statusConfig[right.status]?.priority ?? 99)).slice(0, 8);

  const refreshAll = () => {
    tasksRequest.refresh();
    tablesRequest.refresh();
    alertsRequest.refresh();
    foundationRequest.refresh();
  };

  const metricCard = (title: string, value: number, color: string, background: string,
    icon: React.ReactNode, path: string) => (
    <Card className="rtdwh-metric-card rtdwh-clickable-card" hoverable onClick={() => history.push(path)}>
      <span className="rtdwh-metric-icon" style={{ color, background }}>{icon}</span>
      <Statistic title={title} value={value} valueStyle={{ color }} />
    </Card>
  );

  const quickActions = [
    access.canCreateTask && { title: '创建开发任务', description: '接入 CDC 或构建 ETL', icon: <PlusOutlined />, path: '/sync-task/create' },
    access.canQuery && { title: '查询并下载', description: '使用 Doris 查询 Paimon', icon: <CodeOutlined />, path: '/query/adhoc' },
    access.canViewReport && { title: '查看报表', description: '进入业务数据看板', icon: <BarChartOutlined />, path: '/query/report' },
    access.canViewDataService && { title: '发布数据接口', description: '管理外部系统调用', icon: <ApiOutlined />, path: '/query/data-service' },
  ].filter(Boolean) as Array<{ title: string; description: string; icon: React.ReactNode; path: string }>;

  return <PageContainer title="工作台" subTitle="集中查看任务运行、湖仓资产、告警和资产治理风险"
    extra={<Tooltip title="刷新任务、资产、告警和治理指标"><Button icon={<ReloadOutlined />} onClick={refreshAll}>刷新数据</Button></Tooltip>}>
    {(foundation?.slaRisks.length || 0) > 0 && <Alert showIcon type="warning" style={{ marginBottom: 16 }}
      message={`发现 ${foundation?.slaRisks.length} 个数据资源存在 SLA 风险`}
      description={<Space wrap><span>建议优先处理逾期产出，避免下游报表和接口使用旧数据。</span><Button size="small" onClick={() => history.push('/foundation')}>查看风险</Button></Space>} />}

    <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
      <Col xs={24} sm={12} xl={6}>{metricCard('运行中任务', runningCount, '#1677ff', '#e6f4ff', <ThunderboltOutlined />, '/sync-task/list')}</Col>
      <Col xs={24} sm={12} xl={6}>{metricCard('数仓表总数', tables.length, '#389e0d', '#f6ffed', <DatabaseOutlined />, '/dwh/tables')}</Col>
      <Col xs={24} sm={12} xl={6}>{metricCard('未解决告警', alerts.length, '#d48806', '#fffbe6', <AlertOutlined />, '/system/alert')}</Col>
      <Col xs={24} sm={12} xl={6}>{metricCard('失败任务', failedCount, '#cf1322', '#fff1f0', <CloseCircleOutlined />, '/sync-task/list')}</Col>
    </Row>

    <Card title="常用工作入口" style={{ marginBottom: 16 }}>
      <div className="rtdwh-quick-actions">
        {quickActions.map((item) => <button type="button" className="rtdwh-quick-action" key={item.path} onClick={() => history.push(item.path)}>
          <span>{item.icon}</span><span><b>{item.title}</b><small>{item.description}</small></span>
        </button>)}
      </div>
    </Card>

    <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
      <Col xs={24} xl={17}>
        <Card title="任务运行与异常" extra={<Button type="link" onClick={() => history.push('/sync-task/list')}>全部任务</Button>}>
          <Table<API.SyncTask> rowKey="id" size="small" loading={tasksRequest.loading} dataSource={attentionTasks}
            pagination={false} scroll={{ x: 760 }} locale={{ emptyText: '暂无运行中或已完成任务' }}
            onRow={(record) => ({ onClick: () => history.push(`/sync-task/detail/${record.id}`), style: { cursor: 'pointer' } })}
            columns={[
              { title: '任务名称', key: 'taskName', ellipsis: true, render: (_, record) => <Typography.Text strong>{record.taskName || record.name || '—'}</Typography.Text> },
              { title: '类型', dataIndex: 'taskType', width: 105, render: (value) => taskTypeMap[value] || value },
              { title: '状态', dataIndex: 'status', width: 105, render: (value) => <Tag color={statusConfig[value]?.color}>{statusConfig[value]?.label || value}</Tag> },
              { title: '延迟', dataIndex: 'currentLagMs', width: 100, render: (value) => value == null ? '—' : <span style={{ color: value > 5000 ? '#cf1322' : undefined }}>{value} ms</span> },
              { title: '吞吐', dataIndex: 'throughputQps', width: 90, render: (value) => value == null ? '—' : `${value} QPS` },
              { title: '操作', width: 75, fixed: 'right', render: (_, record) => <Button type="link" size="small" onClick={(event) => { event.stopPropagation(); history.push(`/sync-task/detail/${record.id}`); }}>处理</Button> },
            ]} />
        </Card>
      </Col>
      <Col xs={24} xl={7}>
        <Card title="待处理告警" extra={<Button type="link" onClick={() => history.push('/system/alert')}>告警中心</Button>} className="rtdwh-dashboard-side-card">
          {alerts.length ? <List loading={alertsRequest.loading} dataSource={alerts.slice(0, 6)} renderItem={(item) => <List.Item onClick={() => history.push('/system/alert')} className="rtdwh-alert-item">
            <List.Item.Meta title={<Space><Tag color={alertLevelColor[item.level || ''] || 'warning'}>{item.level || 'warn'}</Tag><Typography.Text ellipsis>{item.ruleType}</Typography.Text></Space>}
              description={<><Typography.Text type="secondary" ellipsis>{item.message || '等待处理'}</Typography.Text><div className="rtdwh-list-time">{item.triggeredAt ? new Date(item.triggeredAt).toLocaleString('zh-CN') : '—'}</div></>} />
          </List.Item>} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有未解决告警" />}
        </Card>
      </Col>
    </Row>

    <Card title={<Space><SafetyCertificateOutlined />资产治理概况</Space>}
      extra={<Button type="link" onClick={() => history.push('/foundation')}>进入治理中心</Button>}>
      <Row gutter={[24, 16]} align="middle">
        <Col xs={24} md={5} className="rtdwh-foundation-score">
          <Progress type="dashboard" size={112} percent={foundation?.overallScore || 0} strokeColor={(foundation?.overallScore || 0) >= 80 ? '#52c41a' : '#faad14'} />
        </Col>
        <Col xs={24} md={19}>
          <Row gutter={[12, 12]}>{(foundation?.capabilities || []).map((item) => <Col xs={12} lg={8} xl={Math.floor(24 / Math.min(foundation?.capabilities.length || 1, 5))} key={item.key}>
            <div className="rtdwh-capability-summary" onClick={() => history.push(item.path)}>
              <span>{item.name}</span><b>{item.score} 分</b><Tag color={item.status === 'healthy' ? 'success' : item.status === 'risk' ? 'error' : 'warning'}>{item.riskCount} 项风险</Tag>
            </div>
          </Col>)}</Row>
        </Col>
      </Row>
    </Card>
  </PageContainer>;
};

export default Dashboard;
