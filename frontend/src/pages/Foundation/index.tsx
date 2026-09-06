import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Button, Card, Empty, Input, List, Progress, Space, Table, Tag, Typography, message,
} from 'antd';
import {
  AuditOutlined, BellOutlined, ClockCircleOutlined, DatabaseOutlined, ReloadOutlined,
  RightOutlined, SafetyCertificateOutlined, SearchOutlined, ThunderboltOutlined,
} from '@ant-design/icons';
import { history, useRequest } from '@umijs/max';
import { getFoundationSummary, searchFoundation } from '@/api';
import './index.less';

const iconMap = {
  asset: <DatabaseOutlined />,
  security: <SafetyCertificateOutlined />,
  quality: <ThunderboltOutlined />,
  observability: <BellOutlined />,
  audit: <AuditOutlined />,
};
const typeLabel: Record<API.FoundationSearchItem['type'], string> = {
  table: '数据表', task: '任务', report: '报表', data_service: '数据接口',
};
const typeColor: Record<API.FoundationSearchItem['type'], string> = {
  table: 'blue', task: 'geekblue', report: 'purple', data_service: 'cyan',
};
const statusColor = { healthy: 'success', attention: 'warning', risk: 'error' } as const;
const statusText = { healthy: '健康', attention: '需关注', risk: '有风险' } as const;
const riskMetricPattern = /失败|风险|异常|告警|缺少|逾期/;
const formatMinutes = (value: number) => value >= 1440
  ? `${(value / 1440).toFixed(1)} 天` : value >= 60 ? `${(value / 60).toFixed(1)} 小时` : `${value} 分钟`;
const formatGeneratedAt = (value?: unknown) => {
  if (!value) return '等待刷新';
  const date = Array.isArray(value)
    ? new Date(Number(value[0]), Number(value[1]) - 1, Number(value[2]), Number(value[3] || 0), Number(value[4] || 0), Number(value[5] || 0))
    : new Date(String(value));
  return Number.isNaN(date.getTime()) ? '刚刚更新' : date.toLocaleTimeString('zh-CN');
};

