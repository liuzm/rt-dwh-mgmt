import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Table, Tag, Button, Space, Select, Input, Modal, InputNumber, Tabs, Statistic, Row, Col, message, Popconfirm, Progress, Badge } from 'antd';
import { SearchOutlined, ReloadOutlined, ToolOutlined, DeleteOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { getDwhTables, triggerCompact, triggerExpireSnapshots, getMaintenanceLogs, batchCompact, batchExpireSnapshots, cleanOrphanFiles } from '@/api';

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

const Maintenance: React.FC = () => {
  const [layerFilter, setLayerFilter] = useState<string | undefined>();
  const [keyword, setKeyword] = useState('');
  const [compactModal, setCompactModal] = useState<{ visible: boolean; tableId?: number; tableName?: string }>({ visible: false });
  const [expireModal, setExpireModal] = useState<{ visible: boolean; tableId?: number; tableName?: string; retainLast: number }>({ visible: false, retainLast: 10 });
  const [activeTab, setActiveTab] = useState('tables');

  const { data: tablesData, loading: tablesLoading, refresh: refreshTables } = useRequest(() =>
    getDwhTables({ layer: layerFilter, keyword }),
    { refreshDeps: [layerFilter, keyword] },
  );
  const { data: logsData, loading: logsLoading, refresh: refreshLogs } = useRequest(getMaintenanceLogs);

  const tables = (tablesData || []) as API.DwhTableMeta[];
  const logs = (logsData || []) as any[];

  const handleCompact = async (tableId: number, strategy: string) => {
    try {
      await triggerCompact(tableId, strategy);
      message.success('Compact 操作已触发');
      setCompactModal({ visible: false });
      refreshTables();
      refreshLogs();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleExpire = async (tableId: number, retainLast: number) => {
    try {
      await triggerExpireSnapshots(tableId, retainLast);
      message.success(`快照过期清理已触发（保留 ${retainLast} 个）`);
      setExpireModal({ visible: false, retainLast: 10 });
      refreshTables();
      refreshLogs();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleCleanOrphan = async (tableId?: number) => {
    try {
      await cleanOrphanFiles(tableId);
      message.success('孤立文件清理已触发');
      refreshLogs();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleBatchCompact = async (layer: string, threshold: number) => {
    try {
      const res = await batchCompact({ layer: layer === 'all' ? undefined : layer, fileCountThreshold: threshold });
      message.success(`批量 Compact 已触发，共 ${res?.triggered || 0} 张表`);
      refreshTables();
    } catch (e) {
      message.error('批量操作失败');
    }
  };

  const handleBatchExpire = async (layer: string, retainLast: number) => {
    try {
      const res = await batchExpireSnapshots({ layer: layer === 'all' ? undefined : layer, retainLast });
      message.success(`批量过期清理已触发，共 ${res?.triggered || 0} 张表`);
      refreshTables();
    } catch (e) {
      message.error('批量操作失败');
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
    <PageContainer>
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
                            onClick={() => setCompactModal({ visible: true, tableId: record.id, tableName: `${record.paimonDb}.${record.paimonTable}` })}
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
                  <Select placeholder="操作类型" allowClear style={{ width: 160 }} options={[
                    { label: 'Compact', value: 'compact' },
                    { label: '快照过期', value: 'expire_snapshots' },
                    { label: '孤立文件清理', value: 'clean_orphan_files' },
                  ]} />
                  <Select placeholder="触发方式" allowClear style={{ width: 140 }} options={[
                    { label: '手动触发', value: 'manual' },
                    { label: '定时任务', value: 'scheduled' },
                  ]} />
                  <Button icon={<ReloadOutlined />} onClick={refreshLogs}>刷新</Button>
                </Space>

                <Table
                  dataSource={logs}
                  rowKey="id"
                  loading={logsLoading}
                  size="small"
                  columns={[
                    { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
                    {
                      title: '表',
                      key: 'table',
                      width: 160,
                      render: (_: any, r: any) => `${r.tableName || r.paimonTable || '—'}`,
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
                      render: (v: string) => <Badge status={statusBadgeMap[v]} text={v === 'success' ? '成功' : v === 'running' ? '运行中' : '失败'} />,
                    },
                    { title: '开始时间', dataIndex: 'createdAt', key: 'start', width: 160, render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '—' },
                    { title: '耗时', dataIndex: 'durationMs', key: 'duration', width: 100, render: (v: number) => v ? `${(v / 1000).toFixed(1)}s` : '—' },
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
                    <Select id="batch-compact-layer" style={{ width: 200 }} placeholder="选择分层范围" options={[
                      { label: '全部分层', value: 'all' },
                      { label: '仅 ODS', value: 'ods' },
                      { label: '仅 DWD', value: 'dwd' },
                    ]} />
                    <InputNumber id="batch-compact-threshold" placeholder="文件数阈值" defaultValue={200} min={10} max={10000} style={{ width: 140 }} />
                    <Popconfirm title="确认批量执行 Compact？" onConfirm={() => {
                      const layer = (document.getElementById('batch-compact-layer') as HTMLSelectElement)?.value || 'all';
                      const threshold = parseInt((document.getElementById('batch-compact-threshold') as HTMLInputElement)?.value || '200');
                      handleBatchCompact(layer, threshold);
                    }}>
                      <Button type="primary" icon={<ToolOutlined />}>执行批量 Compact</Button>
                    </Popconfirm>
                  </Space>

                  <div style={{ fontWeight: 600, marginTop: 16 }}>批量快照过期清理</div>
                  <div style={{ color: '#666', fontSize: 12 }}>对所有快照数超过阈值的表执行 expire，释放存储空间</div>
                  <Space wrap>
                    <Select id="batch-expire-layer" style={{ width: 200 }} placeholder="选择分层范围" options={[
                      { label: '全部分层', value: 'all' },
                      { label: '仅 ODS', value: 'ods' },
                    ]} />
                    <InputNumber id="batch-expire-retain" placeholder="保留最近 N 个快照" defaultValue={10} min={1} max={100} style={{ width: 160 }} />
                    <Popconfirm title="确认批量执行过期清理？" onConfirm={() => {
                      const layer = (document.getElementById('batch-expire-layer') as HTMLSelectElement)?.value || 'all';
                      const retain = parseInt((document.getElementById('batch-expire-retain') as HTMLInputElement)?.value || '10');
                      handleBatchExpire(layer, retain);
                    }}>
                      <Button type="primary" danger icon={<DeleteOutlined />}>执行批量过期清理</Button>
                    </Popconfirm>
                  </Space>

                  <div style={{ fontWeight: 600, marginTop: 16 }}>批量孤立文件清理</div>
                  <div style={{ color: '#666', fontSize: 12 }}>清理 Paimon 仓库中的孤立文件（未被任何快照引用）</div>
                  <Space>
                    <Popconfirm title="确认执行孤立文件清理？" onConfirm={handleCleanOrphan}>
                      <Button type="primary" danger>执行批量孤立文件清理</Button>
                    </Popconfirm>
                  </Space>
                </Space>
              </Card>
            ),
          },
        ]}
      />

      {/* Compact Modal */}
      <Modal
        title={`触发 Compact: ${compactModal.tableName || ''}`}
        open={compactModal.visible}
        onCancel={() => setCompactModal({ visible: false })}
        onOk={() => handleCompact(compactModal.tableId!, 'minor')}
        okText="执行 Compact"
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <div style={{ fontWeight: 600 }}>Compact 策略</div>
          <Select
            defaultValue="minor"
            style={{ width: '100%' }}
            options={[
              { label: 'Minor Compact（轻量合并小文件）', value: 'minor' },
              { label: 'Full Compact（全量合并所有文件）', value: 'full' },
            ]}
          />
          <div style={{ color: '#888', fontSize: 12 }}>
            Minor: 合并小文件，减少文件数 · Full: 全量合并，优化读取性能
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
          <div style={{ color: '#888', fontSize: 12 }}>
            建议: ODS 层保留 5-10 个，DWD/DWS 层保留 3-5 个
          </div>
        </Space>
      </Modal>
    </PageContainer>
  );
};

export default Maintenance;
