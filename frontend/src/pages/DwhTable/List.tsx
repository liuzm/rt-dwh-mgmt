import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Col, Empty, Form, Input, Modal, Row, Select, Space, Statistic, Table, Tag, Typography, message } from 'antd';
import { ApartmentOutlined, BarChartOutlined, CloudSyncOutlined, DatabaseOutlined, HddOutlined, ReloadOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { useAccess, useRequest, useSearchParams } from '@umijs/max';
import { createManagedView, getDwhTables, syncMetadataFromPaimon } from '@/api';
import './index.less';
import { assetTypeLabel } from './AssetPanels';

const layerColorMap: Record<string, string> = {
  ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
};

const layerMetaMap: Record<string, {
  label: string;
  color: string;
  purpose: string;
  tableMode: string;
  partitionPolicy: string;
  consumer: string;
}> = {
  ods: {
    label: '原始层', color: '#1677ff', purpose: '源数据对齐，保留可追溯明细',
    tableMode: '主键 Upsert；优先接收完整 CDC 变更', partitionPolicy: '按创建日或业务日；避免频繁修改历史分区',
    consumer: '清洗任务；历史审计需独立变更日志',
  },
  dwd: {
    label: '明细层', color: '#52c41a', purpose: '清洗、标准化后的原子业务事实',
    tableMode: '主键 Upsert；按读写与 Doris 兼容性选择表模式', partitionPolicy: '按业务日期和稳定维度分区',
    consumer: 'DWS 汇总、明细分析',
  },
  dws: {
    label: '汇总层', color: '#fa8c16', purpose: '面向主题的公共汇总与宽表',
    tableMode: '增量聚合或周期覆盖，口径可复用', partitionPolicy: '通常按业务日期分区',
    consumer: '指标、报表和 ADS',
  },
  ads: {
    label: '应用层', color: '#f5222d', purpose: '面向具体报表、接口和分析场景',
    tableMode: '查询导向，可由上游稳定重建', partitionPolicy: '按交付周期或服务主题组织',
    consumer: '数据 API、看板和导出',
  },
};

const sensitivityMeta: Record<string, { label: string; color: string }> = {
  public: { label: '公开', color: 'green' },
  internal: { label: '内部', color: 'blue' },
  confidential: { label: '机密', color: 'orange' },
  restricted: { label: '受限', color: 'red' },
};

const lifecycleMeta: Record<string, { label: string; color: string }> = {
  active: { label: '使用中', color: 'success' },
  deprecated: { label: '待下线', color: 'warning' },
  offline: { label: '已下线', color: 'default' },
};

const formatSize = (bytes?: number) => {
  if (bytes === undefined || bytes === null) return '—';
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let index = 0;
  let size = bytes;
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1; }
  return `${size.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
};

const formatDateTime = (value?: string | number[]) => {
  if (!value) return '—';
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value;
    return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:${String(second).padStart(2, '0')}`;
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
};