const Foundation: React.FC = () => {
  const summaryRequest = useRequest(getFoundationSummary, { pollingInterval: 30000 });
  const summary = summaryRequest.data as API.FoundationSummary | undefined;
  const [results, setResults] = useState<API.FoundationSearchItem[]>([]);
  const [searched, setSearched] = useState(false);
  const searchRequest = useRequest((keyword: string) => searchFoundation(keyword), { manual: true });

  const search = async (keyword: string) => {
    if (keyword.trim().length < 2) { message.warning('请输入至少 2 个字符'); return; }
    setResults(await searchRequest.run(keyword.trim()));
    setSearched(true);
  };

  const score = summary?.overallScore || 0;
  const capabilities = summary?.capabilities || [];
  const slaRisks = summary?.slaRisks || [];
  const totalRisks = capabilities.reduce((total, item) => total + item.riskCount, 0);
  const scoreTone = score >= 80 ? 'healthy' : score >= 60 ? 'attention' : 'risk';

  return (
    <PageContainer
      title="资产检索与治理"
      subTitle="统一检索、权限、质量 SLA、可观测与审计"
      className="foundation-page"
      extra={(
        <Button icon={<ReloadOutlined />} loading={summaryRequest.loading} onClick={() => summaryRequest.refresh()}>
          刷新
        </Button>
      )}
    >
      <Alert
        showIcon
        type="info"
        className="foundation-guide"
        message={(
          <span>
            仅展示当前账号有权访问的数据资产
            <small>能力分用于定位治理缺口，SLA 风险仅统计已启用周期调度的数据资源</small>
          </span>
        )}
      />

      <Card className="foundation-overview-card" bordered={false}>
        <div className="foundation-overview-layout">
          <section className="foundation-score-panel">
            <Progress
              type="dashboard"
              percent={score}
              width={108}
              gapDegree={72}
              strokeWidth={10}
              strokeColor={scoreTone === 'healthy' ? '#52c41a' : scoreTone === 'attention' ? '#faad14' : '#ff4d4f'}
            />
            <div className="foundation-score-copy">
              <Typography.Text strong>资产治理参考分</Typography.Text>
              <small>{scoreTone === 'healthy' ? '整体运行健康' : scoreTone === 'attention' ? '存在待改进项' : '建议优先处理风险'}</small>
              <div className="foundation-score-tags">
                <span>{capabilities.length || 5} 项能力</span>
                <span className={totalRisks ? 'is-risk' : ''}>{totalRisks} 项风险</span>
              </div>
            </div>
          </section>

          <section className="foundation-search-panel">
            <div className="foundation-search-heading">
              <span>
                <b>统一检索与资产发现</b>
                <small>快速定位数据表、任务、报表和数据接口</small>
              </span>
              {searched && <Tag color="blue">找到 {results.length} 项</Tag>}
            </div>
            <Input.Search
              allowClear
              enterButton={<><SearchOutlined /> 全局检索</>}
              placeholder="输入至少 2 个字符进行检索"
              loading={searchRequest.loading}
              onSearch={search}
            />
            {searched && (
              <div className="foundation-search-results">
                {results.length ? (
                  <List
                    size="small"
                    dataSource={results}
                    renderItem={(item) => (
                      <List.Item
                        actions={[
                          <Button key="open" type="link" size="small" onClick={() => history.push(item.path)}>
                            打开 <RightOutlined />
                          </Button>,
                        ]}
                      >
                        <List.Item.Meta
                          title={(
                            <Space size={6} wrap>
                              <Tag color={typeColor[item.type]}>{typeLabel[item.type]}</Tag>
                              <span>{item.title}</span>
                              <Tag>{item.status}</Tag>
                            </Space>
                          )}
                          description={item.subtitle}
                        />
                      </List.Item>
                    )}
                  />
                ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有找到匹配资源" />}
              </div>
            )}
          </section>
        </div>
      </Card>

      <div className="foundation-section-heading">
        <span><b>五类公共基础能力</b><small>点击卡片进入对应模块处理治理问题</small></span>
        <span className="foundation-updated-at">
          <ClockCircleOutlined /> {formatGeneratedAt(summary?.generatedAt)}
        </span>
      </div>

      <div className="foundation-capability-grid">
        {capabilities.map((item) => {
          const clickable = item.path !== '/foundation';
          return (
            <Card
              key={item.key}
              size="small"
              className={`foundation-capability-card is-${item.status} ${clickable ? 'is-clickable' : ''}`}
              onClick={() => clickable && history.push(item.path)}
            >
              <div className="foundation-capability-header">
                <div className="foundation-capability-identity">
                  <span className="foundation-capability-icon">{iconMap[item.key]}</span>
                  <span>
                    <b>{item.name}</b>
                    <small>{item.description}</small>
                  </span>
                </div>
                <div className="foundation-capability-score">
                  <Tag color={statusColor[item.status]}>{statusText[item.status]}</Tag>
                  <b>{item.score}</b><small>分</small>
                  {clickable && <RightOutlined />}
                </div>
              </div>
              <div className="foundation-metrics">
                {Object.entries(item.metrics).map(([label, value]) => (
                  <div className={riskMetricPattern.test(label) && value ? 'is-risk' : ''} key={label}>
                    <span>{label}</span>
                    <b>{value}</b>
                  </div>
                ))}
              </div>
            </Card>
          );
        })}
      </div>

      <Card
        size="small"
        className="foundation-sla-card"
        title={<Space size={8}><ClockCircleOutlined /><span>数据资源 SLA 风险</span></Space>}
        extra={<Tag color={slaRisks.length ? 'error' : 'success'}>{slaRisks.length ? `${slaRisks.length} 项待处理` : '当前无风险'}</Tag>}
      >
        <Table
          rowKey="outputId"
          size="small"
          loading={summaryRequest.loading}
          dataSource={slaRisks}
          pagination={slaRisks.length > 10 ? { pageSize: 10, size: 'small' } : false}
          scroll={{ x: 980 }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有逾期的数据资源" /> }}
          columns={[
            { title: '数据资源', dataIndex: 'qualifiedName', ellipsis: true },
            { title: '分层', dataIndex: 'layer', width: 80, render: (value) => <Tag color="blue">{String(value).toUpperCase()}</Tag> },
            { title: '负责人', dataIndex: 'owner', width: 120, render: (value) => value || <Tag color="warning">未指定</Tag> },
            { title: 'SLA', dataIndex: 'slaMinutes', width: 110, render: formatMinutes },
            { title: '最近产出', dataIndex: 'lastProducedAt', width: 180, render: (value) => value ? new Date(value).toLocaleString('zh-CN') : '尚未产出' },
            { title: '已逾期', dataIndex: 'overdueMinutes', width: 120, render: (value) => <b className="foundation-overdue">{formatMinutes(value)}</b> },
            { title: '级别', dataIndex: 'severity', width: 100, render: (value) => <Tag color={value === 'critical' ? 'error' : value === 'high' ? 'orange' : 'warning'}>{value}</Tag> },
            { title: '处理', width: 100, render: () => <Button type="link" size="small" onClick={() => history.push('/sync-task/workflow')}>查看任务</Button> },
          ]}
        />
      </Card>
    </PageContainer>
  );
};

export default Foundation;
