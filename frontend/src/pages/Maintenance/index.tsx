import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Card, Table, Tag, Button, Space, Select, Input, Modal, InputNumber, Tabs, Statistic, Row, Col, message, Popconfirm, Progress, Badge } from 'antd';
import { SearchOutlined, ReloadOutlined, ToolOutlined, DeleteOutlined } from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import { getDwhTables, triggerCompact, triggerExpireSnapshots, getMaintenanceLogs, batchCompact, batchExpireSnapshots, cleanOrphanFiles } from '@/api';
import RecoveryDrawer, { cleanupStatus, maintenanceStatus } from './RecoveryDrawer';
import { formatBackendDateTime } from '@/utils/backendDateTime';

const layerColorMap: Record<string, string> = {
  ods: 'blue',
  dwd: 'green',
  dws: 'orange',
  ads: 'red',
};

const formatSize = (bytes?: number) => {
  if (bytes === undefined || bytes === null) return '—';
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let size = bytes;
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++; }
  return `${size.toFixed(1)} ${units[i]}`;
};

const showOperationResult = (result: any) => {
  const value = result?.data ?? result;
  const text = value?.message || '维护请求已记录，请查看执行日志';
  if (value?.status === 'success') message.success(text);
  else if (value?.status === 'failed') message.error(text);
  else if (['unknown', 'timed_out', 'pending'].includes(value?.status)) message.warning(text);
  else message.info(text);
};

