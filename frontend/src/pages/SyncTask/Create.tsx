import React, { useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Button, Card, Col, Collapse, Descriptions, Empty, Form, Input, InputNumber,
  message, Modal, Row, Select, Space, Spin, Steps, Table, Tag, Typography,
} from 'antd';
import {
  ApiOutlined, ArrowLeftOutlined, ArrowRightOutlined, CheckCircleOutlined,
  EditOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined, SaveOutlined,
} from '@ant-design/icons';
import { history, useAccess } from '@umijs/max';
import {
  createSyncTask, getDatasources, getIntrospectTables, previewCdcSql, startSyncTask,
} from '@/api';
import {
  defaultTaskScenario, getTaskScenario, taskScenarioGroups, taskScenarios,
  taskTypeLabel, type ExecutionMode, type TaskScenario, type TaskType,
} from './scenarios';
import './index.less';

type TableMapping = {
  sourceTable: string;
  targetDb: string;
  targetTable: string;
  syncMode: string;
};

type CreateFormData = {
  taskName: string;
  description: string;
  scenarioCode: string;
  taskType: TaskType;
  executionMode: ExecutionMode;
  syncStrategy: 'full_then_incremental' | 'incremental_only';
  sourceConfigId?: number;
  tableMappings: TableMapping[];
  flinkSql: string;
  parallelism: number;
  checkpointIntervalMs: number;
};

const layerColorMap: Record<string, string> = {
  ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
};

const dbTypeLabel = (type?: string) => ({
  mysql: 'MySQL', postgresql: 'PostgreSQL', paimon: 'Paimon',
}[type || ''] || type || '未知');

const datasourceLabel = (datasource: API.DatasourceConfig) => (
  `${datasource.configName}（${dbTypeLabel(datasource.dbType)} · ${datasource.host}:${datasource.port}/${datasource.database}）`
);

