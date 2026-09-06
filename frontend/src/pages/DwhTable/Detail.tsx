import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Descriptions, Table, Tag, Tabs, Button, Space, Modal, Input, Skeleton, Typography, message, Form, Select, Alert } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useAccess, useParams, useRequest, useSearchParams } from '@umijs/max';
import {
  cleanOrphanFiles,
  getDataAsset,
  getDwhTableColumns,
  getDwhTableDetail,
  getDwhTableSnapshots,
  getMaintenanceLogs,
  syncMetadataFromPaimon,
  triggerCompact,
  triggerExpireSnapshots,
  updateDwhColumnComment,
  updateTableMetadata,
} from '@/api';

import './asset.less';
import ViewDetail from './ViewDetail';
import { AssetContextPanel, AssetSchemaHistory, assetTypeLabel } from './AssetPanels';

const DwhTableDetail: React.FC = () => {
  const access = useAccess();
  const { id, assetId } = useParams<{ id: string; assetId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const [editingDesc, setEditingDesc] = useState(false);
  const [descValue, setDescValue] = useState('');
  const [editingColumn, setEditingColumn] = useState<API.DwhColumnMeta>();
  const [columnComment, setColumnComment] = useState('');
  const [metadataOpen, setMetadataOpen] = useState(false);
  const [metadataForm] = Form.useForm();

  const { data: tableData, error: tableError, refresh: refreshTable } = useRequest(() => assetId ? getDataAsset(assetId) : getDwhTableDetail(Number(id)), {refreshDeps:[assetId,id]});
  const tableId = tableData?.id || Number(id) || 0;
  const { data: columnsData, refresh: refreshColumns } = useRequest(() => getDwhTableColumns(tableId), { ready: tableId > 0 && tableData?.assetType !== 'doris_view', refreshDeps: [tableId] });
  const { data: snapshotsData, refresh: refreshSnapshots } = useRequest(() => getDwhTableSnapshots(tableId), { ready: tableId > 0 && tableData?.assetType !== 'doris_view', refreshDeps: [tableId] });
  const { data: logsData, refresh: refreshLogs } = useRequest(() => getMaintenanceLogs({ tableMetaId: tableId }), { ready: tableId > 0 && tableData?.assetType !== 'doris_view', refreshDeps: [tableId] });

  const table = tableData as API.DwhTableMeta | undefined;
  const columns = (columnsData || []) as API.DwhColumnMeta[];
  const maintenanceLogs = (logsData || []) as API.MaintenanceLog[];
  const snapshots = (snapshotsData || []) as API.DwhSnapshot[];

  if (tableError) return <PageContainer><Alert type="error" message="资产加载失败，请确认资产存在且具有访问权限" action={<Button onClick={refreshTable}>重试</Button>}/></PageContainer>;
  if (!table) return <PageContainer><Card><Skeleton active /></Card></PageContainer>;

  if (table.assetType === 'doris_view') return <ViewDetail asset={table} returnTo={searchParams.get('returnTo')?.startsWith('/dwh/tables') ? searchParams.get('returnTo')! : '/dwh/tables'} />;

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
      await updateTableMetadata(tableId, {
        businessDesc: descValue, owner: table.owner, businessDomain: table.businessDomain,
        tags: parseTags(table.tags), sensitivityLevel: table.sensitivityLevel, lifecycleStatus: table.lifecycleStatus,
      });
      message.success('业务描述已更新');
      setEditingDesc(false);
      refreshTable();
    } catch (e) {
      message.error('更新失败');
    }
  };

  const parseTags = (value?: string) => {
    if (!value) return [];
    try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed : []; }
    catch { return []; }
  };

  const openMetadata = () => {
    metadataForm.setFieldsValue({
      businessDesc: table.businessDesc, owner: table.owner, businessDomain: table.businessDomain,
      tags: parseTags(table.tags), sensitivityLevel: table.sensitivityLevel || 'internal',
      lifecycleStatus: table.lifecycleStatus || 'active',
    });
    setMetadataOpen(true);
  };

  const handleUpdateMetadata = async () => {
    const values = await metadataForm.validateFields();
    await updateTableMetadata(tableId, values);
    message.success('表治理信息已更新');
    setMetadataOpen(false);
    refreshTable();
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
    <PageContainer className="asset-detail-page"
      title={`${table.paimonDb}.${table.paimonTable}`}
      subTitle="资产身份、字段契约、生产交付和上下游消费"
      extra={<Space>{access.canQuery && <Button href={`/query/adhoc?assetId=${table.assetId}&assetContext=${encodeURIComponent(searchParams.toString())}`}>查询资产</Button>}<Button href={/^\/dwh\/tables(?:\?|$)/.test(searchParams.get('returnTo') || '') ? searchParams.get('returnTo')! : '/dwh/tables'}>返回资产列表</Button>{access.canAdmin && access.canManageDwh && <Button icon={<ReloadOutlined />} onClick={handleRefreshMetadata}>同步 Catalog 元数据</Button>}</Space>}
    >
      {table.discoveryStatus === 'missing' && <Alert style={{marginBottom:16}} type="warning" showIcon message="Catalog 当前未发现此资产，已保留原身份、字段及变更记录。当前显示的结构和指标可能已过时。" />}
      {table.schemaStatus !== 'observed' && <Alert style={{marginBottom:16}} type="info" showIcon message="当前字段结构尚未重新核验，资产声明不代表物理表已存在。" />}
      <Tabs
        activeKey={searchParams.get('tab') || 'structure'}
        onChange={key => { const next = new URLSearchParams(searchParams); next.set('tab',key); setSearchParams(next,{replace:true}); }}
        items={[
          {key:'usage', label:'生产与消费', children:<AssetContextPanel key={table.assetId} table={table}/>},
          {key:'schema-history', label:'Schema 变更', children:<AssetSchemaHistory key={table.assetId} table={table}/>},
          {
            key: 'structure',
            label: '表结构',
            children: (
              <>
                <Card title="基本信息" extra={access.canManageDwh ? <Button size="small" onClick={openMetadata}>编辑治理信息</Button> : <Tag>只读</Tag>} style={{ marginBottom: 16 }}>
                  <Descriptions column={2}>
                    <Descriptions.Item label="资产 ID" span={2}><Typography.Text copyable code>{table.assetId}</Typography.Text></Descriptions.Item>
                    <Descriptions.Item label="资产类型">{assetTypeLabel(table.assetType)}</Descriptions.Item>
                    <Descriptions.Item label="发现状态">{{observed:'Catalog 已发现',missing:'本次未发现',unverified:'尚未核验'}[table.discoveryStatus || 'unverified']}</Descriptions.Item>
                    <Descriptions.Item label="结构核验时间">{formatDateTime(table.schemaObservedAt)}</Descriptions.Item>
                    <Descriptions.Item label="最近发现时间">{formatDateTime(table.lastSeenAt)}</Descriptions.Item>
                    <Descriptions.Item label="Catalog / 数据库">
                      <Typography.Text code>{table.catalogName || '待同步'} / {table.paimonDb}</Typography.Text>
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
                          {access.canManageDwh && <Button size="small" type="link" onClick={() => { setDescValue(table.businessDesc || ''); setEditingDesc(true); }}>编辑</Button>}
                        </Space>
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="分区键">{table.partitionKeys || '—'}</Descriptions.Item>
                    <Descriptions.Item label="主键">{table.primaryKeys || '—'}</Descriptions.Item>
                    <Descriptions.Item label="责任人">{table.owner || '未指定'}</Descriptions.Item>
                    <Descriptions.Item label="业务域">{table.businessDomain || '未归属'}</Descriptions.Item>
                    <Descriptions.Item label="数据标签"><Space wrap>{parseTags(table.tags).map((tag) => <Tag key={tag}>{tag}</Tag>)}{parseTags(table.tags).length === 0 && '—'}</Space></Descriptions.Item>
                    <Descriptions.Item label="敏感级别"><Tag color={{ public: 'green', internal: 'blue', confidential: 'orange', restricted: 'red' }[table.sensitivityLevel || 'internal']}>{table.sensitivityLevel || 'internal'}</Tag></Descriptions.Item>
                    <Descriptions.Item label="生命周期"><Tag>{table.lifecycleStatus || 'active'}</Tag></Descriptions.Item>
                    <Descriptions.Item label="快照数">{table.snapshotCount ?? '—'}</Descriptions.Item>
                    <Descriptions.Item label="最新快照">{table.latestSnapshotId ?? '—'}</Descriptions.Item>
                    <Descriptions.Item label="记录数">{table.recordCount == null ? '—' : table.recordCount.toLocaleString()}</Descriptions.Item>
                    <Descriptions.Item label="文件数 / 数据大小">{table.fileCount ?? '—'} · {formatSize(table.totalSizeBytes)}</Descriptions.Item>
                    <Descriptions.Item label="最近提交">{formatDateTime(table.latestCommitTime)}</Descriptions.Item>
                    <Descriptions.Item label="元数据更新时间">{formatDateTime(table.updatedAt)}</Descriptions.Item>
                  </Descriptions>
                </Card>

                <Alert style={{marginBottom:16}} type="info" message={table.assetType === 'paimon_primary_key_table' ? '主键表表示当前状态，快照和 Upsert 不等于永久保存历史变更日志。' : table.assetType === 'paimon_append_table' ? '追加表的实际保留期限取决于生命周期策略，不能由资产类型推断。' : '资产类型尚待核验，暂不能判断主键或追加语义。'} />
                <Card title="字段列表">
                  <Table<API.DwhColumnMeta>
                    dataSource={columns}
                    rowKey="id"
                    size="small"
                    columns={[
                      { title: '字段名', dataIndex: 'columnName', key: 'name' },
                      { title: '引擎字段 ID', dataIndex: 'engineFieldId', width: 105, render: value => value ?? '历史未记录' },
                      { title: '类型', dataIndex: 'columnType', key: 'type', width: 120 },
                      { title: '主键', dataIndex: 'isPk', key: 'pk', width: 70, render: (value) => value ? <Tag color="blue">PK</Tag> : '—' },
                      { title: '可为空', dataIndex: 'isNullable', key: 'nullable', width: 80, render: (value) => value ? '是' : '否' },
                      { title: '默认值', dataIndex: 'defaultValue', key: 'defaultValue', width: 120, render: (value) => value || '—' },
                      { title: '业务注释', dataIndex: 'businessComment', key: 'comment', ellipsis: true, render: (value) => value || '—' },
                      {
                        title: '操作',
                        key: 'action',
                        width: 80,
                        render: (_, record) => access.canManageDwh ? (
                          <Button size="small" type="link" onClick={() => {
                            setEditingColumn(record);
                            setColumnComment(record.businessComment || record.comment || '');
                          }}>编辑注释</Button>
                        ) : <span style={{ color: '#8c8c8c' }}>仅查看</span>,
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
                {access.canManageDwh && <Space style={{ marginBottom: 16 }}>
                  <Button type="primary" onClick={handleCompact}>触发 Compact</Button>
                  <Button type="primary" danger onClick={handleExpireSnapshots}>过期快照清理</Button>
                  <Button onClick={handleOrphanCleanup}>清理孤立文件</Button>
                </Space>}
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
      <Modal title="编辑表治理信息" open={metadataOpen} onCancel={() => setMetadataOpen(false)} onOk={handleUpdateMetadata} okText="保存">
        <Form form={metadataForm} layout="vertical">
          <Form.Item name="businessDesc" label="业务描述"><Input.TextArea rows={2} /></Form.Item>
          <Form.Item name="owner" label="责任人"><Input placeholder="姓名或账号" /></Form.Item>
          <Form.Item name="businessDomain" label="业务域"><Input placeholder="例如：交易、用户、风控" /></Form.Item>
          <Form.Item name="tags" label="标签"><Select mode="tags" tokenSeparators={[',']} placeholder="输入后回车" /></Form.Item>
          <Form.Item name="sensitivityLevel" label="敏感级别" rules={[{ required: true }]}><Select options={[
            { value: 'public', label: '公开' }, { value: 'internal', label: '内部' },
            { value: 'confidential', label: '机密' }, { value: 'restricted', label: '严格受限' },
          ]} /></Form.Item>
          <Form.Item name="lifecycleStatus" label="生命周期" rules={[{ required: true }]}><Select options={[
            { value: 'active', label: '使用中' }, { value: 'deprecated', label: '待下线' }, { value: 'offline', label: '已下线' },
          ]} /></Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default DwhTableDetail;
