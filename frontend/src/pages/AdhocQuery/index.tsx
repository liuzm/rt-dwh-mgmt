import React, { useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Button, Card, Col, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Row,
  Select, Space, Spin, Statistic, Table, Tabs, Tag, Tooltip, Tree, Typography, message,
} from 'antd';
import {
  CloudOutlined, DatabaseOutlined, DeleteOutlined, DownloadOutlined, FolderOpenOutlined,
  LaptopOutlined, PlayCircleOutlined, SaveOutlined,
} from '@ant-design/icons';
import { useRequest, useSearchParams } from '@umijs/max';
import type { editor } from 'monaco-editor';
import {
  cancelQuery, cancelQueryByRequestId, createSavedQuery, deleteSavedQuery, executeQuery,
  exportQuery, getQueryCatalog, getQueryGovernanceStats, getQueryHistory, getQueryProfile,
  getSavedQueries, updateSavedQuery, getDataAsset,
} from '@/api';
import SqlEditor from './SqlEditor';
import './index.less';
import '../DwhTable/asset.less';

const LOCAL_SQL_KEY = 'rtdwh.saved-sql.v1';
const CURRENT_DRAFT_KEY = 'rtdwh.sql-current-draft.v1';

type LocalQuery = {
  id: string;
  name: string;
  sqlText: string;
  description?: string;
  tags?: string;
  updatedAt: string;
};

type ActiveQuery = { source: 'local' | 'remote'; id: string | number; name: string };

const queryStatusLabels: Record<string, string> = {
  success: '成功', failed: '失败', cancelled: '已取消', running: '运行中',
};

const readLocalQueries = (): LocalQuery[] => {
  try { return JSON.parse(localStorage.getItem(LOCAL_SQL_KEY) || '[]'); }
  catch { return []; }
};

const formatDateTime = (value?: string | number[]) => {
  if (!value) return '—';
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value;
    return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:${String(second).padStart(2, '0')}`;
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
};

const formatBytes = (value?: number) => {
  if (value == null) return '—';
  if (value < 1024) return `${value} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let size = value / 1024;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1; }
  return `${size.toFixed(size >= 10 ? 1 : 2)} ${units[index]}`;
};