const DwhTableList: React.FC = () => {
  const access = useAccess();
  const [params, setParams] = useSearchParams();
  const layer = params.get('layer') || undefined;
  const database = params.get('database') || undefined;
  const assetType = params.get('assetType') || undefined;
  const keyword = params.get('keyword') || '';
  const setFilter = (key: string, value?: string) => { const next = new URLSearchParams(params); if (value) next.set(key,value); else next.delete(key); setParams(next,{replace:true}); };
  const setLayer = (value?: string) => setFilter('layer',value);
  const setDatabase = (value?: string) => setFilter('database',value);
  const setKeyword = (value: string) => setFilter('keyword',value);
  const [viewOpen, setViewOpen] = useState(false);
  const [viewForm] = Form.useForm();
  const [creating, setCreating] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [storageGuideOpen, setStorageGuideOpen] = useState(false);
  const { data, loading, refresh } = useRequest(getDwhTables);
  const tables = (data || []) as API.DwhTableMeta[];

  const databases = useMemo(() => Array.from(new Set(tables.map((table) => table.paimonDb)))
    .sort().map((value) => ({ label: value, value })), [tables]);
  const filteredTables = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return tables.filter((table) => (!layer || table.layer === layer)
      && (!database || table.paimonDb === database)
      && (!assetType || (assetType === 'doris_view' ? table.assetType === assetType : table.assetType?.startsWith('paimon')))
      && (!normalizedKeyword
        || `${table.paimonDb}.${table.paimonTable} ${table.businessDesc || ''}`
          .toLowerCase().includes(normalizedKeyword)));
  }, [tables, layer, database, keyword, assetType]);

  const observedTables = tables.filter(table => table.discoveryStatus === 'observed');
  const totalSize = observedTables.reduce((sum, table) => sum + (table.totalSizeBytes || 0), 0);
  const totalRecords = observedTables.reduce((sum, table) => sum + (table.recordCount || 0), 0);
  const layerStats = Object.entries(layerMetaMap).map(([value, meta]) => ({
    value, ...meta, count: tables.filter((table) => table.layer === value).length,
  }));
  const hasFilters = Boolean(keyword.trim() || database || layer || assetType);

  const handleSyncMetadata = async () => {
    setSyncing(true);
    try {
      const count = await syncMetadataFromPaimon();
      message.success(`元数据同步完成，共发现 ${count} 张 Paimon 表`);
      refresh();
    } catch (error: any) {
      message.error(error?.message || '元数据同步失败');
    } finally {
      setSyncing(false);
    }
  };

  return (
    <PageContainer
      className="paimon-assets-page"
      title="湖仓资产目录"
      subTitle="统一管理 Paimon 表与 Doris 普通 View"
      extra={<Space size={8}>
        <Tag className="paimon-catalog-tag" icon={<DatabaseOutlined />}>JDBC Catalog</Tag>
        <Button icon={<ApartmentOutlined />} onClick={() => setStorageGuideOpen(true)}>分层存储方案</Button>
        <Button icon={<ReloadOutlined />} onClick={() => refresh()}>刷新</Button>
        {access.canManageDwh && <Button type="primary" onClick={()=>setViewOpen(true)}>新建 View</Button>}
      </Space>}
    >
      <Row className="paimon-metrics" gutter={[12, 12]}>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-blue"><TableOutlined /></span><Statistic title="已观测资产" value={observedTables.length} suffix="个" /></Card></Col>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-cyan"><DatabaseOutlined /></span><Statistic title="当前数据库" value={new Set(observedTables.map(table=>table.paimonDb)).size} suffix="个" /></Card></Col>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-green"><BarChartOutlined /></span><Statistic title="已观测记录合计" value={totalRecords} formatter={(value) => Number(value).toLocaleString()} /></Card></Col>
        <Col xs={12} md={6}><Card className="paimon-metric-card"><span className="paimon-metric-icon is-purple"><HddOutlined /></span><Statistic title="已观测文件容量" value={formatSize(totalSize)} /></Card></Col>
      </Row>

      <Alert
        className="paimon-storage-note"
        type="info"
        showIcon
        message="Paimon 表存储：一个物理 Warehouse，四个逻辑数据层"
        description="Catalog 保存元数据指针；ODS、DWD、DWS、ADS 通过 Database／表目录分层。快照保留用于回溯与恢复，不等于业务数据保留期。"
        action={<Button size="small" type="link" onClick={() => setStorageGuideOpen(true)}>查看方案</Button>}
      />

      <Card className="paimon-layer-card">
        <div className="paimon-layer-heading"><div><strong>数仓分层</strong><Typography.Text type="secondary">按治理层级筛选表与 View</Typography.Text></div><Typography.Text type="secondary">共 {tables.length} 个资产</Typography.Text></div>
        <div className="paimon-layer-list">
          {layerStats.map((item) => <button key={item.value} type="button" className={`paimon-layer-item${layer === item.value ? ' is-active' : ''}`}
            aria-pressed={layer === item.value}
            onClick={() => setLayer(layer === item.value ? undefined : item.value)}>
            <span className="paimon-layer-dot" style={{ background: item.color }} />
            <span className="paimon-layer-code">{item.value.toUpperCase()}</span>
            <span className="paimon-layer-label">{item.label}</span>
            <strong>{item.count}</strong>
          </button>)}
        </div>
      </Card>

      <Card className="paimon-assets-card" title="表与 View 资产" extra={<Tag className="paimon-result-tag">{filteredTables.length} / {tables.length} 张</Tag>}>
        <div className="paimon-assets-toolbar">
          <div className="paimon-filter-group">
            <Input className="paimon-search" allowClear placeholder="搜索库名、表名或业务描述" prefix={<SearchOutlined />}
              value={keyword} onChange={(event) => setKeyword(event.target.value)} />
            <Select style={{minWidth:140}} placeholder="全部资产类型" allowClear value={assetType} onChange={v=>setFilter('assetType',v)} options={[{value:'paimon',label:'Paimon 表'},{value:'doris_view',label:'Doris View'}]}/>
            <Select className="paimon-database-filter" placeholder="全部数据库" allowClear value={database} onChange={setDatabase}
              options={databases} />
            <Select className="paimon-layer-filter" placeholder="全部分层" allowClear value={layer} onChange={setLayer}
              options={[
                { label: 'ODS 原始层', value: 'ods' },
                { label: 'DWD 明细层', value: 'dwd' },
                { label: 'DWS 汇总层', value: 'dws' },
                { label: 'ADS 应用层', value: 'ads' },
              ]} />
          </div>
          <div className="paimon-toolbar-actions">
            {hasFilters && <Button type="link" onClick={() => setParams({}, {replace:true})}>重置筛选</Button>}
            {access.canManageDwh && <Button type="primary" icon={<CloudSyncOutlined />} loading={syncing} onClick={handleSyncMetadata}>
              同步 Catalog 元数据
            </Button>}
          </div>
        </div>

        <Table<API.DwhTableMeta>
          className="paimon-assets-table"
          dataSource={filteredTables}
          rowKey="id"
          loading={loading}
          size="small"
          pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 个资产` }}
          locale={{ emptyText: <Empty className="paimon-empty" image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={hasFilters ? '没有匹配的 Paimon 表' : '尚未同步 Paimon Catalog 元数据'}>
            {access.canManageDwh && !hasFilters && <Button type="link" icon={<CloudSyncOutlined />} loading={syncing} onClick={handleSyncMetadata}>立即同步元数据</Button>}
          </Empty> }}
          columns={[
            { title: '资产', key: 'asset', width: 240, render: (_, record) => <div className="paimon-table-identity">
              <Typography.Text strong ellipsis>{record.paimonTable}</Typography.Text>
              <Typography.Text className="paimon-table-path" code>{record.catalogName}.{record.paimonDb}.{record.paimonTable}</Typography.Text>
            </div> },
            { title: '类型 / 发现状态', key: 'identity', width: 165, render: (_,record) => <Space direction="vertical" size={0}><Tag>{assetTypeLabel(record.assetType)}</Tag>{record.discoveryStatus !== 'observed' && <Tag color="warning">{record.discoveryStatus === 'missing' ? '本次未发现' : '尚未核验'}</Tag>}</Space> },
            { title: '分层', dataIndex: 'layer', width: 78,
              render: (value) => <Tag color={layerColorMap[value]}>{String(value).toUpperCase()}</Tag> },
            { title: '业务描述', dataIndex: 'businessDesc', width: 210, ellipsis: true, render: (value) => value || <Typography.Text type="secondary">暂未补充</Typography.Text> },
            { title: '治理信息', key: 'governance', width: 190, render: (_, record) => {
              const sensitivity = sensitivityMeta[record.sensitivityLevel || 'internal'] || sensitivityMeta.internal;
              const lifecycle = lifecycleMeta[record.lifecycleStatus || 'active'] || lifecycleMeta.active;
              return <div className="paimon-governance-cell"><div><span>{record.owner || '未指定'}</span><span className="paimon-domain">{record.businessDomain || '未归属'}</span></div><div><Tag color={sensitivity.color}>{sensitivity.label}</Tag><Tag color={lifecycle.color}>{lifecycle.label}</Tag></div></div>;
            } },
            { title: '记录数', dataIndex: 'recordCount', width: 105, align: 'right',
              render: (value) => value === undefined || value === null ? '—' : Number(value).toLocaleString() },
            { title: '存储概况', key: 'storage', width: 150, render: (_, record) => record.assetType === 'doris_view' ? <Typography.Text type="secondary">无物理存储</Typography.Text> : <div className="paimon-storage-cell"><span>{formatSize(record.totalSizeBytes)} · {record.fileCount ?? '—'} 文件</span><span>{record.snapshotCount ?? '—'} 个快照</span></div> },
            { title: '最近提交', dataIndex: 'latestCommitTime', width: 170, render: formatDateTime },
            { title: '操作', fixed: 'right', width: 120, render: (_, record) => (
              <Space size={4}>
                <Button size="small" type="link" href={`/dwh/assets/${record.assetId}?returnTo=${encodeURIComponent('/dwh/tables' + (params.toString() ? '?' + params.toString() : ''))}`}>查看</Button>
                {access.canManageDwh && record.assetType !== 'doris_view' && <Button size="small" type="link" href={`/dwh/maintenance?tableId=${record.id}`}>维护</Button>}
              </Space>
            ) },
          ]}
          scroll={{ x: 1260 }}
        />
      </Card>

      <Modal title="新建 Doris 普通 View" open={viewOpen} width={760} confirmLoading={creating} onCancel={()=>setViewOpen(false)} okText="创建草稿" onOk={async()=>{
        try {const values=await viewForm.validateFields();setCreating(true);const result=await createManagedView(values);window.location.href=`/dwh/assets/${result.asset.assetId}`;}
        catch(e:any){if(!e.errorFields)message.error(e.message || '创建失败');}finally{setCreating(false);}
      }}>
        <Alert type="info" showIcon message="View 位于 internal.rtdwh_views，草稿创建后需校验并发布" style={{marginBottom:16}} />
        <Form form={viewForm} layout="vertical">
          <Form.Item name="name" label="View 名称" rules={[{required:true},{pattern:/^[A-Za-z_][A-Za-z0-9_]*$/,message:'使用字母、数字和下划线，以字母或下划线开头'}]}><Input maxLength={128}/></Form.Item>
          <Form.Item name="description" label="业务描述"><Input/></Form.Item>
          <Form.Item name="sql" label="SELECT 定义" rules={[{required:true}]}><Input.TextArea rows={10} placeholder="SELECT id FROM rtdwh_paimon.ods.example_table" style={{fontFamily:'monospace'}}/></Form.Item>
        </Form>
      </Modal>
      <Modal
        className="paimon-storage-guide"
        title="Paimon 分层存储方案"
        open={storageGuideOpen}
        width={920}
        footer={<Button type="primary" onClick={() => setStorageGuideOpen(false)}>知道了</Button>}
        onCancel={() => setStorageGuideOpen(false)}
      >
        <div className="paimon-storage-path" aria-label="Paimon 存储层级">
          {['JDBC Catalog\n元数据与指针', '共享 Warehouse\n对象／文件存储', 'Database\nods · dwd · dws · ads', 'Table\n分区与 Bucket', 'Snapshot → Manifest → Data File'].map((item, index) => (
            <React.Fragment key={item}>
              {index > 0 && <span className="paimon-storage-arrow">→</span>}
              <span className="paimon-storage-node">{item.split('\n').map((line) => <span key={line}>{line}</span>)}</span>
            </React.Fragment>
          ))}
        </div>
        <Table
          className="paimon-storage-guide-table"
          rowKey="code"
          size="small"
          pagination={false}
          dataSource={Object.entries(layerMetaMap).map(([code, meta]) => ({ code, ...meta }))}
          columns={[
            { title: '分层', dataIndex: 'code', width: 92, render: (value: string, record: any) => <Tag color={record.color}>{value.toUpperCase()} · {record.label}</Tag> },
            { title: '定位', dataIndex: 'purpose', width: 190 },
            { title: '写入／表模型', dataIndex: 'tableMode', width: 245 },
            { title: '分区建议', dataIndex: 'partitionPolicy', width: 220 },
            { title: '主要消费方', dataIndex: 'consumer' },
          ]}
        />
        <Alert
          className="paimon-storage-guide-warning"
          type="warning"
          showIcon
          message="生命周期需要拆成两套策略"
          description="Snapshot Expire 只清理不再被保留快照引用的历史文件；业务数据到期删除需要另行配置分区生命周期。长期审计节点应先创建 Tag，再清理快照。"
        />
      </Modal>
    </PageContainer>
  );
};

export default DwhTableList;
