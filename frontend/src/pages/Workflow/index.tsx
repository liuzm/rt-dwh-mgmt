import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Button, Card, DatePicker, Form, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Switch, Table, Tabs, Tag,
} from 'antd';
import { ApartmentOutlined, BranchesOutlined, HistoryOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useAccess, useRequest, useSearchParams } from '@umijs/max';
import dayjs, { Dayjs } from 'dayjs';
import {
  addTaskDependency, createTaskBackfill, getTaskVersions, getWorkflowGraph, getWorkflowInstances,
  publishTaskVersion, removeTaskDependency, rollbackTaskVersion, retryWorkflowInstance,
  cancelWorkflowInstance,
  configureTaskOutputs, configureTaskSchedule, deleteTaskSchedule, getDatasetProductions,
  getWorkflowAttempts, getWorkflowBindings, recheckWorkflowDelivery, getProductionChecks,
  getTaskOutputs, getTaskSchedules, configureTaskParameters, getTaskScheduleRevisions, getTaskAccessChecks,
} from '@/api';

const statusColors: Record<string, string> = {
  waiting: 'default', queued: 'processing', running: 'blue', success: 'success', failed: 'error', cancelled: 'default',
};

const formatTime = (value?: string | number[]) => {
  if (!value) return '—';
  if (Array.isArray(value)) return `${value[0]}-${String(value[1]).padStart(2, '0')}-${String(value[2]).padStart(2, '0')} ${String(value[3] || 0).padStart(2, '0')}:${String(value[4] || 0).padStart(2, '0')}`;
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
};

const formatDate = (value?: string | number[]) => {
  if (!value) return '历史未记录';
  return Array.isArray(value) ? `${value[0]}-${String(value[1]).padStart(2, '0')}-${String(value[2]).padStart(2, '0')}` : value;
};

