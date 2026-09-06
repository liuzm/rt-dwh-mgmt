import React from 'react';
import { Alert, Button, Card, Col, Progress, Row, Space, Statistic, Tag, Tooltip, Typography } from 'antd';
import {
  AppstoreOutlined,
  CloudServerOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import { getFlinkClusterCapacity } from '@/api';
import './flink-capacity.less';

const providerLabels: Record<string, string> = {
  none: '未配置容量执行器',
  standalone: 'Standalone 集群',
  external: '外部资源提供方',
  'kubernetes-native': 'Native Kubernetes',
  'flink-kubernetes-operator': 'Flink Kubernetes Operator',
};

const statusMeta: Record<string, { color: string; label: string }> = {
  healthy: { color: 'success', label: '容量正常' },
  ready: { color: 'success', label: '容量正常' },
  available: { color: 'success', label: '容量正常' },
  degraded: { color: 'warning', label: '容量受限' },
  unhealthy: { color: 'error', label: '集群异常' },
  unreachable: { color: 'error', label: '无法连接' },
  unknown: { color: 'default', label: '状态未知' },
};

const formatTime = (value?: string) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '—';

const FlinkCapacityCard: React.FC = () => {
  const access = useAccess();
  const { data, error, loading, refresh } = useRequest(getFlinkClusterCapacity, {
    pollingInterval: 15000,
    pollingWhenHidden: false,
  });
  const capacity = data as API.FlinkClusterCapacity | undefined;
  const status = statusMeta[capacity?.status || 'unknown'] || {
    color: 'default',
    label: capacity?.status || '状态未知',
  };
  const slotsTotal = capacity?.slotsTotal ?? 0;
  const slotsUsed = capacity?.slotsUsed ?? Math.max(0, slotsTotal - (capacity?.slotsAvailable ?? 0));
  const calculatedUtilization = slotsTotal > 0 ? (slotsUsed / slotsTotal) * 100 : 0;
  const utilization = Math.min(100, Math.max(0, Number(
    Number.isFinite(capacity?.slotUtilization)
      ? capacity?.slotUtilization
      : calculatedUtilization,
  )));
  const providerLabel = providerLabels[capacity?.provider || 'none'] || capacity?.provider || '未知';

  return (
    <Card
      className="settings-section-card flink-capacity-card"
      title={<Space><CloudServerOutlined />Flink 实时容量</Space>}
      extra={(
        <Space>
          {capacity && <Tag color={status.color}>{status.label}</Tag>}
          {access.canManageSettings ? (
            <Tooltip title="重新读取 Flink 集群容量">
              <Button icon={<ReloadOutlined />} loading={loading} onClick={refresh}>刷新容量</Button>
            </Tooltip>
          ) : <Tag>只读</Tag>}
        </Space>
      )}
      loading={loading && !capacity}
    >
      {error && !capacity ? (
        <Alert
          showIcon
          type="error"
          message="Flink 容量读取失败"
          description={error.message || '暂时无法读取 TaskManager 与 Slot 信息'}
          action={access.canManageSettings ? <Button size="small" onClick={refresh}>重试</Button> : undefined}
        />
      ) : capacity ? (
        <>
          <Row gutter={[12, 12]}>
            <Col xs={12} lg={6}>
              <div className="flink-capacity-metric">
                <span className="flink-capacity-icon is-blue"><CloudServerOutlined /></span>
                <Statistic title="TaskManager" value={capacity.currentTaskManagers} suffix="个" />
              </div>
            </Col>
            <Col xs={12} lg={6}>
              <div className="flink-capacity-metric">
                <span className="flink-capacity-icon is-purple"><AppstoreOutlined /></span>
                <Statistic title="Slot 使用" value={slotsUsed} suffix={`/ ${slotsTotal}`} />
                <Progress
                  className="flink-capacity-progress"
                  percent={utilization}
                  size="small"
                  status={utilization >= 90 ? 'exception' : 'normal'}
                  showInfo={false}
                />
              </div>
            </Col>
            <Col xs={12} lg={6}>
              <div className="flink-capacity-metric">
                <span className="flink-capacity-icon is-green"><ThunderboltOutlined /></span>
                <Statistic title="可用 Slot" value={capacity.slotsAvailable} suffix="个" />
              </div>
            </Col>
            <Col xs={12} lg={6}>
              <div className="flink-capacity-metric">
                <span className="flink-capacity-icon is-orange"><ThunderboltOutlined /></span>
                <Statistic title="运行作业" value={capacity.runningJobs} suffix="个" />
              </div>
            </Col>
          </Row>

          <div className="flink-capacity-summary">
            <div>
              <span>容量提供方</span>
              <strong>{providerLabel}</strong>
            </div>
            <div>
              <span>Adaptive Scheduler</span>
              <Tag color={capacity.adaptiveScheduler ? 'success' : 'default'}>
                {capacity.adaptiveScheduler ? '已启用' : '未启用'}
              </Tag>
            </div>
            <div>
              <span>作业动态调整</span>
              <Tag color={capacity.jobRescalingSupported ? 'processing' : 'default'}>
                {capacity.jobRescalingSupported ? '支持' : '不支持'}
              </Tag>
            </div>
            <div>
              <span>自动扩容</span>
              <Tag color={capacity.autoExpansionSupported ? 'success' : 'default'}>
                {capacity.autoExpansionSupported ? '可用' : '只读观测'}
              </Tag>
            </div>
          </div>

          {!capacity.autoExpansionSupported && (
            <Alert
              className="flink-capacity-alert"
              showIcon
              type="info"
              message="当前平台仅观测 TaskManager 容量"
              description={capacity.reason || '当前部署未配置受控容量执行器，TaskManager 扩缩需由集群运维侧完成。作业并行度调整能力会在具体任务页面单独展示。'}
            />
          )}

          <div className="flink-capacity-footer">
            <Typography.Text type="secondary">
              Slot 利用率 {utilization.toFixed(1)}% · 观测时间 {formatTime(capacity.observedAt)}
            </Typography.Text>
          </div>
        </>
      ) : null}
    </Card>
  );
};

export default FlinkCapacityCard;