const Maintenance: React.FC = () => {
  const access = useAccess();
  const [recoveryId, setRecoveryId] = useState<number>();
  const [logOperation, setLogOperation] = useState<string>();
  const [logStatus, setLogStatus] = useState<string>();
  const [layerFilter, setLayerFilter] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const [compactModal, setCompactModal] = useState<{ visible: boolean; tableId?: number; tableName?: string }>({ visible: false });
  const [compactStrategy, setCompactStrategy] = useState<'minor' | 'full'>('minor');
  const [expireModal, setExpireModal] = useState<{ visible: boolean; tableId?: number; tableName?: string; retainLast: number }>({ visible: false, retainLast: 10 });
  const [batchCompactLayer, setBatchCompactLayer] = useState('all');
  const [batchCompactThreshold, setBatchCompactThreshold] = useState(200);
  const [batchExpireLayer, setBatchExpireLayer] = useState('all');
  const [batchExpireRetain, setBatchExpireRetain] = useState(10);
  const [activeTab, setActiveTab] = useState('tables');

  const { data: tablesData, loading: tablesLoading, refresh: refreshTables } = useRequest(() =>
    getDwhTables({ layer: layerFilter, keyword }),
    { refreshDeps: [layerFilter, keyword] },
  );
  const { data: logsData, loading: logsLoading, refresh: refreshLogs } = useRequest(() => getMaintenanceLogs({ operation: logOperation, status: logStatus }), { pollingInterval: 5000, refreshDeps: [logOperation, logStatus] });

  const tables = (tablesData || []) as API.DwhTableMeta[];
  const logs = (logsData || []) as any[];

  const handleCompact = async (tableId: number, strategy: string) => {
    try {
      const result = await triggerCompact(tableId, strategy);
      showOperationResult(result);
      setCompactModal({ visible: false });
      setCompactStrategy('minor');
      refreshTables();
      refreshLogs();
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    }
  };

  const handleExpire = async (tableId: number, retainLast: number) => {
    try {
      const result = await triggerExpireSnapshots(tableId, retainLast);
      showOperationResult(result);
      setExpireModal({ visible: false, retainLast: 10 });
      refreshTables();
      refreshLogs();
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    }
  };

  const handleCleanOrphan = async (tableId?: number) => {
    try {
      const result = await cleanOrphanFiles(tableId);
      message.info(`清理请求已记录：已受理 ${result?.triggered || 0}，未受理或待协调 ${result?.failed || 0}，请查看执行日志`);
      refreshLogs();
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    }
  };

  const handleBatchCompact = async (layer: string, threshold: number) => {
    try {
      const res = await batchCompact({ layer: layer === 'all' ? undefined : layer, fileCountThreshold: threshold });
      message.info(`批量 Compact：已受理 ${res?.triggered || 0}，未受理或待协调 ${res?.failed || 0}，请查看执行日志`);
      refreshTables();
    } catch (e: any) {
      message.error(e?.message || '批量操作失败');
    }
  };

  const handleBatchExpire = async (layer: string, retainLast: number) => {
    try {
      const res = await batchExpireSnapshots({ layer: layer === 'all' ? undefined : layer, retainLast });
      message.info(`批量过期清理：已受理 ${res?.triggered || 0}，未受理或待协调 ${res?.failed || 0}，请查看执行日志`);
      refreshTables();
    } catch (e: any) {
      message.error(e?.message || '批量操作失败');
    }
  };

  const getCompactStatus = (fileCount?: number) => {
    if (!fileCount) return { level: 'unknown', percent: 0 };
    if (fileCount < 50) return { level: 'good', percent: 90 };
    if (fileCount < 200) return { level: 'normal', percent: 60 };
    return { level: 'urgent', percent: 30 };
  };

  const statusBadgeMap: Record<string, 'success' | 'warning' | 'error' | 'processing' | 'default'> = {
    success: 'success',
    running: 'processing',
    failed: 'error',
    normal: 'warning',
    urgent: 'error',
    good: 'success',
    unknown: 'default',
  };

  const opMap: Record<string, { color: string; label: string }> = {
    compact: { color: 'blue', label: 'Compact' },
    expire_snapshots: { color: 'orange', label: '快照过期' },
    orphan_cleanup: { color: 'red', label: '孤立文件清理' },
  };

  const triggerTypeMap: Record<string, string> = {
    manual: '手动',
    scheduled: '定时',
    auto: '自动',
  };

  return (
    <PageContainer className="rtdwh-evidence-surface"
      title="Paimon 存储维护"
      subTitle="管理小文件合并、快照回溯窗口与孤立文件清理"
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="维护对象是物理文件与历史快照，不会自动定义业务数据保留期"
        description="Compact 优化文件布局；Snapshot Expire 释放不再被保留快照引用的文件；业务分区到期需要独立生命周期策略。"
      />
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'tables',
            label: '表维护概览',
            children: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Input
                    placeholder="搜索表名"
                    prefix={<SearchOutlined />}
                    value={keyword}
                    onChange={(e) => setKeyword(e.target.value)}
                    style={{ width: 200 }}
                  />
                  <Select
                    placeholder="筛选分层"
                    allowClear
                    onChange={setLayerFilter}
                    style={{ width: 140 }}
                    options={[
                      { label: 'ODS', value: 'ods' },
                      { label: 'DWD', value: 'dwd' },
                      { label: 'DWS', value: 'dws' },
                      { label: 'ADS', value: 'ads' },
                    ]}
                  />
                  <Button icon={<ReloadOutlined />} onClick={refreshTables}>刷新</Button>
                </Space>

                <Table<API.DwhTableMeta>
                  dataSource={tables}
                  rowKey="id"
                  loading={tablesLoading}
                  columns={[
                    { title: '库名', dataIndex: 'paimonDb', key: 'db', width: 100 },
                    { title: '表名', dataIndex: 'paimonTable', key: 'table', width: 180 },
                    {
                      title: '分层',
                      dataIndex: 'layer',
                      key: 'layer',
                      width: 80,
                      render: (v) => <Tag color={layerColorMap[v]}>{v.toUpperCase()}</Tag>,
                    },
                    {
                      title: '文件数',
                      dataIndex: 'fileCount',
                      key: 'files',
                      width: 100,
                      render: (v) => v ?? '—',
                    },
                    {
                      title: '数据大小',
                      dataIndex: 'totalSizeBytes',
                      key: 'size',
                      width: 110,
                      render: (v) => formatSize(v),
                    },
                    {
                      title: '快照数',
                      dataIndex: 'snapshotCount',
                      key: 'snapshots',
                      width: 90,
                      render: (v) => v ?? '—',
                    },
                    {
                      title: 'Compact 状态',
                      key: 'compact',
                      width: 150,
                      render: (_, record) => {
                        const cs = getCompactStatus(record.fileCount);
                        return (
                          <Space>
                            <Progress
                              percent={cs.percent}
                              size="small"
                              status={cs.level === 'urgent' ? 'exception' : cs.level === 'good' ? 'success' : 'normal'}
                              style={{ width: 80 }}
                            />
                            <Badge status={statusBadgeMap[cs.level]} text={cs.level === 'good' ? '良好' : cs.level === 'urgent' ? '需 Compact' : '正常'} />
                          </Space>
                        );
                      },
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 260,
                      render: (_, record) => (
                        <Space>
                          <Button
                            size="small"
                            type="primary"
                            icon={<ToolOutlined />}
                            onClick={() => {
                              setCompactStrategy('minor');
                              setCompactModal({ visible: true, tableId: record.id, tableName: `${record.paimonDb}.${record.paimonTable}` });
                            }}
                          >
                            Compact
                          </Button>
                          <Button
                            size="small"
                            icon={<DeleteOutlined />}
                            onClick={() => setExpireModal({ visible: true, tableId: record.id, tableName: `${record.paimonDb}.${record.paimonTable}`, retainLast: 10 })}
                          >
                            过期清理
                          </Button>
                          <Button size="small" type="link" danger onClick={() => handleCleanOrphan(record.id)}>清理孤立文件</Button>
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'logs',
            label: '维护操作日志',
            children: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Select placeholder="操作类型" allowClear value={logOperation} onChange={setLogOperation} style={{ width: 160 }} options={[
                    { label: 'Compact', value: 'compact' },
                    { label: '快照过期', value: 'expire_snapshots' },
                    { label: '孤立文件清理', value: 'orphan_cleanup' },
                  ]} />
                  <Select placeholder="执行状态" allowClear value={logStatus} onChange={setLogStatus} style={{ width: 180 }} options={Object.entries(maintenanceStatus).map(([value, label]) => ({ value, label }))} />
                  <Button icon={<ReloadOutlined />} onClick={refreshLogs}>刷新</Button>
                </Space>

                <Table
                  dataSource={logs}
                  rowKey="id"
                  loading={logsLoading}
                  size="small"
                  scroll={{ x: 1200 }}
                  columns={[
                    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
                    {
                      title: '表',
                      key: 'table',
                      width: 160,
                      render: (_: any, r: any) => r.contractOrigin === 'bound_v1' ? `${r.databaseName}.${r.tableName}` : '历史目标未认证',
                    },
                    {
                      title: '操作',
                      dataIndex: 'operation',
                      key: 'op',
                      width: 120,
                      render: (v: string) => {
                        const m = opMap[v] || { color: 'default', label: v };
                        return <Tag color={m.color}>{m.label}</Tag>;
                      },
                    },
                    {
                      title: '触发方式',
                      dataIndex: 'triggerType',
                      key: 'trigger',
                      width: 100,
                      render: (v: string) => triggerTypeMap[v] || v,
                    },
                    {
                      title: '状态',
                      dataIndex: 'status',
                      key: 'status',
                      width: 100,
                      render: (v: string) => <Badge status={v === 'success' ? 'success' : v === 'failed' ? 'error' : v === 'running' ? 'processing' : 'warning'} text={maintenanceStatus[v] || '未知'} />,
                    },
                    { title: '会话清理', dataIndex: 'cleanupStatus', width: 165, render: (v: string) => cleanupStatus[v] || v },
                    { title: '开始时间', dataIndex: 'startedAt', key: 'start', width: 170, render: formatBackendDateTime },
                    { title: '耗时', dataIndex: 'durationMs', key: 'duration', width: 100, render: (v: number) => v ? `${(v / 1000).toFixed(1)}s` : '—' },
                    { title: '恢复', key: 'recovery', width: 120, fixed: 'right', render: (_: any, r: any) => <Button size="small" onClick={() => setRecoveryId(r.id)}>证据与处置</Button> },
                  ]}
                />
              </Card>
            ),
          },
          {
            key: 'batch',
            label: '批量维护',
            children: (
              <Card title="批量维护操作">
                <Row gutter={16} style={{ marginBottom: 16 }}>
                  <Col span={8}>
                    <Card size="small">
                      <Statistic title="ODS 层表数" value={tables.filter((t: any) => t.layer === 'ods').length} suffix="表" />
                    </Card>
                  </Col>
                  <Col span={8}>
                    <Card size="small">
                      <Statistic title="需要 Compact" value={tables.filter((t: any) => (t.fileCount || 0) > 200).length} suffix="表" valueStyle={{ color: '#ff4d4f' }} />
                    </Card>
                  </Col>
                  <Col span={8}>
                    <Card size="small">
                      <Statistic title="快照 > 10" value={tables.filter((t: any) => (t.snapshotCount || 0) > 10).length} suffix="表" valueStyle={{ color: '#faad14' }} />
                    </Card>
                  </Col>
                </Row>

                <Space direction="vertical" style={{ width: '100%' }}>
                  <div style={{ fontWeight: 600 }}>批量 Compact</div>
                  <div style={{ color: '#666', fontSize: 12 }}>对文件数超过阈值的表执行 minor compact，降低读取开销</div>
                  <Space wrap>
                    <Select style={{ width: 200 }} value={batchCompactLayer} onChange={setBatchCompactLayer} options={[
                      { label: '全部分层', value: 'all' },
                      { label: '仅 ODS', value: 'ods' },
                      { label: '仅 DWD', value: 'dwd' },
                      { label: '仅 DWS', value: 'dws' },
                      { label: '仅 ADS', value: 'ads' },
                    ]} />
                    <InputNumber placeholder="文件数阈值" value={batchCompactThreshold}
                      onChange={(value) => setBatchCompactThreshold(value || 200)} min={10} max={10000} style={{ width: 140 }} />
                    <Popconfirm title="确认批量执行 Compact？"
                      description={`范围：${batchCompactLayer.toUpperCase()}，文件数阈值：${batchCompactThreshold}`}
                      onConfirm={() => handleBatchCompact(batchCompactLayer, batchCompactThreshold)}>
                      <Button type="primary" icon={<ToolOutlined />}>执行批量 Compact</Button>
                    </Popconfirm>
                  </Space>

                  <div style={{ fontWeight: 600, marginTop: 16 }}>批量快照过期清理</div>
                  <div style={{ color: '#666', fontSize: 12 }}>保留最近 N 个快照并清理更早的无引用文件；不删除仍属于当前快照的业务数据</div>
                  <Space wrap>
                    <Select style={{ width: 200 }} value={batchExpireLayer} onChange={setBatchExpireLayer} options={[
                      { label: '全部分层', value: 'all' },
                      { label: '仅 ODS', value: 'ods' },
                      { label: '仅 DWD', value: 'dwd' },
                      { label: '仅 DWS', value: 'dws' },
                      { label: '仅 ADS', value: 'ads' },
                    ]} />
                    <InputNumber placeholder="保留最近 N 个快照" value={batchExpireRetain}
                      onChange={(value) => setBatchExpireRetain(value || 10)} min={1} max={100} style={{ width: 160 }} />
                    <Popconfirm title="确认批量执行过期清理？"
                      description={`范围：${batchExpireLayer.toUpperCase()}，保留最近 ${batchExpireRetain} 个快照`}
                      onConfirm={() => handleBatchExpire(batchExpireLayer, batchExpireRetain)}>
                      <Button type="primary" danger icon={<DeleteOutlined />}>执行批量过期清理</Button>
                    </Popconfirm>
                  </Space>

                  <div style={{ fontWeight: 600, marginTop: 16 }}>批量孤立文件清理</div>
                  <div style={{ color: '#666', fontSize: 12 }}>清理 Paimon 仓库中的孤立文件（未被任何快照引用）</div>
                  <Space>
                    <Popconfirm title="确认执行孤立文件清理？" onConfirm={() => handleCleanOrphan()}>
                      <Button type="primary" danger>执行批量孤立文件清理</Button>
                    </Popconfirm>
                  </Space>
                </Space>
              </Card>
            ),
          },
        ]}
      />

      <RecoveryDrawer id={recoveryId} canManage={access.canManageDwh} onClose={() => setRecoveryId(undefined)} onChanged={refreshLogs} />
      {/* Compact Modal */}
      <Modal
        title={`触发 Compact: ${compactModal.tableName || ''}`}
        open={compactModal.visible}
        onCancel={() => { setCompactModal({ visible: false }); setCompactStrategy('minor'); }}
        onOk={() => handleCompact(compactModal.tableId!, compactStrategy)}
        okText="执行 Compact"
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <div style={{ fontWeight: 600 }}>Compact 策略</div>
          <Select
            value={compactStrategy}
            onChange={setCompactStrategy}
            style={{ width: '100%' }}
            options={[
              { label: 'Minor Compact（轻量合并小文件）', value: 'minor' },
              { label: 'Full Compact（全量合并所有文件）', value: 'full' },
            ]}
          />
          <div style={{ color: '#888', fontSize: 12 }}>
            Minor：日常合并小文件；Full：全量重写开销较大，建议只在明确需要时执行
          </div>
        </Space>
      </Modal>

      {/* Expire Snapshots Modal */}
      <Modal
        title={`快照过期清理: ${expireModal.tableName || ''}`}
        open={expireModal.visible}
        onCancel={() => setExpireModal({ visible: false, retainLast: 10 })}
        onOk={() => handleExpire(expireModal.tableId!, expireModal.retainLast)}
        okText="执行清理"
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <div style={{ fontWeight: 600 }}>保留最近 N 个快照</div>
          <InputNumber
            value={expireModal.retainLast}
            onChange={(v) => setExpireModal((m) => ({ ...m, retainLast: v || 10 }))}
            min={1}
            max={100}
            style={{ width: '100%' }}
          />
          <Alert
            type="warning"
            showIcon
            message="快照数不是业务数据保留天数"
            description="保留过少可能影响长查询和流任务从历史快照恢复。长期审计节点请先建立 Tag，业务数据到期请使用独立分区生命周期策略。"
          />
        </Space>
      </Modal>
    </PageContainer>
  );
};

export default Maintenance;