const AdhocQuery: React.FC = () => {
  const [assetParams] = useSearchParams();
  const assetId = assetParams.get('assetId') || '';
  const {data: sourceAsset} = useRequest(() => getDataAsset(assetId), {ready:!!assetId, refreshDeps:[assetId]});
  const [sql, setSql] = useState(() => localStorage.getItem(CURRENT_DRAFT_KEY) || '');
  const [maxRows, setMaxRows] = useState(1000);
  const [result, setResult] = useState<API.QueryResult | null>(null);
  const [executing, setExecuting] = useState(false);
  const [historyId, setHistoryId] = useState<number>();
  const [requestId, setRequestId] = useState<string>();
  const [selectedDatabase, setSelectedDatabase] = useState<string>();
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [saveOpen, setSaveOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [profileLoading, setProfileLoading] = useState(false);
  const [profileData, setProfileData] = useState<{ queryId: string; profile: string }>();
  const [localQueries, setLocalQueries] = useState<LocalQuery[]>(readLocalQueries);
  const [activeQuery, setActiveQuery] = useState<ActiveQuery>();
  const [saveForm] = Form.useForm();
  const editorRef = useRef<editor.IStandaloneCodeEditor>();

  const { data: catalogData } = useRequest(getQueryCatalog);
  const catalog = catalogData as API.QueryCatalog | undefined;
  useEffect(() => {
    if (!selectedDatabase && catalog?.databases.length) setSelectedDatabase(catalog.databases[0].name);
  }, [catalog, selectedDatabase]);
  const { data: savedData, loading: savedLoading, refresh: refreshSaved } = useRequest(getSavedQueries);
  const savedQueries = (savedData || []) as API.SavedQuery[];
  const { data: historyPageData, loading: historyLoading, run: loadHistory } = useRequest(
    getQueryHistory, { defaultParams: [{ page: 0, size: 20 }] },
  );
  const historyPage = historyPageData as API.PageResult<any> | undefined;
  const historyList = historyPage?.content || [];
  const { data: governanceData, refresh: refreshGovernance } = useRequest(getQueryGovernanceStats);
  const governance = governanceData as API.QueryGovernanceStats | undefined;

  useEffect(() => {
    const timer = window.setTimeout(() => localStorage.setItem(CURRENT_DRAFT_KEY, sql), 300);
    return () => window.clearTimeout(timer);
  }, [sql]);

  const catalogTree = useMemo(() => catalog ? [{
    title: `${catalog.catalogName} (${catalog.catalogKey})`,
    key: `catalog:${catalog.catalogName}`,
    icon: <DatabaseOutlined />,
    children: catalog.databases.map((database) => ({
      title: database.name,
      key: `database:${database.name}`,
      children: database.tables.map((table) => ({
        title: (
          <span className="adhoc-query-tree-table-title">
            <span className="adhoc-query-tree-table-name">{table.name}</span>
            <span className="adhoc-query-tree-layer">{table.layer.toUpperCase()}</span>
          </span>
        ),
        key: `table:${catalog.catalogName}.${database.name}.${table.name}`,
        children: table.columns.map((column) => ({
          title: (
            <span className="adhoc-query-tree-column-title">
              <span>{column.name}</span>
              <span>{column.type}{column.primaryKey ? ' · PK' : ''}</span>
            </span>
          ),
          key: `column:${catalog.catalogName}.${database.name}.${table.name}.${column.name}`,
          isLeaf: true,
        })),
      })),
    })),
  }] : [], [catalog]);

  const insertIntoEditor = (text: string) => {
    const instance = editorRef.current;
    if (!instance) { setSql((current) => `${current}${text}`); return; }
    const selection = instance.getSelection();
    if (!selection) return;
    instance.executeEdits('catalog-explorer', [{ range: selection, text, forceMoveMarkers: true }]);
    instance.focus();
  };

  const handleCatalogSelect = (keys: React.Key[]) => {
    const key = String(keys[0] || '');
    if (key.startsWith('table:')) insertIntoEditor(key.substring(6));
    if (key.startsWith('column:')) insertIntoEditor(key.split('.').pop() || '');
  };

  const handleExecute = async () => {
    if (!sql.trim()) return message.warning('请输入 SQL 语句');
    try {
      setExecuting(true);
      const currentRequestId = `web_${Date.now()}`;
      setRequestId(currentRequestId);
      const queryResult = await executeQuery({ sql, maxRows, requestId: currentRequestId,
        catalog: catalog?.catalogName, database: selectedDatabase });
      setResult(queryResult);
      setHistoryId(queryResult.historyId);
      loadHistory({ page: 0, size: historyPage?.size || 20 });
      refreshGovernance();
      if (queryResult.status === 'success') {
        message.success(`查询成功，返回 ${queryResult.rowCount || 0} 行，耗时 ${queryResult.durationMs || 0}ms`);
      } else message.error(`查询失败：${queryResult.errorMsg || '未知错误'}`);
    } catch (error: any) {
      message.error(error?.message || '查询执行异常');
    } finally { setExecuting(false); }
  };

  const handleCancel = async () => {
    if (!requestId && !historyId) return;
    try {
      if (requestId) await cancelQueryByRequestId(requestId);
      else await cancelQuery(historyId!);
      message.info('已请求取消查询');
    } catch { message.error('取消失败，查询可能已结束'); }
  };

  const handleExport = async () => {
    if (!sql.trim()) return message.warning('请输入 SQL 语句');
    try {
      const blob = await exportQuery({ sql, maxRows, catalog: catalog?.catalogName, database: selectedDatabase });
      const url = URL.createObjectURL(blob as Blob);
      const link = document.createElement('a');
      link.href = url; link.download = 'query-result.csv'; link.click(); URL.revokeObjectURL(url);
    } catch { message.error('导出失败'); }
  };

  const openProfile = async (id?: number) => {
    if (!id) return;
    setProfileOpen(true);
    setProfileLoading(true);
    setProfileData(undefined);
    try { setProfileData(await getQueryProfile(id)); }
    catch (error: any) { message.error(error?.message || 'Query Profile 读取失败'); }
    finally { setProfileLoading(false); }
  };

  const openSave = () => {
    saveForm.setFieldsValue({
      location: activeQuery?.source || 'remote',
      name: activeQuery?.name || `查询_${new Date().toISOString().slice(0, 16).replace(/[-T:]/g, '')}`,
      description: '', tags: '',
    });
    setSaveOpen(true);
  };

  const persistLocal = (items: LocalQuery[]) => {
    setLocalQueries(items);
    localStorage.setItem(LOCAL_SQL_KEY, JSON.stringify(items));
  };

  const handleSave = async () => {
    const values = await saveForm.validateFields();
    if (!sql.trim()) return message.warning('没有可保存的 SQL');
    if (values.location === 'local') {
      const id = activeQuery?.source === 'local' ? String(activeQuery.id) : `local_${Date.now()}`;
      const next: LocalQuery = { id, name: values.name.trim(), sqlText: sql,
        description: values.description, tags: values.tags, updatedAt: new Date().toISOString() };
      persistLocal([next, ...localQueries.filter((item) => item.id !== id && item.name !== next.name)]);
      setActiveQuery({ source: 'local', id, name: next.name });
      message.success('已保存到当前浏览器');
    } else {
      const payload = { name: values.name.trim(), sqlText: sql,
        description: values.description, tags: values.tags };
      const saved = activeQuery?.source === 'remote'
        ? await updateSavedQuery(Number(activeQuery.id), payload)
        : await createSavedQuery(payload);
      setActiveQuery({ source: 'remote', id: saved.id, name: saved.name });
      refreshSaved();
      message.success('已保存到服务端 SQL 库');
    }
    setSaveOpen(false);
  };

  const loadSaved = (item: LocalQuery | API.SavedQuery, source: 'local' | 'remote') => {
    setSql(item.sqlText);
    setActiveQuery({ source, id: item.id, name: item.name });
    setLibraryOpen(false);
    message.success(`已打开：${item.name}`);
  };

  const removeLocal = (id: string) => {
    persistLocal(localQueries.filter((item) => item.id !== id));
    if (activeQuery?.source === 'local' && activeQuery.id === id) setActiveQuery(undefined);
  };

  const removeRemote = async (id: number) => {
    await deleteSavedQuery(id);
    if (activeQuery?.source === 'remote' && activeQuery.id === id) setActiveQuery(undefined);
    refreshSaved();
    message.success('服务端 SQL 已删除');
  };

  const savedColumns = (source: 'local' | 'remote') => [
    { title: '名称', dataIndex: 'name', ellipsis: true, render: (value: string, record: any) => (
      <Button type="link" onClick={() => loadSaved(record, source)}>{value}</Button>) },
    { title: '标签', dataIndex: 'tags', width: 120, render: (value: string) => value || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 180, render: formatDateTime },
    { title: '操作', width: 80, render: (_: unknown, record: any) => (
      <Popconfirm title="确认删除这条 SQL？" onConfirm={() => source === 'local'
        ? removeLocal(record.id) : removeRemote(record.id)}>
        <Button type="link" danger icon={<DeleteOutlined />} />
      </Popconfirm>) },
  ];

  return (
    <PageContainer className="adhoc-query-page" title="SQL 查询" subTitle="Doris 加速 Paimon 查询、Catalog 智能提示与 SQL 资产管理">
      {sourceAsset && <Alert className="asset-query-context" style={{marginBottom:16}} type={sourceAsset.discoveryStatus === 'missing' ? 'warning' : 'info'} showIcon
        message={`来源资产：${sourceAsset.paimonDb}.${sourceAsset.paimonTable}${sourceAsset.discoveryStatus === 'missing' ? '（Catalog 当前未发现）' : ''}`}
        action={<Space><Button disabled={!sourceAsset.catalogName || sourceAsset.discoveryStatus !== 'observed'} onClick={() => {
          const quote = (value:string) => '`' + value.replace(/`/g,'``') + '`';
          setSql(`SELECT * FROM ${[sourceAsset.catalogName!,sourceAsset.paimonDb,sourceAsset.paimonTable].map(quote).join('.')} LIMIT 100`);
          if (sourceAsset.catalogName === catalog?.catalogName) setSelectedDatabase(sourceAsset.paimonDb); setActiveQuery(undefined);
        }}>载入资产查询</Button><Button href={`/dwh/assets/${sourceAsset.assetId}?${assetParams.get('assetContext') || ''}`}>返回资产</Button></Space>} />}

      <Card className="adhoc-query-editor-card" title={activeQuery ? `SQL 编辑器 · ${activeQuery.name}` : 'SQL 编辑器'}
        extra={<Space wrap size={8}>
          <Tag color={catalog ? 'green' : 'orange'}>{catalog ? `Catalog: ${catalog.catalogName}` : 'Catalog 加载中'}</Tag>
          <Button icon={<FolderOpenOutlined />} onClick={() => setLibraryOpen(true)}>SQL 库</Button>
          <Button icon={<SaveOutlined />} onClick={openSave}>保存 SQL</Button>
        </Space>}>
        <div className="adhoc-query-toolbar">
          <div className="adhoc-query-toolbar-controls">
          <Select className="adhoc-query-engine-select" value="doris-paimon" options={[
            { label: 'Doris · Paimon Catalog', value: 'doris-paimon' },
          ]} />
          <Select className="adhoc-query-database-select" value={selectedDatabase} placeholder="默认数据库"
            onChange={setSelectedDatabase}
            options={(catalog?.databases || []).map((database) => ({ label: database.name, value: database.name }))} />
          <InputNumber min={1} max={50000} value={maxRows} onChange={(value) => setMaxRows(value || 1000)}
            addonBefore="最大行数" className="adhoc-query-limit-input" />
          <Button type="primary" icon={<PlayCircleOutlined />} loading={executing} onClick={handleExecute}>执行查询</Button>
          <Button danger disabled={!executing || (!requestId && !historyId)} onClick={handleCancel}>取消查询</Button>
          <Button icon={<DownloadOutlined />} onClick={handleExport}>导出 CSV</Button>
          </div>
          <Typography.Text className="adhoc-query-shortcut" type="secondary">
            ⌘/Ctrl + Enter 执行，输入 <Typography.Text code>{catalog?.catalogName || 'catalog'}.</Typography.Text> 查看 Catalog 提示
          </Typography.Text>
        </div>

        <div className="adhoc-query-workspace">
          <section className="adhoc-query-catalog-panel" aria-label="Catalog 资源">
            <div className="adhoc-query-panel-title">
              <DatabaseOutlined />
              <Typography.Text strong>Catalog 资源</Typography.Text>
            </div>
            <div className="adhoc-query-tree-scroll">
              <Tree className="adhoc-query-catalog-tree" showLine showIcon blockNode defaultExpandAll
                treeData={catalogTree} onSelect={handleCatalogSelect} />
            </div>
          </section>
          <section className="adhoc-query-editor-panel" aria-label="SQL 编辑器">
            <SqlEditor height="100%" value={sql} catalog={catalog} onChange={setSql} onExecute={handleExecute}
              onReady={(instance) => { editorRef.current = instance; }} />
          </section>
        </div>
      </Card>

      {result && (
        <Card className="adhoc-query-data-card adhoc-query-result-card" title={`查询结果（Doris · 耗时 ${result.durationMs || 0}ms · 返回 ${result.rowCount || 0} 行）`}
          extra={<Space>
            {result.traceId && <Typography.Text type="secondary" copyable>Trace: {result.traceId}</Typography.Text>}
            <Button size="small" disabled={!result.queryId || !result.historyId} onClick={() => openProfile(result.historyId)}>查看 Profile</Button>
          </Space>}>
          {result.status !== 'success' && <Alert className="adhoc-query-result-alert" type="error" message={result.errorMsg || '查询失败'} showIcon />}
          {result.truncated && <Alert className="adhoc-query-result-alert" type="warning" message="结果已达到最大返回行数，请增加限制或导出 CSV" showIcon />}
          {result.budgetExceeded && <Alert className="adhoc-query-result-alert" type="warning" showIcon message="本次查询超出成本软预算" description={result.budgetReason} />}
          <Row className="adhoc-query-result-stats" gutter={12}>
            <Col span={6}><Statistic title="扫描行数" value={result.scannedRows ?? '—'} /></Col>
            <Col span={6}><Statistic title="扫描数据量" value={formatBytes(result.scannedBytes)} /></Col>
            <Col span={6}><Statistic title="CPU 时间" value={result.cpuMs ?? '—'} suffix={result.cpuMs == null ? undefined : 'ms'} /></Col>
            <Col span={6}><Statistic title="峰值内存" value={formatBytes(result.peakMemoryBytes)} /></Col>
          </Row>
          <Table<Record<string, any>>
            dataSource={(result.rows || []).map((row, index) => ({ key: index,
              ...Object.fromEntries((result.columns || []).map((column, columnIndex) => [column, row[columnIndex]])) }))}
            columns={(result.columns || []).map((column) => ({ title: column, dataIndex: column, key: column, ellipsis: true }))}
            size="small" scroll={{ x: 'max-content', y: 380 }} pagination={false} />
        </Card>
      )}

      <Row className="adhoc-query-metrics" gutter={[10, 10]}>
        <Col xs={12} sm={12} md={8} lg={6} xl={4}><Card className="adhoc-query-metric-card is-success"><Statistic title="近 1000 次成功率" value={governance?.successRate || 0} precision={1} suffix="%" /></Card></Col>
        <Col xs={12} sm={12} md={8} lg={6} xl={4}><Card className="adhoc-query-metric-card is-duration"><Statistic title="P95 耗时" value={governance?.p95DurationMs || 0} suffix="ms" /></Card></Col>
        <Col xs={12} sm={12} md={8} lg={6} xl={4}><Card className="adhoc-query-metric-card is-failed"><Statistic title="失败查询" value={governance?.failedCount || 0} /></Card></Col>
        <Col xs={12} sm={12} md={8} lg={6} xl={4}><Card className="adhoc-query-metric-card is-budget"><Statistic title="预算超限" value={governance?.budgetExceededCount || 0} /></Card></Col>
        <Col xs={12} sm={12} md={8} lg={6} xl={4}><Card className="adhoc-query-metric-card is-queue"><Statistic title="平均排队" value={governance?.averageQueueWaitMs || 0} precision={1} suffix="ms" /></Card></Col>
        <Col xs={12} sm={12} md={8} lg={6} xl={4}><Card className="adhoc-query-metric-card is-capacity"><Statistic title="运行 / 排队" value={`${governance?.runningCount || 0} / ${governance?.queuedCount || 0}`} suffix={<span className="adhoc-query-metric-cap">上限 {governance?.concurrencyLimit || 2}</span>} /></Card></Col>
      </Row>

      <Card className="adhoc-query-data-card adhoc-query-cost-card" title="高成本查询 Top 10">
        <Table className="adhoc-query-table" dataSource={governance?.costlyQueries || []} rowKey="id" size="small" pagination={false}
          locale={{ emptyText: '暂无可用的 Doris 成本指标' }}
          columns={[
            { title: 'SQL', dataIndex: 'sqlText', ellipsis: true },
            { title: '成本分', dataIndex: 'costScore', width: 100, render: (value) => value == null ? '—' : <Tag color={value > 100 ? 'red' : value > 60 ? 'orange' : 'green'}>{value}</Tag> },
            { title: '扫描量', dataIndex: 'scannedBytes', width: 120, render: formatBytes },
            { title: 'CPU', dataIndex: 'cpuMs', width: 100, render: (value) => value == null ? '—' : `${value}ms` },
            { title: '峰值内存', dataIndex: 'peakMemoryBytes', width: 120, render: formatBytes },
            { title: '排队', dataIndex: 'queueWaitMs', width: 90, render: (value) => `${value || 0}ms` },
            { title: '预算', dataIndex: 'budgetExceeded', width: 90, render: (value, record: any) => value ? <Tooltip title={record.budgetReason}><Tag color="error">超限</Tag></Tooltip> : <Tag color="success">正常</Tag> },
          ]} />
      </Card>

      <Card className="adhoc-query-data-card adhoc-query-history-card" title="查询历史">
        <Table className="adhoc-query-table" dataSource={historyList} rowKey="id" size="small" loading={historyLoading}
          pagination={{ total: historyPage?.totalElements || 0, pageSize: historyPage?.size || 20,
            current: (historyPage?.number || 0) + 1, showSizeChanger: true,
            onChange: (page, pageSize) => loadHistory({ page: page - 1, size: pageSize }) }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 190, render: formatDateTime },
            { title: 'SQL', dataIndex: 'sqlText', ellipsis: true },
            { title: '引擎', dataIndex: 'queryEngine', width: 90,
              render: (value) => <Tag color="blue">{value || 'doris'}</Tag> },
            { title: '行数', dataIndex: 'resultRowCount', width: 80 },
            { title: '扫描行数', dataIndex: 'scannedRows', width: 110, render: (value) => value ?? '—' },
            { title: '扫描量', dataIndex: 'scannedBytes', width: 110, render: formatBytes },
            { title: 'CPU', dataIndex: 'cpuMs', width: 90, render: (value) => value == null ? '—' : `${value}ms` },
            { title: '峰值内存', dataIndex: 'peakMemoryBytes', width: 110, render: formatBytes },
            { title: '排队', dataIndex: 'queueWaitMs', width: 80, render: (value) => `${value || 0}ms` },
            { title: '成本', dataIndex: 'costScore', width: 85, render: (value, record: any) => value == null ? '—' : <Tooltip title={record.budgetReason}><Tag color={record.budgetExceeded ? 'red' : 'blue'}>{value}</Tag></Tooltip> },
            { title: '耗时', dataIndex: 'durationMs', width: 90, render: (value) => `${value || 0}ms` },
            { title: '状态', dataIndex: 'status', width: 100, render: (value) => <Tag color={{
              success: 'green', failed: 'red', cancelled: 'orange', running: 'blue',
            }[value as string]}>{queryStatusLabels[value] || value}</Tag> },
            { title: '操作', width: 150, fixed: 'right', render: (_, record: any) => <Space size={0}>
              <Button type="link" onClick={() => { setSql(record.sqlText); setActiveQuery(undefined); }}>载入</Button>
              <Button type="link" disabled={!record.queryId} onClick={() => openProfile(record.id)}>Profile</Button>
            </Space> },
          ]} />
      </Card>

      <Drawer title="我的 SQL 库" width={760} open={libraryOpen} onClose={() => setLibraryOpen(false)}>
        <Tabs items={[
          { key: 'remote', label: <span><CloudOutlined /> 服务端 SQL</span>, children: (
            <Table dataSource={savedQueries} rowKey="id" loading={savedLoading} size="small"
              columns={savedColumns('remote')} locale={{ emptyText: '暂无服务端 SQL' }} />) },
          { key: 'local', label: <span><LaptopOutlined /> 本地草稿</span>, children: (
            <Table dataSource={localQueries} rowKey="id" size="small"
              columns={savedColumns('local')} locale={{ emptyText: '当前浏览器暂无本地 SQL' }} />) },
        ]} />
      </Drawer>

      <Modal title="保存 SQL" open={saveOpen} onCancel={() => setSaveOpen(false)} onOk={handleSave} okText="保存">
        <Form form={saveForm} layout="vertical">
          <Form.Item name="location" label="保存位置" rules={[{ required: true }]}>
            <Select options={[
              { label: '服务端 SQL 库（登录后多端可用）', value: 'remote' },
              { label: '当前浏览器本地存储', value: 'local' },
            ]} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入 SQL 名称' }, { max: 128 }]}>
            <Input placeholder="例如：ODS 质量规则检查" />
          </Form.Item>
          <Form.Item name="description" label="描述"><Input.TextArea rows={2} maxLength={512} /></Form.Item>
          <Form.Item name="tags" label="标签"><Input placeholder="例如：ODS, 质量检查" maxLength={256} /></Form.Item>
        </Form>
      </Modal>

      <Modal title={`Doris Query Profile${profileData?.queryId ? ` · ${profileData.queryId}` : ''}`}
        open={profileOpen} onCancel={() => setProfileOpen(false)} footer={<Button onClick={() => setProfileOpen(false)}>关闭</Button>}
        width={1100}>
        {profileLoading ? <Spin /> : <pre style={{ maxHeight: '70vh', overflow: 'auto', padding: 16, background: '#111827', color: '#d1d5db', borderRadius: 8, whiteSpace: 'pre-wrap' }}>
          {profileData?.profile || 'Profile 不可用'}
        </pre>}
      </Modal>
    </PageContainer>
  );
};

export default AdhocQuery;
