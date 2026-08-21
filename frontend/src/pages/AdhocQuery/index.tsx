import React, { useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Button, Card, Col, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Row,
  Select, Space, Table, Tabs, Tag, Tree, Typography, message,
} from 'antd';
import {
  CloudOutlined, DatabaseOutlined, DeleteOutlined, DownloadOutlined, FolderOpenOutlined,
  LaptopOutlined, PlayCircleOutlined, SaveOutlined,
} from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import type { editor } from 'monaco-editor';
import {
  cancelQuery, cancelQueryByRequestId, createSavedQuery, deleteSavedQuery, executeQuery,
  exportQuery, getQueryCatalog, getQueryHistory, getSavedQueries, updateSavedQuery,
} from '@/api';
import SqlEditor from './SqlEditor';

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

const AdhocQuery: React.FC = () => {
  const [sql, setSql] = useState(() => localStorage.getItem(CURRENT_DRAFT_KEY) || '');
  const [maxRows, setMaxRows] = useState(1000);
  const [result, setResult] = useState<API.QueryResult | null>(null);
  const [executing, setExecuting] = useState(false);
  const [historyId, setHistoryId] = useState<number>();
  const [requestId, setRequestId] = useState<string>();
  const [libraryOpen, setLibraryOpen] = useState(false);
  const [saveOpen, setSaveOpen] = useState(false);
  const [localQueries, setLocalQueries] = useState<LocalQuery[]>(readLocalQueries);
  const [activeQuery, setActiveQuery] = useState<ActiveQuery>();
  const [saveForm] = Form.useForm();
  const editorRef = useRef<editor.IStandaloneCodeEditor>();

  const { data: catalogData } = useRequest(getQueryCatalog);
  const catalog = catalogData as API.QueryCatalog | undefined;
  const { data: savedData, loading: savedLoading, refresh: refreshSaved } = useRequest(getSavedQueries);
  const savedQueries = (savedData || []) as API.SavedQuery[];
  const { data: historyPageData, loading: historyLoading, run: loadHistory } = useRequest(
    getQueryHistory, { defaultParams: [{ page: 0, size: 20 }] },
  );
  const historyPage = historyPageData as API.PageResult<any> | undefined;
  const historyList = historyPage?.content || [];

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
        title: `${table.name}  [${table.layer.toUpperCase()}]`,
        key: `table:${database.name}.${table.name}`,
        children: table.columns.map((column) => ({
          title: `${column.name}  ${column.type}${column.primaryKey ? '  PK' : ''}`,
          key: `column:${database.name}.${table.name}.${column.name}`,
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
      const queryResult = await executeQuery({ sql, maxRows, requestId: currentRequestId });
      setResult(queryResult);
      setHistoryId(queryResult.historyId);
      loadHistory({ page: 0, size: historyPage?.size || 20 });
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
      const blob = await exportQuery({ sql, maxRows });
      const url = URL.createObjectURL(blob as Blob);
      const link = document.createElement('a');
      link.href = url; link.download = 'query-result.csv'; link.click(); URL.revokeObjectURL(url);
    } catch { message.error('导出失败'); }
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
    <PageContainer title="即席查询" subTitle="Paimon Catalog 智能提示、查询执行与 SQL 资产管理">
      <Card title={activeQuery ? `SQL 编辑器 · ${activeQuery.name}` : 'SQL 编辑器'}
        extra={<Space>
          <Tag color={catalog ? 'green' : 'orange'}>{catalog ? `Catalog: ${catalog.catalogName}` : 'Catalog 加载中'}</Tag>
          <Button icon={<FolderOpenOutlined />} onClick={() => setLibraryOpen(true)}>SQL 库</Button>
          <Button icon={<SaveOutlined />} onClick={openSave}>保存 SQL</Button>
        </Space>}>
        <Space wrap style={{ marginBottom: 12 }}>
          <Select value="paimon-flink-sql" style={{ width: 240 }} options={[
            { label: 'Paimon · Flink SQL Gateway', value: 'paimon-flink-sql' },
          ]} />
          <InputNumber min={1} max={50000} value={maxRows} onChange={(value) => setMaxRows(value || 1000)}
            addonBefore="最大行数" style={{ width: 190 }} />
          <Button type="primary" icon={<PlayCircleOutlined />} loading={executing} onClick={handleExecute}>执行查询</Button>
          <Button danger disabled={!executing || (!requestId && !historyId)} onClick={handleCancel}>取消查询</Button>
          <Button icon={<DownloadOutlined />} onClick={handleExport}>导出 CSV</Button>
          <Typography.Text type="secondary">⌘/Ctrl + Enter 执行，输入 <Typography.Text code>paimon.</Typography.Text> 查看 Catalog 提示</Typography.Text>
        </Space>

        <Row gutter={12}>
          <Col flex="260px">
            <div style={{ height: 300, overflow: 'auto', border: '1px solid #e8e8e8', borderRadius: 6, padding: 8 }}>
              <Typography.Text strong>Catalog 资源</Typography.Text>
              <Tree showLine showIcon defaultExpandAll treeData={catalogTree} onSelect={handleCatalogSelect}
                style={{ marginTop: 8 }} />
            </div>
          </Col>
          <Col flex="auto">
            <div style={{ overflow: 'hidden', borderRadius: 6 }}>
              <SqlEditor value={sql} catalog={catalog} onChange={setSql} onExecute={handleExecute}
                onReady={(instance) => { editorRef.current = instance; }} />
            </div>
          </Col>
        </Row>
      </Card>

      {result && (
        <Card title={`查询结果（耗时 ${result.durationMs || 0}ms · 返回 ${result.rowCount || 0} 行）`}>
          {result.status !== 'success' && <Alert type="error" message={result.errorMsg || '查询失败'} showIcon style={{ marginBottom: 12 }} />}
          {result.truncated && <Alert type="warning" message="结果已达到最大返回行数，请增加限制或导出 CSV" showIcon style={{ marginBottom: 12 }} />}
          <Table<Record<string, any>>
            dataSource={(result.rows || []).map((row, index) => ({ key: index,
              ...Object.fromEntries((result.columns || []).map((column, columnIndex) => [column, row[columnIndex]])) }))}
            columns={(result.columns || []).map((column) => ({ title: column, dataIndex: column, key: column, ellipsis: true }))}
            size="small" scroll={{ x: 'max-content', y: 380 }} pagination={false} />
        </Card>
      )}

      <Card title="查询历史">
        <Table dataSource={historyList} rowKey="id" size="small" loading={historyLoading}
          pagination={{ total: historyPage?.totalElements || 0, pageSize: historyPage?.size || 20,
            current: (historyPage?.number || 0) + 1, showSizeChanger: true,
            onChange: (page, pageSize) => loadHistory({ page: page - 1, size: pageSize }) }}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 190, render: formatDateTime },
            { title: 'SQL', dataIndex: 'sqlText', ellipsis: true },
            { title: '行数', dataIndex: 'resultRowCount', width: 80 },
            { title: '耗时', dataIndex: 'durationMs', width: 90, render: (value) => `${value || 0}ms` },
            { title: '状态', dataIndex: 'status', width: 100, render: (value) => <Tag color={{
              success: 'green', failed: 'red', cancelled: 'orange', running: 'blue',
            }[value as string]}>{value}</Tag> },
            { title: '操作', width: 90, render: (_, record: any) => (
              <Button type="link" onClick={() => { setSql(record.sqlText); setActiveQuery(undefined); }}>载入</Button>) },
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
    </PageContainer>
  );
};

export default AdhocQuery;
