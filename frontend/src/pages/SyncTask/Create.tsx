import React, { useState, useEffect, useRef } from 'react';
import { PageContainer, ProForm, ProFormSelect, ProFormText, ProFormTextArea, ProFormRadio } from '@ant-design/pro-components';
import { Card, Steps, Button, Space, Input, Select, Table, Tag, message, Divider, Modal, Form } from 'antd';
import { history } from '@umijs/max';
import { createSyncTask } from '@/api';
import { getDatasources, getIntrospectTables, getIntrospectTable, previewCdcSql } from '@/api';

const SyncTaskCreate: React.FC = () => {
  const [currentStep, setCurrentStep] = useState(0);
  const [formData, setFormData] = useState<any>({
    taskName: '',
    taskType: 'cdc_sync',
    syncStrategy: 'full_then_incremental',
    sourceConfigId: undefined,
    targetConfigId: undefined,
    tableMappings: [],
    flinkSql: '',
  });

  const [datasourceList, setDatasourceList] = useState<API.DatasourceConfig[]>([]);

  // Table mapping Modal state
  const [mappingModalVisible, setMappingModalVisible] = useState(false);
  const [editingIndex, setEditingIndex] = useState(-1);
  const [form] = Form.useForm();

  // Introspection state
  const [sourceTables, setSourceTables] = useState<string[]>([]);
  const [sourceColumns, setSourceColumns] = useState<any[]>([]);
  const [introspecting, setIntrospecting] = useState(false);
  const [sqlPreview, setSqlPreview] = useState('');
  const [previewVisible, setPreviewVisible] = useState(false);

  useEffect(() => {
    getDatasources().then((data) => {
      setDatasourceList(Array.isArray(data) ? data : []);
    });
  }, []);

  // Load source tables when datasource changes
  const handleSourceDsChange = async (value: number) => {
    setFormData((d: any) => ({ ...d, sourceConfigId: value }));
    setSourceTables([]);
    setSourceColumns([]);
    if (value) {
      setIntrospecting(true);
      try {
        const tables = await getIntrospectTables(value);
        setSourceTables(Array.isArray(tables) ? tables : []);
        message.success(`找到 ${(Array.isArray(tables) ? tables : []).length} 张表`);
      } catch {
        message.error('获取表列表失败');
      } finally {
        setIntrospecting(false);
      }
    }
  };

  // Load column structure for a source table
  const handleSourceTableSelect = async (value: string) => {
    if (!value) return;
    const dsId = formData.sourceConfigId;
    if (!dsId) {
      message.warning('请先选择源数据源');
      return;
    }
    setIntrospecting(true);
    try {
      const tableInfo = await getIntrospectTable(dsId, value);
      const columns = tableInfo?.columns || [];
      setSourceColumns(columns);
      message.success(`表 ${value} 结构已加载 (${columns.length} 列)`);
    } catch {
      message.error('获取表结构失败');
    } finally {
      setIntrospecting(false);
    }
  };

  // Preview CDC SQL via backend
  const handlePreviewSql = async () => {
    if (!formData.sourceConfigId || formData.tableMappings.length === 0) {
      message.warning('请先选择数据源并添加表映射');
      return;
    }
    try {
      const result = await previewCdcSql({
        taskName: formData.taskName,
        taskType: formData.taskType,
        syncStrategy: formData.syncStrategy,
        sourceConfigId: formData.sourceConfigId,
        targetConfigId: formData.targetConfigId,
        tableMappings: JSON.stringify(formData.tableMappings),
      });
      setSqlPreview(result?.sql || '');
      setPreviewVisible(true);
    } catch (e: any) {
      message.error(e?.message || '预览失败');
    }
  };

  // Apply previewed SQL
  const handleApplyPreview = () => {
    setFormData((d: any) => ({ ...d, flinkSql: sqlPreview }));
    setPreviewVisible(false);
    message.success('SQL 已应用到编辑器');
  };

  const dbTypeLabel = (type: string) => {
    const map: Record<string, string> = { mysql: 'MySQL', postgresql: 'PostgreSQL', paimon: 'Paimon' };
    return map[type] || type;
  };

  // Open Modal for adding a new mapping
  const openAddMapping = () => {
    setEditingIndex(-1);
    form.resetFields();
    form.setFieldsValue({ targetDb: 'ods', syncMode: 'full+incremental' });
    setMappingModalVisible(true);
  };

  // Open Modal for editing an existing mapping
  const openEditMapping = (index: number) => {
    setEditingIndex(index);
    const mapping = formData.tableMappings[index];
    form.setFieldsValue(mapping);
    setMappingModalVisible(true);
  };

  // Save mapping (add or edit)
  const handleSaveMapping = async () => {
    try {
      const values = await form.validateFields();
      const mappings = [...formData.tableMappings];
      if (editingIndex >= 0) {
        mappings[editingIndex] = values;
      } else {
        mappings.push(values);
      }
      setFormData((d: any) => ({ ...d, tableMappings: mappings }));
      setMappingModalVisible(false);
      message.success(editingIndex >= 0 ? '编辑成功' : '添加成功');
    } catch {
      // Validation failed, do nothing
    }
  };

  // Delete mapping
  const handleDeleteMapping = (index: number) => {
    Modal.confirm({
      title: '确认删除',
      content: `确定要删除表映射 "${formData.tableMappings[index].sourceTable}" 吗？`,
      okText: '删除',
      okType: 'danger',
      onOk: () => {
        const mappings = [...formData.tableMappings];
        mappings.splice(index, 1);
        setFormData((d: any) => ({ ...d, tableMappings: mappings }));
        message.success('删除成功');
      },
    });
  };

  // Auto-fill target table name based on source table + target layer
  const handleTargetDbChange = (value: string) => {
    if (editingIndex < 0) {
      const sourceTable = form.getFieldValue('sourceTable');
      if (sourceTable) {
        const targetTable = `${value}_${sourceTable}`;
        form.setFieldValue('targetTable', targetTable);
      }
    }
  };

  const handleCreate = async () => {
    try {
      if (!formData.taskName?.trim()) return message.warning('请输入任务名称');
      if (!formData.sourceConfigId || !formData.targetConfigId) return message.warning('请选择源数据源和目标数据源');
      if (formData.taskType === 'cdc_sync' && formData.tableMappings.length === 0) return message.warning('请至少添加一张表映射');
      if (!formData.flinkSql?.trim()) return message.warning('请生成或填写 Flink SQL');
      const payload = {
        taskName: formData.taskName,
        taskType: formData.taskType,
        sourceConfigId: formData.sourceConfigId,
        targetConfigId: formData.targetConfigId,
        flinkSql: formData.flinkSql,
        syncStrategy: formData.syncStrategy,
        tableMappings: JSON.stringify(formData.tableMappings),
        parallelism: 1,
        checkpointIntervalMs: 60000,
      };
      const createdTask = await createSyncTask(payload);
      message.success('同步任务创建成功');
      history.push(`/sync-task/detail/${createdTask.id}`);
    } catch (e: any) {
      message.error(e?.message || '创建异常');
    }
  };

  const stepsContent = [
    // Step 1: Basic Info
    <Card title="基本配置">
      <ProForm
        onFinish={undefined}
        submitter={false}
        onValuesChange={(values) => setFormData((d: any) => ({ ...d, ...values }))}
      >
        <ProFormText
          name="taskName"
          label="任务名称"
          placeholder="例如: cdc_orders_to_ods"
          rules={[{ required: true, message: '请输入任务名称' }]}
          width="md"
        />
        <ProFormSelect
          name="taskType"
          label="任务类型"
          options={[
            { label: 'CDC 同步（源库 → Paimon ODS）', value: 'cdc_sync' },
            { label: 'ETL 转换（ODS → DWD/DWS）', value: 'etl' },
            { label: '物化表（Flink 2.x Materialized Table）', value: 'materialized' },
          ]}
          rules={[{ required: true }]}
          width="md"
        />
        <ProFormRadio.Group
          name="syncStrategy"
          label="同步策略"
          options={[
            { label: '全量初始化 + 增量断点续传', value: 'full_then_incremental' },
            { label: '仅增量同步', value: 'incremental_only' },
          ]}
          rules={[{ required: true }]}
          width="md"
        />
      </ProForm>
    </Card>,

    // Step 2: Datasource & Table Mapping
    <Card title="数据源与表映射">
      <Divider orientation="left">源库配置</Divider>
      <Space style={{ width: '100%', marginBottom: 16 }} direction="vertical">
        <Select
          placeholder="选择源数据源"
          style={{ width: '100%' }}
          value={formData.sourceConfigId}
          onChange={handleSourceDsChange}
          loading={introspecting}
          options={datasourceList
            .filter((ds) => ds.dbType === 'mysql' || ds.dbType === 'postgresql')
            .map((ds) => ({
              label: `${ds.configName} (${dbTypeLabel(ds.dbType)} · ${ds.host}:${ds.port}/${ds.database})`,
              value: ds.id,
            }))}
        />
        {sourceTables.length > 0 && (
          <div>
            <div style={{ fontWeight: 500, marginBottom: 4 }}>源库表列表 ({sourceTables.length} 张)</div>
            <Select
              mode="multiple"
              placeholder="选择要同步的表"
              style={{ width: '100%' }}
              value={formData.tableMappings.map((m: any) => m.sourceTable)}
              onChange={(values) => {
                // Auto-add mappings for selected tables
                const mappings = values.map((t: string) => ({
                  sourceTable: t,
                  targetDb: 'ods',
                  targetTable: `ods_${t}`,
                  syncMode: 'full+incremental',
                }));
                setFormData((d: any) => ({ ...d, tableMappings: mappings }));
                if (values.length > 0) handleSourceTableSelect(values[0]);
                else setSourceColumns([]);
              }}
              options={sourceTables.map((t) => ({ label: t, value: t }))}
            />
          </div>
        )}
        {sourceColumns.length > 0 && (
          <div>
            <div style={{ fontWeight: 500, marginBottom: 4 }}>
              表结构预览: {sourceTables.find(t => sourceColumns.some(c => c.name === t)) || '—'}
            </div>
            <Table
              dataSource={sourceColumns}
              rowKey="name"
              size="small"
              pagination={false}
              columns={[
                { title: '字段名', dataIndex: 'name', key: 'name', width: 200 },
                { title: '类型', dataIndex: 'type', key: 'type', width: 150 },
                {
                  title: '主键',
                  dataIndex: 'primaryKey',
                  key: 'pk',
                  width: 60,
                  render: (v: boolean) => v ? <Tag color="green">PK</Tag> : '',
                },
                {
                  title: '可空',
                  dataIndex: 'nullable',
                  key: 'nullable',
                  width: 60,
                  render: (v: boolean) => v ? '✓' : '',
                },
                { title: '注释', dataIndex: 'comment', key: 'comment', ellipsis: true },
              ]}
            />
          </div>
        )}
      </Space>

      <Divider orientation="left">目标配置</Divider>
      <Space style={{ width: '100%', marginBottom: 16 }} direction="vertical">
        <Select
          placeholder="选择目标数据源 (Paimon)"
          style={{ width: '100%' }}
          value={formData.targetConfigId}
          onChange={(v) => setFormData((d: any) => ({ ...d, targetConfigId: v }))}
          options={datasourceList
            .filter((ds) => ds.dbType === 'paimon')
            .map((ds) => ({
              label: `${ds.configName} (Paimon · ${ds.host})`,
              value: ds.id,
            }))}
        />
      </Space>

      <Divider orientation="left">表映射配置</Divider>
      <Table
        dataSource={formData.tableMappings}
        rowKey={(_, index) => `mapping-${index}`}
        size="small"
        pagination={false}
        columns={[
          { title: '源表', dataIndex: 'sourceTable', key: 'src' },
          {
            title: '目标分层',
            dataIndex: 'targetDb',
            key: 'layer',
            render: (v: string) => <Tag color={{ ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red' }[v]}>{v.toUpperCase()}</Tag>,
          },
          { title: '目标表名', dataIndex: 'targetTable', key: 'tgt' },
          {
            title: '同步模式',
            dataIndex: 'syncMode',
            key: 'mode',
            render: (v: string) => <Tag color="blue">{v}</Tag>,
          },
          {
            title: '操作',
            key: 'action',
            render: (_: any, __: any, index: number) => (
              <Space>
                <Button size="small" type="link" onClick={() => openEditMapping(index)}>编辑</Button>
                <Button size="small" type="link" danger onClick={() => handleDeleteMapping(index)}>删除</Button>
              </Space>
            ),
          },
        ]}
      />
      <Button type="dashed" icon={<span style={{ fontSize: 16 }}>+</span>} style={{ marginTop: 8 }} onClick={openAddMapping}>
        添加表映射
      </Button>

      {/* Table Mapping Modal */}
      <Modal
        title={editingIndex >= 0 ? '编辑表映射' : '添加表映射'}
        open={mappingModalVisible}
        onOk={handleSaveMapping}
        onCancel={() => setMappingModalVisible(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item label="源表名" name="sourceTable" rules={[{ required: true, message: '请输入源表名' }]}>
            <Select
              placeholder="从源库选择或手动输入"
              showSearch
              filterOption={(input, option) => (option?.label ?? '').toLowerCase().includes(input.toLowerCase())}
              options={sourceTables.map(t => ({ label: t, value: t }))}
            />
          </Form.Item>
          <Form.Item label="目标分层" name="targetDb" rules={[{ required: true, message: '请选择目标分层' }]}>
            <Select
              placeholder="选择目标分层"
              onChange={handleTargetDbChange}
              options={[
                { label: 'ODS（原始数据层）', value: 'ods' },
                { label: 'DWD（明细数据层）', value: 'dwd' },
                { label: 'DWS（汇总数据层）', value: 'dws' },
                { label: 'ADS（应用数据层）', value: 'ads' },
              ]}
            />
          </Form.Item>
          <Form.Item label="目标表名" name="targetTable" rules={[{ required: true, message: '请输入目标表名' }]}>
            <Input placeholder="例如: ods_orders" />
          </Form.Item>
          <Form.Item label="同步模式" name="syncMode" rules={[{ required: true, message: '请选择同步模式' }]}>
            <Select
              placeholder="选择同步模式"
              options={[
                { label: '全量 + 增量', value: 'full+incremental' },
                { label: '仅增量', value: 'incremental' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>,

    // Step 3: Flink SQL
    <Card title="Flink SQL 定义">
      <div style={{ marginBottom: 12 }}>
        <Space>
          <Button type="primary" onClick={handlePreviewSql}>
            预览 CDC SQL
          </Button>
          <span style={{ color: '#888', fontSize: 12 }}>
            后端将根据表结构和数据源配置自动生成 CDC SQL
          </span>
        </Space>
      </div>
      <textarea
        value={formData.flinkSql}
        onChange={(e) => setFormData((d: any) => ({ ...d, flinkSql: e.target.value }))}
        style={{
          width: '100%',
          minHeight: 300,
          background: '#1e1e1e',
          color: '#d4d4d4',
          padding: 16,
          borderRadius: 8,
          fontFamily: 'Courier New, monospace',
          fontSize: 13,
          lineHeight: 1.6,
          border: '1px solid #333',
          resize: 'vertical',
        }}
        placeholder="预览 CDC SQL 后将显示在这里，可手动修改..."
      />
    </Card>,
  ];

  return (
    <PageContainer
      extra={
        <Space>
          <Button onClick={() => history.push('/sync-task/list')}>取消</Button>
          {currentStep > 0 && <Button onClick={() => setCurrentStep(currentStep - 1)}>上一步</Button>}
          {currentStep < stepsContent.length - 1 && (
            <Button type="primary" onClick={() => setCurrentStep(currentStep + 1)}>下一步</Button>
          )}
          {currentStep === stepsContent.length - 1 && (
            <Button type="primary" onClick={handleCreate}>创建任务</Button>
          )}
        </Space>
      }
    >
      <Steps
        current={currentStep}
        items={[
          { title: '基本配置', description: '任务名称、类型、策略' },
          { title: '数据源与表映射', description: '源库、目标库、表映射' },
          { title: 'Flink SQL', description: 'SQL 生成与确认' },
        ]}
        style={{ marginBottom: 24 }}
      />
      {stepsContent[currentStep]}

      {/* SQL Preview Modal */}
      <Modal
        title="CDC SQL 预览"
        open={previewVisible}
        onCancel={() => setPreviewVisible(false)}
        footer={[
          <Button key="cancel" onClick={() => setPreviewVisible(false)}>关闭</Button>,
          <Button key="apply" type="primary" onClick={handleApplyPreview}>应用到编辑器</Button>,
        ]}
        width={900}
      >
        <pre style={{
          background: '#1e1e1e',
          color: '#d4d4d4',
          padding: 16,
          borderRadius: 8,
          fontFamily: 'Courier New, monospace',
          fontSize: 13,
          lineHeight: 1.6,
          maxHeight: 600,
          overflow: 'auto',
          whiteSpace: 'pre-wrap',
        }}>
          {sqlPreview || '—'}
        </pre>
      </Modal>
    </PageContainer>
  );
};

export default SyncTaskCreate;
