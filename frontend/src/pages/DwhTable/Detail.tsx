import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Descriptions, Table, Tag, Tabs, Button, Space, Modal, Input, Skeleton, Typography, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useParams } from '@umijs/max';
import { useRequest } from '@umijs/max';
import {
  cleanOrphanFiles,
  getDwhTableColumns,
  getDwhTableDetail,
  getDwhTableSnapshots,
  getMaintenanceLogs,
  syncMetadataFromPaimon,
  triggerCompact,
  triggerExpireSnapshots,
  updateDwhColumnComment,
  updateTableBusinessDesc,
} from '@/api';

const DwhTableDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const tableId = parseInt(id || '0');
  const [editingDesc, setEditingDesc] = useState(false);
  const [descValue, setDescValue] = useState('');
  const [editingColumn, setEditingColumn] = useState<API.DwhColumnMeta>();
  const [columnComment, setColumnComment] = useState('');

  const { data: tableData, refresh: refreshTable } = useRequest(() => getDwhTableDetail(tableId));
  const { data: columnsData, refresh: refreshColumns } = useRequest(() => getDwhTableColumns(tableId));
  const { data: snapshotsData, refresh: refreshSnapshots } = useRequest(() => getDwhTableSnapshots(tableId));
  const { data: logsData, refresh: refreshLogs } = useRequest(() => getMaintenanceLogs({ tableMetaId: tableId }));

  const table = tableData as API.DwhTableMeta | undefined;
  const columns = (columnsData || []) as API.DwhColumnMeta[];
  const maintenanceLogs = (logsData || []) as API.MaintenanceLog[];
  const snapshots = (snapshotsData || []) as API.DwhSnapshot[];

  if (!table) return <PageContainer><Card><Skeleton active /></Card></PageContainer>;

  const handleCompact = async () => {
    try {
      await triggerCompact(tableId, 'minor');
      message.success('Compact 操作已触发');
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleExpireSnapshots = async () => {
    try {
      await triggerExpireSnapshots(tableId, 10);
      message.success('快照过期清理已触发');
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleUpdateDesc = async () => {
    try {
      await updateTableBusinessDesc(tableId, descValue);
      message.success('业务描述已更新');
      setEditingDesc(false);
      refreshTable();
    } catch (e) {
      message.error('更新失败');
    }
  };

  const handleUpdateColumnComment = async () => {
    if (!editingColumn) return;
    try {
      await updateDwhColumnComment(editingColumn.id, columnComment);
      message.success('字段注释已更新');
      setEditingColumn(undefined);
      refreshColumns();
    } catch (e) {
      message.error('更新字段注释失败');
    }
  };

  const handleOrphanCleanup = async () => {
    try {
      await cleanOrphanFiles(tableId);
      message.success('孤立文件清理已触发');
      refreshLogs();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleRefreshMetadata = async () => {
    try {
      await syncMetadataFromPaimon();
      await Promise.all([refreshTable(), refreshColumns(), refreshSnapshots()]);
      message.success('表元数据已刷新');
    } catch (error: any) {
      message.error(error?.message || '刷新元数据失败');
    }
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

  const formatDateTime = (value?: string | number[]) => {
    if (!value) return '—';
    if (Array.isArray(value)) {
      const [year, month, day, hour = 0, minute = 0, second = 0] = value;
      return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:${String(second).padStart(2, '0')}`;
    }
    return new Date(value).toLocaleString('zh-CN', { hour12: false });
  };

  return (
    <PageContainer
      title={`${table.paimonDb}.${table.paimonTable}`}
      subTitle="Paimon 表元数据、字段结构、快照与维护记录"
      extra={<Button icon={<ReloadOutlined />} onClick={handleRefreshMetadata}>刷新元数据</Button>}
    >
      <Tabs
        items={[
          {
            key: 'structure',
            label: '表结构',
            children: (
              <>
                <Card title="基本信息" style={{ marginBottom: 16 }}>
                  <Descriptions column={2}>
                    <Descriptions.Item label="Catalog / 数据库">
                      <Typography.Text code>rtdwh / {table.paimonDb}</Typography.Text>
                    </Descriptions.Item>
                    <Descriptions.Item label="表名"><Typography.Text strong>{table.paimonTable}</Typography.Text></Descriptions.Item>
                    <Descriptions.Item label="分层">
                      <Tag color={{
                        ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
                      }[table.layer]}>{table.layer.toUpperCase()}</Tag>
                    </Descriptions.Item>
                    <Descriptions.Item label="业务描述">
                      {editingDesc ? (
                        <Space>
                          <Input value={descValue} onChange={(e) => setDescValue(e.target.value)} style={{ width: 300 }} />
                          <Button size="small" type="primary" onClick={handleUpdateDesc}>保存</Button>
                          <Button size="small" onClick={() => setEditingDesc(false)}>取消</Button>
                        </Space>
                      ) : (
                        <Space>
                          <span>{table.businessDesc || '—'}</span>
                          <Button size="small" type="link" onClick={() => { setDescValue(table.businessDesc || ''); setEditingDesc(true); }}>编辑</Button>
                        </Space>
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="分区键">{table.partitionKeys || '—'}</Descriptions.Item>
                    <Descriptions.Item label="主键">{table.primaryKeys || '—'}</Descriptions.Item>
                    <Descriptions.Item label="快照数">{table.snapshotCount ?? '—'}</Descriptions.Item>
                    <Descriptions.Item label="最新快照">{table.latestSnapshotId ?? '—'}</Descriptions.Item>
                    <Descriptions.Item label="记录数">{table.recordCount === undefined ? '—' : table.recordCount.toLocaleString()}</Descriptions.Item>
                    <Descriptions.Item label="文件数 / 数据大小">{table.fileCount ?? '—'} · {formatSize(table.totalSizeBytes)}</Descriptions.Item>
                    <Descriptions.Item label="最近提交">{formatDateTime(table.latestCommitTime)}</Descriptions.Item>
                    <Descriptions.Item label="元数据更新时间">{formatDateTime(table.updatedAt)}</Descriptions.Item>
                  </Descriptions>
                </Card>

                <Card title="字段列表">
                  <Table<API.DwhColumnMeta>
                    dataSource={columns}
                    rowKey="id"
                    size="small"
                    columns={[
                      { title: '字段名', dataIndex: 'columnName', key: 'name' },
                      { title: '类型', dataIndex: 'columnType', key: 'type', width: 120 },
                      { title: '主键', dataIndex: 'isPk', key: 'pk', width: 70, render: (value) => value ? <Tag color="blue">PK</Tag> : '—' },
                      { title: '可为空', dataIndex: 'isNullable', key: 'nullable', width: 80, render: (value) => value ? '是' : '否' },
                      { title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 120, render: (value) => value || '—' },
                      { title: '业务注释', dataIndex: 'businessComment', key: 'comment', ellipsis: true, render: (value) => value || '—' },
                      {
                        title: '操作',
                        key: 'action',
                        width: 80,
                        render: (_, record) => (
                          <Button size="small" type="link" onClick={() => {
                            setEditingColumn(record);
                            setColumnComment(record.businessComment || record.comment || '');
                          }}>编辑注释</Button>
                        ),
                      },
                    ]}
                  />
                </Card>
              </>
            ),
          },
          {
            key: 'maintenance',
            label: '表维护',
            children: (
              <Card>
                <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" onClick={handleCompact}>触发 Compact</Button>
                  <Button type="primary" danger onClick={handleExpireSnapshots}>过期快照清理</Button>
                  <Button onClick={handleOrphanCleanup}>清理孤立文件</Button>
                </Space>
                <Table<API.MaintenanceLog>
                  dataSource={maintenanceLogs}
                  rowKey="id"
                  columns={[
                    { title: '时间', dataIndex: 'startedAt', key: 'time', render: formatDateTime },
                    { title: '操作', dataIndex: 'operation', key: 'op', render: (value) => ({
                      compact: 'Compact', expire_snapshots: '过期快照', orphan_cleanup: '孤立文件清理',
                    }[value as string] || value) },
                    { title: '触发方式', dataIndex: 'triggerType', key: 'trigger' },
                    { title: '状态', dataIndex: 'status', key: 'status', render: (value) => <Tag color={{
                      success: 'green', failed: 'red', running: 'blue', pending: 'orange',
                    }[value as string]}>{value}</Tag> },
                    { title: '耗时(ms)', dataIndex: 'durationMs', key: 'duration', render: (v: number) => v ?? '—' },
                    { title: '详情', key: 'detail', ellipsis: true,
                      render: (_, record) => record.errorMsg || record.sqlContent || '—' },
                  ]}
                  locale={{ emptyText: '暂无维护操作日志' }}
                />
              </Card>
            ),
          },
          {
            key: 'snapshots',
            label: '快照历史',
            children: <Card><Table<API.DwhSnapshot>
              dataSource={snapshots}
              rowKey="snapshotId"
              pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个快照` }}
              locale={{ emptyText: '当前表尚未生成快照' }}
              columns={[
                { title: '快照 ID', dataIndex: 'snapshotId', width: 110 },
                { title: 'Schema ID', dataIndex: 'schemaId', width: 110 },
                { title: '提交类型', dataIndex: 'commitKind', width: 120,
                  render: (value) => <Tag color={value === 'APPEND' ? 'green' : 'blue'}>{value}</Tag> },
                { title: '提交时间', dataIndex: 'commitTime', render: formatDateTime },
                { title: '总记录数', dataIndex: 'recordCount', align: 'right',
                  render: (value) => Number(value || 0).toLocaleString() },
                { title: '增量记录', dataIndex: 'deltaRecordCount', align: 'right',
                  render: (value) => Number(value || 0).toLocaleString() },
                { title: 'Manifest 大小', dataIndex: 'manifestSizeBytes', align: 'right', render: formatSize },
              ]}
            /></Card>,
          },
        ]}
      />
      <Modal
        title={`编辑字段注释：${editingColumn?.columnName || ''}`}
        open={Boolean(editingColumn)}
        onCancel={() => setEditingColumn(undefined)}
        onOk={handleUpdateColumnComment}
        okText="保存"
      >
        <Input.TextArea
          rows={4}
          value={columnComment}
          onChange={(event) => setColumnComment(event.target.value)}
          placeholder="请输入字段的业务含义"
        />
      </Modal>
    </PageContainer>
  );
};

export default DwhTableDetail;
