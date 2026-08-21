import React from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Row, Col, Statistic, Table, Tag, Button, Tooltip } from 'antd';
import { useRequest } from '@umijs/max';
import {
  AlertOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { getSyncTasks, getDwhTables, getAlertRecords } from '@/api';

const statusColorMap: Record<string, string> = {
  running: 'blue',
  failed: 'red',
  paused: 'orange',
  draft: 'default',
  finished: 'green',
};

const layerColorMap: Record<string, string> = {
  ods: 'blue',
  dwd: 'green',
  dws: 'orange',
  ads: 'red',
};

const Dashboard: React.FC = () => {
  const { data: tasksData, loading: tasksLoading, refresh: refreshTasks } = useRequest(getSyncTasks);
  const { data: tablesData, loading: tablesLoading, refresh: refreshTables } = useRequest(getDwhTables);
  const { data: alertsData, refresh: refreshAlerts } = useRequest(() => getAlertRecords({ resolved: false }));

  const tasks = (tasksData || []) as API.SyncTask[];
  const tables = (tablesData || []) as API.DwhTableMeta[];
  const alerts = (alertsData || []) as API.AlertRecord[];

  const runningCount = tasks.filter((t) => t.status === 'running').length;
  const failedCount = tasks.filter((t) => t.status === 'failed').length;

  const odsCount = tables.filter((t) => t.layer === 'ods').length;
  const dwdCount = tables.filter((t) => t.layer === 'dwd').length;
  const dwsCount = tables.filter((t) => t.layer === 'dws').length;
  const adsCount = tables.filter((t) => t.layer === 'ads').length;

  const refreshAll = () => {
    refreshTasks();
    refreshTables();
    refreshAlerts();
  };

  const metricCard = (
    title: string,
    value: number,
    color: string,
    background: string,
    icon: React.ReactNode,
  ) => (
    <Card className="rtdwh-metric-card">
      <span className="rtdwh-metric-icon" style={{ color, background }}>{icon}</span>
      <Statistic title={title} value={value} valueStyle={{ color }} />
    </Card>
  );

  return (
    <PageContainer
      extra={[
        <Tooltip title="刷新任务、元数据和告警" key="refresh">
          <Button icon={<ReloadOutlined />} onClick={refreshAll}>刷新数据</Button>
        </Tooltip>,
      ]}
    >
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} sm={12} xl={6}>
          {metricCard('运行中任务', runningCount, '#1890ff', '#e6f7ff', <ThunderboltOutlined />)}
        </Col>
        <Col xs={24} sm={12} xl={6}>
          {metricCard('数仓表总数', tables.length, '#52c41a', '#f6ffed', <DatabaseOutlined />)}
        </Col>
        <Col xs={24} sm={12} xl={6}>
          {metricCard('未解决告警', alerts.length, '#faad14', '#fffbe6', <AlertOutlined />)}
        </Col>
        <Col xs={24} sm={12} xl={6}>
          {metricCard('失败任务', failedCount, '#ff4d4f', '#fff2f0', <CheckCircleOutlined />)}
        </Col>
      </Row>

      <Card title="任务运行状态概览" style={{ marginBottom: 16 }}>
        <Table<API.SyncTask>
          dataSource={tasks.filter((t) => t.status !== 'draft')}
          rowKey="id"
          loading={tasksLoading}
          size="small"
          pagination={false}
          columns={[
            {
              title: '任务名称',
              key: 'taskName',
              render: (_, record) => record.taskName || record.name || '—',
            },
            {
              title: '类型',
              dataIndex: 'taskType',
              key: 'taskType',
              render: (v) => {
                const map: Record<string, string> = { cdc_sync: 'CDC同步', etl: 'ETL', materialized: '物化表' };
                return map[v] || v;
              },
            },
            {
              title: '状态',
              dataIndex: 'status',
              key: 'status',
              render: (v) => <Tag color={statusColorMap[v]}>{v.toUpperCase()}</Tag>,
            },
            {
              title: '延迟(ms)',
              dataIndex: 'currentLagMs',
              key: 'lag',
              render: (v) => v ?? '—',
            },
            {
              title: '吞吐(QPS)',
              dataIndex: 'throughputQps',
              key: 'qps',
              render: (v) => v ?? '—',
            },
            {
              title: '最新 Checkpoint',
              dataIndex: 'lastCheckpointTime',
              key: 'checkpoint',
              render: (v, record) => v
                ? `#${record.checkpointCount || 0} · ${new Date(v).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
                : '—',
            },
          ]}
        />
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <Card><Statistic title="ODS 原始层" value={odsCount} valueStyle={{ color: '#1890ff' }} /></Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card><Statistic title="DWD 明细层" value={dwdCount} valueStyle={{ color: '#52c41a' }} /></Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card><Statistic title="DWS 汇总层" value={dwsCount} valueStyle={{ color: '#faad14' }} /></Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card loading={tablesLoading}><Statistic title="ADS 应用层" value={adsCount} valueStyle={{ color: '#ff4d4f' }} /></Card>
        </Col>
      </Row>
    </PageContainer>
  );
};

export default Dashboard;