const SyncTaskCreate: React.FC = () => {
  const access = useAccess();
  const [currentStep, setCurrentStep] = useState(0);
  const [formData, setFormData] = useState<CreateFormData>({
    taskName: '',
    description: '',
    scenarioCode: defaultTaskScenario.code,
    taskType: defaultTaskScenario.taskType,
    executionMode: defaultTaskScenario.executionMode,
    syncStrategy: 'full_then_incremental',
    sourceConfigId: undefined,
    tableMappings: [],
    flinkSql: '',
    parallelism: 1,
    checkpointIntervalMs: 60_000,
  });
  const [datasourceList, setDatasourceList] = useState<API.DatasourceConfig[]>([]);
  const [datasourceLoading, setDatasourceLoading] = useState(true);
  const [sourceTables, setSourceTables] = useState<string[]>([]);
  const [introspecting, setIntrospecting] = useState(false);
  const [tableLoaded, setTableLoaded] = useState(false);
  const [tableLoadError, setTableLoadError] = useState('');
  const [generatingSql, setGeneratingSql] = useState(false);
  const [submitAction, setSubmitAction] = useState<'draft' | 'start'>();
  const [mappingModalOpen, setMappingModalOpen] = useState(false);
  const [editingIndex, setEditingIndex] = useState(-1);
  const [mappingInitialValues, setMappingInitialValues] = useState<Partial<TableMapping>>();
  const [mappingForm] = Form.useForm<TableMapping>();
  const tableLoadRequestRef = useRef(0);

  const selectedScenario = getTaskScenario(formData.scenarioCode) || defaultTaskScenario;
  const isCdc = selectedScenario.inputMode === 'tables';
  const isDatabaseCdc = selectedScenario.tableSelection === 'all';
  const sourceOptions = useMemo(() => {
    if (selectedScenario.sourceTypes?.length) {
      return datasourceList.filter((item) => selectedScenario.sourceTypes?.includes(item.dbType));
    }
    return datasourceList;
  }, [datasourceList, selectedScenario.sourceTypes]);
  const selectedSource = datasourceList.find((item) => item.id === formData.sourceConfigId);

  useEffect(() => {
    getDatasources()
      .then((data) => {
        const list = Array.isArray(data) ? data : [];
        setDatasourceList(list);
      })
      .catch((error: any) => message.error(error?.message || '数据源加载失败'))
      .finally(() => setDatasourceLoading(false));
  }, []);

  const buildMappings = (
    tableNames: string[],
    existingMappings = formData.tableMappings,
    syncStrategy = formData.syncStrategy,
  ) => tableNames.map((sourceTable) => {
    const existing = existingMappings.find((item) => item.sourceTable === sourceTable);
    return existing || {
      sourceTable,
      targetDb: 'ods',
      targetTable: `ods_${sourceTable}`,
      syncMode: syncStrategy === 'incremental_only' ? 'incremental' : 'full+incremental',
    };
  });

  const changeScenario = (scenario: TaskScenario) => {
    if (scenario.status !== 'available') {
      message.info(scenario.status === 'experimental' ? '该能力尚未完成验收，暂不开放新建' : `${scenario.title}正在规划中`);
      return;
    }
    const switchingWithinCdc = formData.taskType === 'cdc_sync' && scenario.taskType === 'cdc_sync';
    const selectedTables = scenario.tableSelection === 'all' ? sourceTables : [];
    setFormData((previous) => ({
      ...previous,
      scenarioCode: scenario.code,
      taskType: scenario.taskType,
      executionMode: scenario.executionMode,
      sourceConfigId: scenario.taskType === 'cdc_sync'
        ? (scenario.sourceTypes?.includes(selectedSource?.dbType || '') ? previous.sourceConfigId : undefined)
        : undefined,
      tableMappings: switchingWithinCdc ? buildMappings(selectedTables, previous.tableMappings, previous.syncStrategy) : [],
      flinkSql: '',
      taskName: scenario.tableSelection === 'all' && previous.sourceConfigId
        ? `cdc_${selectedSource?.database || 'database'}_to_ods`
        : '',
      syncStrategy: scenario.taskType === 'cdc_sync' ? previous.syncStrategy : 'full_then_incremental',
    }));
    setTableLoadError('');
    if (!switchingWithinCdc) {
      tableLoadRequestRef.current += 1;
      setSourceTables([]);
      setTableLoaded(false);
    }
  };

  const handleSourceChange = async (sourceConfigId: number) => {
    setFormData((previous) => ({ ...previous, sourceConfigId, tableMappings: [], flinkSql: '' }));
    setSourceTables([]);
    setTableLoaded(false);
    setTableLoadError('');
    if (!isCdc) return;
    const requestId = ++tableLoadRequestRef.current;
    setIntrospecting(true);
    try {
      const tables = await getIntrospectTables(sourceConfigId);
      if (requestId !== tableLoadRequestRef.current) return;
      const tableList = Array.isArray(tables) ? tables : [];
      setSourceTables(tableList);
      setTableLoaded(true);
      if (isDatabaseCdc) {
        const source = datasourceList.find((item) => item.id === sourceConfigId);
        setFormData((previous) => ({
          ...previous,
          tableMappings: buildMappings(tableList, [], previous.syncStrategy),
          flinkSql: '',
          taskName: previous.taskName || `cdc_${source?.database || 'database'}_to_ods`,
        }));
      }
    } catch (error: any) {
      if (requestId !== tableLoadRequestRef.current) return;
      setTableLoaded(false);
      setTableLoadError(
        error?.data?.message
        || error?.response?.data?.message
        || error?.message
        || '源库表读取失败，请检查数据源连接',
      );
    } finally {
      if (requestId === tableLoadRequestRef.current) setIntrospecting(false);
    }
  };

  const reloadSourceTables = () => {
    if (formData.sourceConfigId) handleSourceChange(formData.sourceConfigId);
  };

  const changeSelectedTables = (tableNames: string[]) => {
    const mappings = buildMappings(tableNames);
    setFormData((previous) => ({
      ...previous,
      tableMappings: mappings,
      flinkSql: '',
      taskName: previous.taskName || (tableNames[0] ? `cdc_${tableNames[0]}_to_ods` : ''),
    }));
  };

  const openMappingModal = (index = -1) => {
    setEditingIndex(index);
    const initial = index >= 0 ? formData.tableMappings[index] : {
      sourceTable: undefined,
      targetDb: 'ods',
      targetTable: '',
      syncMode: formData.syncStrategy === 'incremental_only' ? 'incremental' : 'full+incremental',
    };
    setMappingInitialValues(initial);
    setMappingModalOpen(true);
  };

  const handleMappingSourceChange = (sourceTable: string) => {
    const targetDb = mappingForm.getFieldValue('targetDb') || 'ods';
    mappingForm.setFieldValue('targetTable', `${targetDb}_${sourceTable}`);
  };

  const handleMappingLayerChange = (targetDb: string) => {
    const sourceTable = mappingForm.getFieldValue('sourceTable');
    if (sourceTable) mappingForm.setFieldValue('targetTable', `${targetDb}_${sourceTable}`);
  };

  const saveMapping = async () => {
    try {
      const values = await mappingForm.validateFields();
      const mappings = [...formData.tableMappings];
      if (editingIndex >= 0) mappings[editingIndex] = values;
      else if (mappings.some((item) => item.sourceTable === values.sourceTable)) {
        message.warning('该源表已经添加');
        return;
      } else mappings.push(values);
      setFormData((previous) => ({ ...previous, tableMappings: mappings, flinkSql: '' }));
      setMappingModalOpen(false);
    } catch {
      // Ant Design Form displays validation errors.
    }
  };

  const deleteMapping = (index: number) => {
    const mappings = formData.tableMappings.filter((_, currentIndex) => currentIndex !== index);
    setFormData((previous) => ({ ...previous, tableMappings: mappings, flinkSql: '' }));
  };

  const validateConfiguration = () => {
    if (!formData.taskName.trim()) {
      message.warning('请填写任务名称');
      return false;
    }
    if (isCdc && !formData.sourceConfigId) {
      message.warning('请选择业务源库');
      return false;
    }
    if (isCdc && formData.tableMappings.length === 0) {
      message.warning('请至少选择一张需要同步的源表');
      return false;
    }
    if (!isCdc && !formData.flinkSql.trim()) {
      message.warning('请填写可执行的 Flink SQL');
      return false;
    }
    return true;
  };

  const generateCdcSql = async () => {
    setGeneratingSql(true);
    try {
      const result = await previewCdcSql({
        taskName: formData.taskName,
        scenarioCode: formData.scenarioCode,
        taskType: formData.taskType,
        executionMode: formData.executionMode,
        syncStrategy: formData.syncStrategy,
        sourceConfigId: formData.sourceConfigId,
        tableMappings: formData.tableMappings,
        parallelism: formData.parallelism,
        checkpointIntervalMs: formData.checkpointIntervalMs,
      });
      const sql = result?.sql || '';
      setFormData((previous) => ({ ...previous, flinkSql: sql }));
      return sql;
    } catch (error: any) {
      message.error(error?.message || 'CDC SQL 生成失败，请检查表映射和数据源配置');
      return '';
    } finally {
      setGeneratingSql(false);
    }
  };

  const goToReview = async () => {
    if (!validateConfiguration()) return;
    if (isCdc && !(await generateCdcSql())) return;
    setCurrentStep(1);
  };

  const handleCreate = async (startImmediately: boolean) => {
    if (!validateConfiguration()) {
      setCurrentStep(0);
      return;
    }
    if (isCdc && !formData.flinkSql && !(await generateCdcSql())) {
      setCurrentStep(0);
      return;
    }

    setSubmitAction(startImmediately ? 'start' : 'draft');
    let createdTask: API.SyncTask | undefined;
    try {
      createdTask = await createSyncTask({
        taskName: formData.taskName.trim(),
        description: formData.description.trim(),
        scenarioCode: formData.scenarioCode,
        taskType: formData.taskType,
        executionMode: formData.executionMode,
        sourceConfigId: formData.sourceConfigId,
        flinkSql: formData.flinkSql,
        syncStrategy: formData.syncStrategy,
        tableMappings: JSON.stringify(formData.tableMappings),
        parallelism: formData.parallelism,
        checkpointIntervalMs: formData.checkpointIntervalMs,
      });

      if (!startImmediately) {
        message.success(formData.executionMode === 'scheduled'
          ? '周期任务草稿已创建，请发布版本后配置调度'
          : '任务已保存为草稿，可确认后再启动');
        history.push(formData.executionMode === 'scheduled' ? '/sync-task/workflow' : `/sync-task/detail/${createdTask.id}`);
        return;
      }

      if (formData.executionMode === 'scheduled') {
        message.success('周期任务草稿已创建，请前往任务编排发布版本并配置调度');
        history.push('/sync-task/workflow');
        return;
      }
      const startedTask = await startSyncTask(createdTask.id);
      if (startedTask.status === 'failed') {
        message.error(startedTask.lastErrorMsg || '任务已创建，但启动失败，请查看详情后重试');
      } else if (startedTask.status === 'running') {
        message.success('任务创建并启动成功');
      } else {
        message.info('任务已创建，正在提交到 Flink');
      }
      history.push(`/sync-task/detail/${createdTask.id}`);
    } catch (error: any) {
      if (createdTask) {
        message.warning('任务已创建，但启动请求未完成，可在详情页重新启动');
        history.push(`/sync-task/detail/${createdTask.id}`);
      } else {
        message.error(error?.message || '任务创建失败');
      }
    } finally {
      setSubmitAction(undefined);
    }
  };

  const mappingColumns: any[] = [
    { title: '源表', dataIndex: 'sourceTable', key: 'sourceTable' },
    {
      title: '目标表', key: 'targetTable',
      render: (_: unknown, record: TableMapping) => (
        <Space size={6}>
          <Tag color={layerColorMap[record.targetDb]}>{record.targetDb.toUpperCase()}</Tag>
          <span>{record.targetTable}</span>
        </Space>
      ),
    },
    {
      title: '操作', key: 'action', width: 130,
      render: (_: unknown, __: TableMapping, index: number) => currentStep === 0 ? (
        <Space size={4}>
          <Button size="small" type="link" icon={<EditOutlined />} onClick={() => openMappingModal(index)}>调整</Button>
          <Button size="small" type="link" danger onClick={() => deleteMapping(index)}>移除</Button>
        </Space>
      ) : null,
    },
  ];

  return (
    <PageContainer
      title="创建任务"
      subTitle="持续任务直接运行，周期任务发布版本后按实例调度"
      className="task-create-page"
      extra={<Button onClick={() => history.push('/sync-task/list')}>退出创建</Button>}
    >
      <Card className="task-create-steps-card">
        <Steps
          current={currentStep}
          responsive={false}
          items={[
            { title: '配置任务', description: '选择场景并填写必要配置' },
            { title: '确认并创建', description: '按运行方式进入对应闭环' },
          ]}
        />
      </Card>

      {currentStep === 0 ? (
        <div className="task-create-content">
          <Card
            title="1. 选择任务场景"
            className="task-create-section"
            extra={<Tag color="blue">{taskScenarios.filter((item) => item.status === 'available').length} 个场景已开放</Tag>}
          >
            <Alert
              className="task-scenario-guide"
              type="info"
              showIcon
              message="按业务目标选择场景，系统会自动切换对应的执行器与配置表单"
            />
            <div className="task-scenario-groups">
              {taskScenarioGroups.map((group) => {
                const scenarios = taskScenarios.filter((item) => item.category === group.key);
                return (
                  <section className="task-scenario-group" key={group.key}>
                    <div className="task-scenario-heading">
                      <b>{group.title}</b>
                      <span>{group.description}</span>
                    </div>
                    <div className="task-type-grid">
                      {scenarios.map((scenario) => {
                        const selected = formData.scenarioCode === scenario.code;
                        const planned = scenario.status !== 'available';
                        return (
                          <button
                            type="button"
                            key={scenario.code}
                            aria-pressed={selected}
                            aria-disabled={planned}
                            className={`task-type-option ${selected ? 'is-selected' : ''} ${planned ? 'is-planned' : ''}`}
                            onClick={() => changeScenario(scenario)}
                          >
                            <span className="task-type-icon">{scenario.icon}</span>
                            <span className="task-type-copy">
                              <span className="task-scenario-title">
                                <b>{scenario.title}</b>
                                {scenario.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
                              </span>
                              <strong>{scenario.description}</strong>
                              <small>{scenario.hint}</small>
                            </span>
                            {selected && <CheckCircleOutlined className="task-type-selected" />}
                          </button>
                        );
                      })}
                    </div>
                  </section>
                );
              })}
            </div>
          </Card>

          <Card title="2. 填写必要配置" className="task-create-section">
            {datasourceLoading ? <div className="task-create-loading"><Spin /> 正在加载数据源...</div> : (
              <>
                <Row gutter={[16, 0]}>
                  <Col xs={24} lg={12}>
                    <Form.Item label="任务名称" required>
                      <Input
                        value={formData.taskName}
                        maxLength={128}
                        showCount
                        placeholder={isCdc ? '选择源表后可自动生成' : '例如：dwd_order_detail_daily'}
                        onChange={(event) => setFormData((previous) => ({ ...previous, taskName: event.target.value }))}
                      />
                    </Form.Item>
                  </Col>
                  <Col xs={24} lg={12}>
                    <Form.Item label="任务说明">
                      <Input
                        value={formData.description}
                        placeholder="可选，说明用途或负责人"
                        onChange={(event) => setFormData((previous) => ({ ...previous, description: event.target.value }))}
                      />
                    </Form.Item>
                  </Col>
                </Row>

                {isCdc ? <Row gutter={[16, 0]}>
                  <Col xs={24} lg={12}>
                    <Form.Item label="业务源库" required>
                      <Select
                        showSearch
                        optionFilterProp="label"
                        value={formData.sourceConfigId}
                        placeholder={sourceOptions.length ? '请选择数据源' : '暂无可用数据源'}
                        loading={introspecting}
                        onChange={handleSourceChange}
                        options={sourceOptions.map((item) => ({ label: datasourceLabel(item), value: item.id }))}
                        notFoundContent={<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先创建可用数据源" />}
                      />
                    </Form.Item>
                  </Col>
                  <Col xs={24} lg={12}>
                    <Alert type="info" showIcon message="目标：平台 Paimon Catalog" description="Catalog、Metastore 与 Warehouse 由系统设置统一管理，无需为任务重复选择。" />
                  </Col>
                </Row> : (
                  <Alert type="info" showIcon message="运行环境：平台 Paimon Catalog" description={formData.executionMode === 'scheduled'
                    ? '该 SQL 按业务日期生成运行实例；发布版本后可配置调度、依赖和补数。'
                    : '该 SQL 作为持续 Flink Job 运行；Catalog、Metastore 与 Warehouse 使用平台统一配置。'} />
                )}

                {isCdc ? (
                  <div className="task-table-picker">
                    <div className="task-field-title">
                      <span><b>{isDatabaseCdc ? '确认整库同步范围' : '选择同步表'}</b><em>*</em></span>
                      {formData.sourceConfigId && (
                        <span className="task-table-discovery-status">
                          {!introspecting && tableLoaded && (
                            <small>源库共 {sourceTables.length} 张表，已选择 {formData.tableMappings.length} 张</small>
                          )}
                          <Button
                            type="link"
                            size="small"
                            icon={<ReloadOutlined spin={introspecting} />}
                            disabled={introspecting}
                            onClick={reloadSourceTables}
                          >
                            {introspecting ? '正在读取' : '重新读取'}
                          </Button>
                        </span>
                      )}
                    </div>
                    {tableLoadError && (
                      <Alert
                        className="task-table-load-alert"
                        type="error"
                        showIcon
                        message="数据表读取失败"
                        description={tableLoadError}
                        action={(
                          <Space direction="vertical" size={4}>
                            <Button size="small" danger onClick={reloadSourceTables}>重新读取</Button>
                            <Button size="small" type="link" onClick={() => history.push('/sync-task/datasource')}>检查数据源</Button>
                          </Space>
                        )}
                      />
                    )}
                    {!tableLoadError && tableLoaded && sourceTables.length === 0 && (
                      <Alert
                        className="task-table-empty-alert"
                        type="warning"
                        showIcon
                        message="连接成功，但当前数据库没有读取到基础表"
                        description="请确认数据库名称是否正确，并检查连接用户是否拥有 information_schema 和目标库的读取权限。"
                        action={<Button size="small" onClick={reloadSourceTables}>重新读取</Button>}
                      />
                    )}
                    {isDatabaseCdc && formData.sourceConfigId && tableLoaded && sourceTables.length > 0 && (
                      <Alert
                        className="task-database-sync-tip"
                        type="success"
                        showIcon
                        message="已自动选择当前源库全部表"
                        description="可以从下方选择框排除不需要同步的表；创建前会为保留表批量生成 ODS 映射。"
                      />
                    )}
                    <Select
                      mode="multiple"
                      showSearch
                      maxTagCount="responsive"
                      value={formData.tableMappings.map((item) => item.sourceTable)}
                      placeholder={formData.sourceConfigId
                        ? (isDatabaseCdc ? '默认选择全部表，可搜索并排除' : '搜索并选择需要实时同步的表')
                        : '请先选择业务源库'}
                      disabled={!formData.sourceConfigId}
                      loading={introspecting}
                      onChange={changeSelectedTables}
                      options={sourceTables.map((tableName) => ({ label: tableName, value: tableName }))}
                      notFoundContent={formData.sourceConfigId && !introspecting
                        ? <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description={tableLoadError ? '表列表读取失败，请先重试' : '未读取到可同步的数据表'}
                          />
                        : undefined}
                    />
                    {formData.tableMappings.length > 0 ? (
                      <Table<TableMapping>
                        className="task-mapping-table"
                        rowKey="sourceTable"
                        size="small"
                        pagination={false}
                        dataSource={formData.tableMappings}
                        columns={mappingColumns}
                        scroll={{ x: 620 }}
                      />
                    ) : (
                      <div className={`task-mapping-empty ${tableLoadError ? 'is-error' : ''}`}>
                        {tableLoadError
                          ? '数据源探测失败，修复连接后点击“重新读取”'
                          : (tableLoaded && sourceTables.length === 0
                              ? '当前数据库没有发现可同步的基础表'
                              : '选择源表后，系统会自动映射为 ODS 目标表')}
                      </div>
                    )}
                    <Button type="dashed" icon={<PlusOutlined />} onClick={() => openMappingModal()}>
                      手动添加表映射
                    </Button>
                  </div>
                ) : (
                  <Form.Item label="Flink SQL" required className="task-sql-form-item">
                    <Input.TextArea
                      className="task-sql-editor"
                      value={formData.flinkSql}
                      rows={12}
                      spellCheck={false}
                      placeholder={formData.taskType === 'materialized'
                        ? '请输入物化表定义或 Flink SQL...'
                        : '请输入 INSERT INTO ... SELECT ... Flink SQL...'}
                      onChange={(event) => setFormData((previous) => ({ ...previous, flinkSql: event.target.value }))}
                    />
                  </Form.Item>
                )}

                <Collapse
                  ghost
                  className="task-advanced-settings"
                  items={[{
                    key: 'advanced',
                    label: '高级运行参数（使用默认值即可直接创建）',
                    children: (
                      <Row gutter={[16, 0]}>
                        {isCdc && (
                          <Col xs={24} md={8}>
                            <Form.Item label="同步策略">
                              <Select
                                value={formData.syncStrategy}
                                onChange={(syncStrategy) => setFormData((previous) => ({
                                  ...previous,
                                  syncStrategy,
                                  flinkSql: '',
                                  tableMappings: previous.tableMappings.map((item) => ({
                                    ...item,
                                    syncMode: syncStrategy === 'incremental_only' ? 'incremental' : 'full+incremental',
                                  })),
                                }))}
                                options={[
                                  { label: '首次全量，之后持续增量', value: 'full_then_incremental' },
                                  { label: '仅从最新位点开始增量', value: 'incremental_only' },
                                ]}
                              />
                            </Form.Item>
                          </Col>
                        )}
                        <Col xs={24} md={8}>
                          <Form.Item label="并行度">
                            <InputNumber
                              min={1}
                              max={128}
                              value={formData.parallelism}
                              onChange={(value) => setFormData((previous) => ({ ...previous, parallelism: value || 1, flinkSql: '' }))}
                              style={{ width: '100%' }}
                            />
                          </Form.Item>
                        </Col>
                        <Col xs={24} md={8}>
                          <Form.Item label="Checkpoint 间隔">
                            <Select
                              value={formData.checkpointIntervalMs}
                              onChange={(checkpointIntervalMs) => setFormData((previous) => ({ ...previous, checkpointIntervalMs, flinkSql: '' }))}
                              options={[
                                { label: '30 秒', value: 30_000 },
                                { label: '1 分钟（推荐）', value: 60_000 },
                                { label: '5 分钟', value: 300_000 },
                              ]}
                            />
                          </Form.Item>
                        </Col>
                      </Row>
                    ),
                  }]}
                />
              </>
            )}
          </Card>
        </div>
      ) : (
        <div className="task-review-layout">
          <div className="task-review-main">
            <Alert
              type="success"
              showIcon
              message="配置检查通过，可以创建任务"
              description={isCdc
                ? 'CDC SQL 已根据源表结构自动生成，启动时会再次按最新 Schema 生成。'
                : formData.executionMode === 'scheduled'
                  ? '请确认 SQL 和运行参数；创建草稿后需发布版本，调度实例始终执行绑定的版本快照。'
                  : '请确认 SQL 和运行参数，创建并启动后将提交给 Flink SQL Gateway。'}
            />
            <Card title="任务配置" className="task-create-section">
              <Descriptions column={{ xs: 1, sm: 2 }} size="small">
                <Descriptions.Item label="任务名称">{formData.taskName}</Descriptions.Item>
                <Descriptions.Item label="任务场景"><Tag color="blue">{selectedScenario.title}</Tag></Descriptions.Item>
                <Descriptions.Item label="执行器">{taskTypeLabel[formData.taskType]}</Descriptions.Item>
                <Descriptions.Item label="运行方式">{formData.executionMode === 'scheduled' ? '周期实例' : '持续作业'}</Descriptions.Item>
                <Descriptions.Item label="业务源库">{selectedSource?.configName || (isCdc ? '—' : '由 SQL 声明')}</Descriptions.Item>
                <Descriptions.Item label="湖仓目标">平台 Paimon Catalog</Descriptions.Item>
                <Descriptions.Item label="并行度">{formData.parallelism}</Descriptions.Item>
                <Descriptions.Item label="Checkpoint">{formData.checkpointIntervalMs / 1000} 秒</Descriptions.Item>
                {isCdc && <Descriptions.Item label="同步策略">{formData.syncStrategy === 'incremental_only' ? '仅增量' : '首次全量 + 持续增量'}</Descriptions.Item>}
                {isCdc && <Descriptions.Item label="同步表数">{formData.tableMappings.length} 张</Descriptions.Item>}
              </Descriptions>
            </Card>

            {isCdc && (
              <Card title="表映射" className="task-create-section">
                <Table<TableMapping>
                  rowKey="sourceTable"
                  size="small"
                  pagination={false}
                  dataSource={formData.tableMappings}
                  columns={mappingColumns}
                  scroll={{ x: 520 }}
                />
              </Card>
            )}

            <Card
              title="Flink SQL"
              className="task-create-section"
              extra={isCdc && <Tag icon={<ApiOutlined />} color="processing">系统自动生成</Tag>}
            >
              {generatingSql ? <Spin /> : (
                <pre className="task-sql-preview">{formData.flinkSql || '暂无 SQL'}</pre>
              )}
            </Card>
          </div>

          <Card className="task-launch-card">
            <div className="task-launch-icon"><PlayCircleOutlined /></div>
            <Typography.Title level={4}>下一步怎么做？</Typography.Title>
            <Typography.Paragraph type="secondary">
              {formData.executionMode === 'scheduled'
                ? '周期任务先保存草稿，再到任务编排发布不可变版本并配置调度、依赖或补数。'
                : '推荐直接创建并启动。系统会提交到 Flink，并跳转到任务详情查看运行状态。'}
            </Typography.Paragraph>
            {access.canManageTask && formData.executionMode === 'continuous' ? (
              <Button
                type="primary"
                size="large"
                block
                icon={<PlayCircleOutlined />}
                loading={submitAction === 'start'}
                disabled={!!submitAction && submitAction !== 'start'}
                onClick={() => handleCreate(true)}
              >
                创建并启动
              </Button>
            ) : formData.executionMode === 'continuous' ? (
              <Alert type="info" showIcon message="当前账号只能创建草稿，启动需要任务管理权限" />
            ) : null}
            <Button
              block
              icon={<SaveOutlined />}
              loading={submitAction === 'draft'}
              disabled={!!submitAction && submitAction !== 'draft'}
              onClick={() => handleCreate(false)}
            >
              {formData.executionMode === 'scheduled' ? '创建周期任务草稿' : '仅保存草稿'}
            </Button>
            <Button block type="text" icon={<ArrowLeftOutlined />} onClick={() => setCurrentStep(0)}>
              返回修改配置
            </Button>
          </Card>
        </div>
      )}

      {currentStep === 0 && (
        <div className="task-create-footer">
          <span>完成必要配置后，下一步确认任务将采用持续运行还是周期实例</span>
          <Space>
            <Button onClick={() => history.push('/sync-task/list')}>取消</Button>
            <Button
              type="primary"
              icon={<ArrowRightOutlined />}
              iconPosition="end"
              loading={generatingSql}
              onClick={goToReview}
            >
              检查配置
            </Button>
          </Space>
        </div>
      )}

      <Modal
        title={editingIndex >= 0 ? '调整表映射' : '手动添加表映射'}
        open={mappingModalOpen}
        onCancel={() => setMappingModalOpen(false)}
        onOk={saveMapping}
        okText="保存映射"
        destroyOnHidden
        afterOpenChange={(open) => {
          if (open) {
            mappingForm.resetFields();
            mappingForm.setFieldsValue(mappingInitialValues as TableMapping);
          }
        }}
      >
        <Form form={mappingForm} layout="vertical">
          <Form.Item name="sourceTable" label="源表" rules={[{ required: true, message: '请选择源表' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择源表"
              onChange={handleMappingSourceChange}
              options={sourceTables.map((tableName) => ({ label: tableName, value: tableName }))}
            />
          </Form.Item>
          <Form.Item name="targetDb" label="目标分层" rules={[{ required: true, message: '请选择目标分层' }]}>
            <Select
              onChange={handleMappingLayerChange}
              options={[
                { label: 'ODS（原始数据层）', value: 'ods' },
                { label: 'DWD（明细数据层）', value: 'dwd' },
                { label: 'DWS（汇总数据层）', value: 'dws' },
                { label: 'ADS（应用数据层）', value: 'ads' },
              ]}
            />
          </Form.Item>
          <Form.Item name="targetTable" label="目标表名" rules={[{ required: true, message: '请填写目标表名' }]}>
            <Input placeholder="例如：ods_orders" />
          </Form.Item>
          <Form.Item name="syncMode" hidden><Input /></Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default SyncTaskCreate;