const Workflow: React.FC = () => {
  const access = useAccess();
  const [searchParams] = useSearchParams();
  const selectedTaskId = Number(searchParams.get('taskId')) || undefined;
  const [parameterTask, setParameterTask] = useState<API.SyncTask>();
  const [parameterSchema, setParameterSchema] = useState('[]');
  const [scheduleHistory, setScheduleHistory] = useState<API.TaskScheduleRevision[]>();
  const [accessChecks, setAccessChecks] = useState<API.TaskAccessCheck[]>();
  const [runDetail, setRunDetail] = useState<API.TaskRunInstance>();
  const [runAttempts, setRunAttempts] = useState<API.TaskRunAttempt[]>([]);
  const [runBindings, setRunBindings] = useState<API.TaskRunBinding[]>([]);
  const [productionChecks, setProductionChecks] = useState<API.ProductionCheck[]>();
  const [upstreamOutputs, setUpstreamOutputs] = useState<API.TaskOutputDataset[]>([]);
  const [dependencyOpen, setDependencyOpen] = useState(false);
  const [publishTask, setPublishTask] = useState<API.SyncTask>();
  const [backfillTask, setBackfillTask] = useState<API.SyncTask>();
  const [versionTask, setVersionTask] = useState<API.SyncTask>();
  const [versions, setVersions] = useState<API.TaskDefinitionVersion[]>([]);
  const [scheduleTask, setScheduleTask] = useState<API.SyncTask>();
  const [outputTask, setOutputTask] = useState<API.SyncTask>();
  const [outputs, setOutputs] = useState<API.TaskOutputDataset[]>([]);
  const [productionOutput, setProductionOutput] = useState<API.TaskOutputDataset>();
  const [productions, setProductions] = useState<API.DatasetProduction[]>([]);
  const [dependencyForm] = Form.useForm();
  const [publishForm] = Form.useForm();
  const [backfillForm] = Form.useForm();
  const [scheduleForm] = Form.useForm();
  const [outputForm] = Form.useForm();

  const dependencyCondition = Form.useWatch('conditionType', dependencyForm);
  const openRun = async (run: API.TaskRunInstance) => {
    setRunAttempts([]); setRunBindings([]);
    setRunDetail(run);
    const [attempts, bindings] = await Promise.all([getWorkflowAttempts(run.id), getWorkflowBindings(run.id)]);
    setRunAttempts(attempts); setRunBindings(bindings);
  };
  const graphRequest = useRequest(getWorkflowGraph);
  const instancesRequest = useRequest(() => getWorkflowInstances({ limit: 200, taskId: selectedTaskId }), { pollingInterval: 5000, refreshDeps: [selectedTaskId] });
  const schedulesRequest = useRequest(getTaskSchedules, { pollingInterval: 10000 });
  const graph = graphRequest.data as API.WorkflowGraph | undefined;
  const tasks = (graph?.tasks || []).filter((task) => task.executionMode === 'scheduled');
  const scheduledTaskIds = useMemo(() => new Set(tasks.map((task) => task.id)), [tasks]);
  const dependencies = (graph?.dependencies || []).filter((item) => scheduledTaskIds.has(item.upstreamTaskId) && scheduledTaskIds.has(item.downstreamTaskId));
  const taskMap = useMemo(() => new Map(tasks.map((task) => [task.id, task])), [tasks]);
  const scheduleMap = useMemo(() => new Map(((schedulesRequest.data || []) as API.TaskSchedule[]).map((item) => [item.taskId, item])), [schedulesRequest.data]);

  const refreshAll = () => { graphRequest.refresh(); instancesRequest.refresh(); };

  const openVersions = async (task: API.SyncTask) => {
    setVersionTask(task);
    setVersions(await getTaskVersions(task.id));
  };
  const openSchedule = (task: API.SyncTask) => {
    const schedule = scheduleMap.get(task.id); setScheduleTask(task); scheduleForm.resetFields();
    scheduleForm.setFieldsValue(schedule ? { ...schedule } : { cronExpression: '0 0 2 * * *', timezone: 'Asia/Shanghai', businessDateOffset: -1, parametersJson: '{}', enabled: true });
  };
  const openOutputs = async (task: API.SyncTask) => {
    setOutputTask(task); const values = await getTaskOutputs(task.id); setOutputs(values);
    outputForm.setFieldsValue({ outputs: values.map(({ id, taskId, ...item }) => item) });
  };

  const taskColumns = [
    { title: '任务', key: 'task', render: (_: unknown, task: API.SyncTask) => <Space direction="vertical" size={0}>
      <b>{task.taskName || task.name}</b><span style={{ color: '#8c8c8c' }}>ID {task.id} · 周期实例</span>
    </Space> },
    { title: '上游依赖', key: 'upstream', render: (_: unknown, task: API.SyncTask) => {
      const upstream = dependencies.filter((item) => item.downstreamTaskId === task.id);
      return upstream.length ? <Space wrap>{upstream.map((item) => <Tag key={item.id} closable={access.canManageTask}
        onClose={(event) => { event.preventDefault(); removeTaskDependency(item.upstreamTaskId, item.downstreamTaskId)
          .then(() => { message.success('依赖已删除'); graphRequest.refresh(); }); }}>
        {taskMap.get(item.upstreamTaskId)?.taskName || `任务 ${item.upstreamTaskId}`}
      </Tag>)}</Space> : <span style={{ color: '#bfbfbf' }}>无上游，可直接调度</span>;
    } },
    { title: '周期调度', key: 'schedule', width: 190, render: (_: unknown, task: API.SyncTask) => { const item=scheduleMap.get(task.id); return item ? <Space direction="vertical" size={0}><Tag color={item.enabled?'processing':'default'}>{item.enabled?'已启用':'已停用'} · {item.cronExpression}</Tag><span style={{color:'#8c8c8c'}}>修订 #{item.activeRevisionId || '历史'} · 下次 {formatTime(item.nextRunAt)}</span>{item.lastError && <span style={{color:'#cf1322'}}>{item.lastError}</span>}</Space> : <Tag>未配置</Tag>; } },
    { title: '发布状态', key: 'status', width: 150, render: (_: unknown, task: API.SyncTask) => task.publishedVersionId
      ? <Tag color={task.definitionStatus === 'published' ? 'blue' : 'gold'}>{task.definitionStatus === 'published' ? '已发布' : '草稿有变更'} · #{task.publishedVersionId}</Tag>
      : <Tag>未发布</Tag> },
    { title: '操作', key: 'action', width: 420, render: (_: unknown, task: API.SyncTask) => <Space wrap>
      <Button size="small" icon={<HistoryOutlined />} onClick={() => openVersions(task)}>版本</Button>
      {access.canManageTask && <><Button size="small" onClick={() => { setPublishTask(task); publishForm.setFieldsValue({ changeSummary: '发布任务配置' }); }}>发布版本</Button>
        <Button size="small" disabled={!task.publishedVersionId} onClick={() => { setBackfillTask(task); backfillForm.resetFields(); backfillForm.setFieldsValue({ dates: [dayjs(), dayjs()], bindingPolicy: 'batch_only' }); }}>补数</Button>
        <Button size="small" disabled={!task.publishedVersionId} onClick={() => openSchedule(task)}>调度</Button>
        <Button size="small" onClick={() => openOutputs(task)}>产出资源</Button>
        <Button size="small" onClick={() => { setParameterTask(task); setParameterSchema(task.parameterSchemaJson || '[]'); }}>参数契约</Button></>}
      <Button size="small" onClick={async () => setScheduleHistory(await getTaskScheduleRevisions(task.id))}>调度历史</Button>
      <Button size="small" onClick={async () => setAccessChecks(await getTaskAccessChecks(task.id))}>权限检查</Button>
    </Space> },
  ];

  const instanceColumns = [
    { title: '实例 ID', dataIndex: 'id', width: 90 },
    { title: '任务', dataIndex: 'taskId', render: (id: number) => taskMap.get(id)?.taskName || `任务 ${id}` },
    { title: '版本', dataIndex: 'definitionVersionId', width: 100, render: (id: number) => <Tag color="blue">#{id}</Tag> },
    { title: '业务日期', dataIndex: 'businessDate', width: 130, render: formatDate },
    { title: '触发方式', key: 'trigger', width: 145, render: (_: unknown, item: API.TaskRunInstance) => <Space direction="vertical" size={0}><span>{item.triggerType}</span>{item.scheduleRevisionId && <Tag>调度 #{item.scheduleRevisionId}</Tag>}</Space> },
    { title: '计算状态', dataIndex: 'status', width: 110, render: (status: string) => <Tag color={statusColors[status]}>{status}</Tag> },
    { title: '交付状态', key: 'delivery', width: 150, render: (_: unknown, item: API.TaskRunInstance) => <Space direction="vertical" size={0}><Tag color={item.deliveryStatus === 'available' ? 'success' : item.deliveryStatus === 'blocked' ? 'error' : 'default'}>{item.deliveryStatus || '历史未验证'}</Tag>{item.deliveryError && <span>{item.deliveryError}</span>}</Space> },
    { title: '执行信息', key: 'executor', width: 220, render: (_: unknown, record: API.TaskRunInstance) => <Space direction="vertical" size={0}>
      <span>{record.executorId || '等待领取'}</span>
      <span style={{ color: '#8c8c8c' }}>{record.externalJobId ? `Job ${record.externalJobId.slice(0, 12)}…` : `已重试 ${record.retryCount || 0} 次`}</span>
    </Space> },
    { title: '下次执行 / 租约', key: 'schedule', width: 180, render: (_: unknown, record: API.TaskRunInstance) => formatTime(record.nextRetryAt || record.leaseExpiresAt) },
    { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
    { title: '创建时间', dataIndex: 'createdAt', width: 180, render: formatTime },
    { title: '操作', key: 'actions', fixed: 'right' as const, width: 200, render: (_: unknown, record: API.TaskRunInstance) => <Space wrap>
      <Button size="small" onClick={() => openRun(record)}>执行与依赖</Button>
      {access.canManageTask && record.deliveryStatus === 'blocked' && <Button size="small" onClick={async () => { await recheckWorkflowDelivery(record.id); message.success('已安排重新检测，保留原检测记录'); instancesRequest.refresh(); }}>重新检测</Button>}
      {access.canManageTask && record.status === 'failed' && <Button size="small" type="primary" onClick={async () => {
        await retryWorkflowInstance(record.id); message.success('实例已重新入队'); instancesRequest.refresh();
      }}>重试</Button>}
      {access.canManageTask && ['waiting', 'queued', 'running'].includes(record.status) && <Popconfirm title="确认取消该实例？运行中的 Flink Job 也会被取消。" onConfirm={async () => {
        await cancelWorkflowInstance(record.id); message.success('实例已取消'); instancesRequest.refresh();
      }}><Button size="small" danger>取消</Button></Popconfirm>}
    </Space> },
  ];

  return <PageContainer title="调度与运行" subTitle="任务依赖、版本发布、运行实例与补数控制面">
    <Card>
      <Space style={{ marginBottom: 16 }}>
        {access.canManageTask && <Button type="primary" icon={<PlusOutlined />} onClick={() => setDependencyOpen(true)}>添加依赖</Button>}
        <Button icon={<ReloadOutlined />} onClick={refreshAll}>刷新</Button>
        <Tag icon={<ApartmentOutlined />} color="blue">{tasks.length} 个任务</Tag>
        <Tag icon={<BranchesOutlined />}>{dependencies.length} 条依赖</Tag>
      </Space>
      <Tabs items={[
        { key: 'dag', label: 'DAG 与版本', children: <Table rowKey="id" loading={graphRequest.loading} dataSource={tasks} columns={taskColumns} pagination={false} scroll={{ x: 1250 }} /> },
        { key: 'runs', label: '运行实例', children: <Table rowKey="id" loading={instancesRequest.loading} dataSource={(instancesRequest.data || []) as API.TaskRunInstance[]} columns={instanceColumns} pagination={{ pageSize: 20 }} scroll={{ x: 1450 }} /> },
      ]} />
    </Card>

    <Modal title={`运行参数契约：${parameterTask?.taskName || ''}`} open={!!parameterTask} onCancel={() => setParameterTask(undefined)} onOk={async () => {
      if (!parameterTask) return;
      await configureTaskParameters(parameterTask.id, parameterSchema);
      message.success('参数草稿已保存，重新发布后生效'); setParameterTask(undefined); graphRequest.refresh();
    }}>
      <Alert type="info" showIcon style={{ marginBottom: 16, background: '#e6f4ff', borderColor: '#91caff' }} message="参数只能作为完整值使用，不能拼接表名或 SQL 片段。bizdate 由业务日期提供，无需声明。" />
      <p>支持 string、integer、number、boolean、date、datetime。required 控制必填，defaultValue 设置默认值。</p>
      <Input.TextArea rows={10} value={parameterSchema} onChange={event => setParameterSchema(event.target.value)} placeholder={'[{"name":"region","type":"string","required":true},{"name":"limit","type":"integer","defaultValue":100}]'} />
      <p style={{ marginTop: 8 }}>SQL 示例：<code>{"WHERE region = ${region} AND dt = '${bizdate}' LIMIT ${limit}"}</code></p>
    </Modal>

    <Modal title="调度修订历史" open={!!scheduleHistory} onCancel={() => setScheduleHistory(undefined)} footer={null} width={900}>
      <Table rowKey="id" dataSource={scheduleHistory} pagination={{ pageSize: 10 }} columns={[
        { title: '修订', dataIndex: 'revisionNo', render: (value, record) => `R${value} · #${record.id}` },
        { title: '动作', dataIndex: 'action' }, { title: 'Cron', dataIndex: 'cronExpression' },
        { title: '时区', dataIndex: 'timezone' }, { title: '日期偏移', dataIndex: 'businessDateOffset' },
        { title: '参数', dataIndex: 'parametersJson', ellipsis: true },
        { title: '修改人', dataIndex: 'createdBy' }, { title: '时间', dataIndex: 'createdAt', render: formatTime },
      ]} />
    </Modal>

    <Modal title="执行权限检查" open={!!accessChecks} onCancel={() => setAccessChecks(undefined)} footer={null} width={900}>
      <Table rowKey="id" dataSource={accessChecks} pagination={{ pageSize: 10 }} columns={[
        { title: '版本', dataIndex: 'definitionVersionId' }, { title: '实例', dataIndex: 'instanceId' },
        { title: '执行人', dataIndex: 'actorId' }, { title: '动作', dataIndex: 'action' },
        { title: '结果', dataIndex: 'allowed', render: value => <Tag color={value ? 'success' : 'error'}>{value ? '通过' : '拒绝'}</Tag> },
        { title: '原因', dataIndex: 'reason' }, { title: '检查时间', dataIndex: 'checkedAt', render: formatTime },
      ]} />
    </Modal>

    <Modal title="添加任务依赖" open={dependencyOpen} onCancel={() => setDependencyOpen(false)} onOk={() => dependencyForm.submit()} destroyOnClose>
      <Form form={dependencyForm} layout="vertical" initialValues={{ conditionType: 'data_available' }} onFinish={async (values) => {
        await addTaskDependency(values); message.success('依赖创建成功'); setDependencyOpen(false); dependencyForm.resetFields(); graphRequest.refresh();
      }}>
        <Form.Item name="upstreamTaskId" label="上游任务" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={tasks.filter(task => task.publishedVersionId).map((task) => ({ value: task.id, label: task.taskName || task.name }))} onChange={async id => {
          dependencyForm.setFieldValue('outputDatasetId', undefined); setUpstreamOutputs([]);
          const version = (await getTaskVersions(id)).find(item => item.id === taskMap.get(id)?.publishedVersionId);
          const published = version?.contractJson ? JSON.parse(version.contractJson).outputs.map((item: {definition: API.TaskOutputDataset}) => item.definition) : await getTaskOutputs(id);
          if (dependencyForm.getFieldValue('upstreamTaskId') === id) setUpstreamOutputs(published);
        }} /></Form.Item>
        <Form.Item name="conditionType" label="放行条件"><Select options={[{value:'data_available',label:'指定产出可用'},{value:'execution_success',label:'仅要求计算成功（控制依赖）'}]} /></Form.Item>
        {dependencyCondition !== 'execution_success' && <Form.Item name="outputDatasetId" label="上游产出" rules={[{required:true}]} extra="必须已经包含在上游发布版本中"><Select options={upstreamOutputs.map(output => ({value:output.id,label:`${output.databaseName}.${output.tableName}`}))} /></Form.Item>}
        <Form.Item name="downstreamTaskId" label="下游任务" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={tasks.map((task) => ({ value: task.id, label: task.taskName || task.name }))} /></Form.Item>
      </Form>
    </Modal>

    <Modal title={`发布版本：${publishTask?.taskName || ''}`} open={!!publishTask} onCancel={() => setPublishTask(undefined)} onOk={() => publishForm.submit()} destroyOnClose>
      <Alert type="info" showIcon message="本次发布会固定任务定义、依赖、产出和质量规则。后续编辑需重新发布，已创建实例继续使用原版本。" style={{ marginBottom: 16 }} />
      <Form form={publishForm} layout="vertical" onFinish={async ({ changeSummary }) => {
        if (!publishTask) return; await publishTaskVersion(publishTask.id, changeSummary); message.success('版本发布成功'); setPublishTask(undefined); graphRequest.refresh();
      }}><Form.Item name="changeSummary" label="变更说明" rules={[{ required: true }]}><Input.TextArea rows={3} maxLength={256} showCount /></Form.Item></Form>
    </Modal>

    <Modal title={`创建补数：${backfillTask?.taskName || ''}`} open={!!backfillTask} onCancel={() => setBackfillTask(undefined)} onOk={() => backfillForm.submit()} destroyOnClose>
      <Form form={backfillForm} layout="vertical" onFinish={async ({ dates, parametersJson, bindingPolicy, upstreamParameters }: { dates: [Dayjs, Dayjs]; parametersJson?: string; bindingPolicy: string; upstreamParameters?: string }) => {
        if (!backfillTask) return; await createTaskBackfill(backfillTask.id, { startDate: dates[0].format('YYYY-MM-DD'), endDate: dates[1].format('YYYY-MM-DD'), parametersJson, bindingPolicy, taskParametersJson: upstreamParameters ? JSON.parse(upstreamParameters) : undefined });
        message.success('补数实例已创建'); setBackfillTask(undefined); instancesRequest.refresh();
      }}>
        <Alert type="info" style={{marginBottom:12,background:'#e6f4ff'}} message="按选中日期范围逐日创建，单个窗口为 [业务日期, 次日)。本批重算会创建上游实例；复用模式要求同窗口、同上游发布版本的可用产出。" />
        <Form.Item name="bindingPolicy" label="依赖策略" rules={[{required:true}]}><Select options={[{value:'batch_only',label:'本批重算上游（默认）'},{value:'reuse_available',label:'复用已有可用产出'}]} /></Form.Item>
        <Form.Item name="upstreamParameters" label="上游任务参数（可选）" rules={[{validator: async (_, value) => {
          if (!value?.trim()) return;
          try { const parsed = JSON.parse(value); if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || Object.values(parsed).some(item => typeof item !== 'string')) throw new Error(); }
          catch { throw new Error('请填写以任务 ID 为键、参数 JSON 字符串为值的对象'); }
        }}]} extra='按任务 ID 提供 JSON 字符串，例如 {"12":"{\"region\":\"east\"}"}；缺省读取各发布版本默认值'><Input.TextArea rows={2}/></Form.Item>
        <Form.Item name="dates" label="业务日期范围" rules={[{ required: true }]}><DatePicker.RangePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="parametersJson" label="运行参数（JSON）"><Input.TextArea rows={4} placeholder='例如：{"region":"east","limit":100}；按发布版本的参数契约校验' /></Form.Item>
      </Form>
    </Modal>

    <Modal title={`版本历史：${versionTask?.taskName || ''}`} width={760} open={!!versionTask} footer={null} onCancel={() => setVersionTask(undefined)}>
      <Table rowKey="id" dataSource={versions} pagination={false} columns={[
        { title: '版本', dataIndex: 'versionNo', render: (value) => <Tag color="blue">V{value}</Tag> },
        { title: '发布契约', dataIndex: 'contractProvenance', render: (value) => <Tag color={value === 'frozen-v1' ? 'green' : 'gold'}>{value === 'frozen-v1' ? '已冻结' : '历史契约未验证'}</Tag> },
        { title: '变更说明', dataIndex: 'changeSummary' },
        { title: '发布时间', dataIndex: 'createdAt', render: formatTime },
        { title: '操作', render: (_: unknown, version: API.TaskDefinitionVersion) => <Popconfirm title="确认回滚到此版本？仅 draft 任务可回滚" onConfirm={async () => {
          if (!versionTask) return; await rollbackTaskVersion(versionTask.id, version.versionNo); message.success('配置已回滚'); graphRequest.refresh();
        }}><Button size="small">回滚</Button></Popconfirm> },
      ]} />
    </Modal>

    <Modal title={`周期调度：${scheduleTask?.taskName || ''}`} open={!!scheduleTask} onCancel={() => setScheduleTask(undefined)} onOk={() => scheduleForm.submit()} destroyOnClose>
      <Form form={scheduleForm} layout="vertical" onFinish={async (values) => { if(!scheduleTask)return; await configureTaskSchedule(scheduleTask.id, values); message.success('周期调度已保存'); setScheduleTask(undefined); schedulesRequest.refresh(); }}>
        <Form.Item name="enabled" label="启用调度" valuePropName="checked"><Switch /></Form.Item>
        <Form.Item name="cronExpression" label="Cron 表达式" extra="六段 Cron：秒 分 时 日 月 周" rules={[{required:true}]}><Input placeholder="0 0 2 * * *" /></Form.Item>
        <Space style={{width:'100%'}} align="start">
          <Form.Item name="timezone" label="时区" rules={[{required:true}]}><Select style={{width:200}} options={[{value:'Asia/Shanghai'},{value:'UTC'},{value:'Asia/Hong_Kong'}]} /></Form.Item>
          <Form.Item name="businessDateOffset" label="业务日期偏移"><InputNumber min={-366} max={366} addonAfter="天" /></Form.Item>
        </Space>
        <Form.Item name="parametersJson" label="运行参数（JSON）"><Input.TextArea rows={4} /></Form.Item>
        {scheduleTask && scheduleMap.has(scheduleTask.id) && <Popconfirm title="确认删除周期调度？" onConfirm={async()=>{await deleteTaskSchedule(scheduleTask.id);message.success('调度已删除');setScheduleTask(undefined);schedulesRequest.refresh();}}><Button danger>删除调度</Button></Popconfirm>}
      </Form>
    </Modal>

    <Modal title={`产出数据资源：${outputTask?.taskName || ''}`} width={1000} open={!!outputTask} onCancel={() => setOutputTask(undefined)} onOk={() => outputForm.submit()} destroyOnClose>
      <Form form={outputForm} layout="vertical" onFinish={async ({outputs: values=[]})=>{if(!outputTask)return;await configureTaskOutputs(outputTask.id,values);message.success('产出草稿已保存，重新发布后生效');setOutputTask(undefined);graphRequest.refresh();}}>
        <Form.List name="outputs">{(fields,{add,remove})=><Space direction="vertical" style={{width:'100%'}}>
          {fields.map((field,index)=><Card key={field.key} size="small" title={`数据资源 ${index+1}`} extra={<Button danger type="link" onClick={()=>remove(field.name)}>移除</Button>}>
            <Space wrap align="start"><Form.Item name={[field.name,'catalogName']} label="Catalog" rules={[{required:true}]}><Input style={{width:160}} /></Form.Item><Form.Item name={[field.name,'databaseName']} label="Database" rules={[{required:true}]}><Input style={{width:140}} /></Form.Item><Form.Item name={[field.name,'tableName']} label="Table" rules={[{required:true}]}><Input style={{width:220}} /></Form.Item><Form.Item name={[field.name,'layer']} label="分层" rules={[{required:true}]}><Select style={{width:100}} options={['ods','dwd','dws','ads'].map(value=>({value}))} /></Form.Item><Form.Item name={[field.name,'slaMinutes']} label="SLA"><InputNumber min={1} addonAfter="分钟" /></Form.Item></Space>
            <Space wrap align="start"><Form.Item name={[field.name,'owner']} label="负责人"><Input /></Form.Item><Form.Item name={[field.name,'businessDesc']} label="业务说明"><Input style={{width:360}} /></Form.Item><Form.Item name={[field.name,'qualityGateEnabled']} label="质量门禁" extra="命中该表的启用规则失败时阻止资源变为可用" valuePropName="checked"><Switch /></Form.Item></Space>
            {outputs[index] && <Space>{outputs[index].lastProducedAt && <Tag color="success">最近可用产出 {formatTime(outputs[index].lastProducedAt)}</Tag>}<Button size="small" onClick={async()=>{setProductionOutput(outputs[index]);setProductions(await getDatasetProductions(outputs[index].id));}}>查看产出记录</Button></Space>}
          </Card>)}
          <Button type="dashed" icon={<PlusOutlined/>} onClick={()=>add({catalogName:'rtdwh_paimon',databaseName:'ads',layer:'ads',slaMinutes:1440,qualityGateEnabled:false})}>添加产出数据资源</Button>
        </Space>}</Form.List>
      </Form>
    </Modal>

    <Modal title={`执行与依赖：实例 ${runDetail?.id || ''}`} open={!!runDetail} onCancel={() => setRunDetail(undefined)} footer={null} width={1050}>
      <p>业务窗口 [{formatDate(runDetail?.windowStart || runDetail?.businessDate)}, {formatDate(runDetail?.windowEnd)}) · 批次 {runDetail?.batchId}</p>
      <Alert type="info" style={{marginBottom:16,background:'#e6f4ff'}} message="已提交执行或提交结果未知时，不自动重放。当前只支持对确定尚未提交的失败执行重试。" />
      <Tabs items={[
        {key:'attempts',label:'执行尝试',children:<Table rowKey="id" dataSource={runAttempts} pagination={false} columns={[
          {title:'尝试',dataIndex:'attemptNo'}, {title:'状态',dataIndex:'status'}, {title:'执行器',dataIndex:'executorId'},
          {title:'Job ID',dataIndex:'externalJobId',ellipsis:true}, {title:'提交时间',dataIndex:'submittedAt',render:formatTime}, {title:'错误',dataIndex:'errorMessage'},
        ]}/>},
        {key:'bindings',label:'依赖绑定',children:<Table rowKey="id" dataSource={runBindings} pagination={false} columns={[
          {title:'上游任务',dataIndex:'upstreamTaskId'}, {title:'版本',dataIndex:'upstreamVersionId'}, {title:'条件',dataIndex:'conditionType'},
          {title:'策略',dataIndex:'bindingPolicy'}, {title:'上游实例',dataIndex:'upstreamInstanceId'}, {title:'产出 ID',dataIndex:'productionId'}, {title:'绑定时间',dataIndex:'boundAt',render:formatTime},
        ]}/>},
      ]}/>
    </Modal>
    <Modal title="产出检测历史" open={!!productionChecks} onCancel={()=>setProductionChecks(undefined)} footer={null} width={760}>
      <Table rowKey="id" dataSource={productionChecks} columns={[{title:'状态',dataIndex:'status'},{title:'原因',dataIndex:'reason'},{title:'检测批次',dataIndex:'qualityBatchId',ellipsis:true},{title:'时间',dataIndex:'checkedAt',render:formatTime}]} />
    </Modal>

    <Modal title={`产出记录：${productionOutput?.databaseName}.${productionOutput?.tableName}`} width={900} open={!!productionOutput} onCancel={()=>setProductionOutput(undefined)} footer={null}>
      <Table rowKey="id" dataSource={productions} pagination={false} columns={[{title:'业务日期',dataIndex:'businessDate',render:formatDate},{title:'实例',dataIndex:'instanceId'},{title:'状态',dataIndex:'status',render:(v)=><Tag color={v==='available'?'success':'error'}>{v}</Tag>},{title:'原因',dataIndex:'reason'},{title:'产出时间',dataIndex:'producedAt',render:formatTime},{title:'检测',render:(_:unknown,p:API.DatasetProduction)=><Button size="small" onClick={async()=>setProductionChecks(await getProductionChecks(p.id))}>历史</Button>}]} />
    </Modal>
  </PageContainer>;
};

export default Workflow;
