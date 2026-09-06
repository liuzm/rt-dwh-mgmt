import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Col, DatePicker, Empty, Form, Input, InputNumber, message, Modal, Popconfirm, Row, Select, Space, Statistic, Table, Tabs, Tag, Typography } from 'antd';
import { ApiOutlined, CheckCircleOutlined, ClockCircleOutlined, CodeOutlined, KeyOutlined, PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import {
  createDataService, createDataServiceApp, deleteDataService, getDataServiceApps, getDataServiceGrants,
  getDataServiceLogs, getDataServices, grantDataService, publishDataService, revokeDataService,
  rotateDataServiceSecret, toggleDataServiceApp, updateDataService,
  getDataServicePublished,
} from '@/api';
import ReleaseDrawer from './ReleaseDrawer';
import { formatBackendDateTime } from '@/utils/backendDateTime';
import './index.less';

const parameterTemplate = JSON.stringify({ parameters: [
  { name: 'start_date', label: '开始日期', type: 'date', required: true },
  { name: 'region', label: '区域', type: 'string', defaultValue: '华东' },
] }, null, 2);

const serviceStatusLabels: Record<string, string> = { draft: '草稿', published: '已发布', offline: '已下线' };
const invocationStatusLabels: Record<string, string> = { success: '成功', failed: '失败' };

const DataService: React.FC = () => {
  const access = useAccess();
  const servicesRequest = useRequest(getDataServices);
  const appsRequest = useRequest(getDataServiceApps);
  const logsRequest = useRequest(() => getDataServiceLogs(200));
  const services = (servicesRequest.data || []) as API.DataServiceDefinition[];
  const apps = (appsRequest.data || []) as API.DataServiceApp[];
  const [editing, setEditing] = useState<API.DataServiceDefinition>();
  const [serviceOpen, setServiceOpen] = useState(false);
  const [credential, setCredential] = useState<API.DataServiceCredential>();
  const [grantApp, setGrantApp] = useState<API.DataServiceApp>();
  const [grants, setGrants] = useState<API.DataServiceGrant[]>([]);
  const [form] = Form.useForm();
  const [appForm] = Form.useForm();
  const [appOpen, setAppOpen] = useState(false);
  const [serviceKeyword, setServiceKeyword] = useState('');
  const [serviceStatus, setServiceStatus] = useState<string>();
  const [logStatus, setLogStatus] = useState<string>();
  const [exampleService, setExampleService] = useState<API.DataServiceDefinition>();
  const [releaseServiceId, setReleaseServiceId] = useState<number>();

  const showExample = async (value: API.DataServiceDefinition) => {
    if (value.publishedVersionId) {
      const published = await getDataServicePublished(value.id);
      setExampleService({ ...value, ...published, id: value.id, apiVersion: published.versionNo });
    } else setExampleService(value);
  };

  const editService = (value?: API.DataServiceDefinition) => {
    setEditing(value); setServiceOpen(true); form.resetFields();
    form.setFieldsValue(value || { catalogName: 'rtdwh_paimon', databaseName: 'ads', maxRows: 1000, timeoutSeconds: 30, rateLimitPerMinute: 60 });
  };
  const openGrants = async (app: API.DataServiceApp) => { setGrantApp(app); setGrants(await getDataServiceGrants(app.id)); };
  const refresh = () => { servicesRequest.refresh(); appsRequest.refresh(); logsRequest.refresh(); };
  const visibleServices = useMemo(() => services.filter((service) =>
    (!serviceStatus || service.status === serviceStatus)
    && (!serviceKeyword.trim() || `${service.serviceName} ${service.serviceCode} ${service.description || ''}`
      .toLowerCase().includes(serviceKeyword.trim().toLowerCase()))), [services, serviceKeyword, serviceStatus]);
  const logs = ((logsRequest.data || []) as API.DataServiceInvocationLog[])
    .filter((log) => !logStatus || log.status === logStatus);
  const successCount = ((logsRequest.data || []) as API.DataServiceInvocationLog[]).filter((log) => log.status === 'success').length;
  const averageDuration = Math.round(((logsRequest.data || []) as API.DataServiceInvocationLog[])
    .reduce((sum, log) => sum + (log.durationMs || 0), 0) / Math.max((logsRequest.data || []).length, 1));
  const exampleBody = useMemo(() => {
    if (!exampleService?.parameterConfig) return {};
    try {
      const definitions = JSON.parse(exampleService.parameterConfig)?.parameters || [];
      return Object.fromEntries(definitions.map((parameter: any) => {
        if (parameter.defaultValue !== undefined) return [parameter.name, parameter.defaultValue];
        if (parameter.type === 'number') return [parameter.name, 0];
        if (parameter.type === 'boolean') return [parameter.name, true];
        if (parameter.type === 'stringList') return [parameter.name, ['示例值']];
        if (parameter.type === 'date') return [parameter.name, '2026-08-23'];
        return [parameter.name, '示例值'];
      }));
    } catch { return {}; }
  }, [exampleService]);
  const curlExample = exampleService ? `curl -X POST '${window.location.origin}/api/v1/open/data/${exampleService.serviceCode}' \\\n+  -H 'Content-Type: application/json' \\\n+  -H 'X-App-Key: <YOUR_APP_KEY>' \\\n+  -H 'X-App-Secret: <YOUR_APP_SECRET>' \\\n+  -d '${JSON.stringify(exampleBody)}'` : '';
  const curlCommand = curlExample.replace(/\n\+/g, '\n');

  return <PageContainer className="data-service-page" title="数据 API" subTitle="将 Doris 查询安全发布为外部系统可调用的数据接口" extra={<Button icon={<ReloadOutlined/>} onClick={refresh}>刷新</Button>}>
    <Alert
      className="data-service-guide"
      type="info"
      showIcon
      message={<div className="data-service-guide-title"><span>外部调用地址</span><Typography.Text code copyable={{text:'POST /api/v1/open/data/{serviceCode}'}}>POST /api/v1/open/data/{'{serviceCode}'}</Typography.Text></div>}
      description={<span className="data-service-guide-description">请求头使用 <code>X-App-Key</code> 和 <code>X-App-Secret</code>，请求体传入 JSON 参数。密钥仅在创建或轮换时展示一次，请妥善保存。</span>}
    />
    <Row className="data-service-metrics" gutter={[12,12]}>
      <Col xs={12} md={6}><Card className="data-service-metric-card"><span className="data-service-metric-icon is-blue"><ApiOutlined/></span><Statistic title="已发布接口" value={services.filter(service=>service.status==='published').length}/></Card></Col>
      <Col xs={12} md={6}><Card className="data-service-metric-card"><span className="data-service-metric-icon is-cyan"><KeyOutlined/></span><Statistic title="调用应用" value={apps.filter(app=>app.enabled).length}/></Card></Col>
      <Col xs={12} md={6}><Card className="data-service-metric-card"><span className="data-service-metric-icon is-green"><CheckCircleOutlined/></span><Statistic title="近期成功率" value={(logsRequest.data||[]).length ? Number((successCount * 100 / (logsRequest.data||[]).length).toFixed(1)) : 0} suffix="%"/></Card></Col>
      <Col xs={12} md={6}><Card className="data-service-metric-card"><span className="data-service-metric-icon is-purple"><ClockCircleOutlined/></span><Statistic title="平均耗时" value={averageDuration} suffix="ms"/></Card></Col>
    </Row>
    <Card className="data-service-content-card"><Tabs className="data-service-tabs" items={[
      {key:'services',label:`服务定义（${services.length}）`,children:<>
        <div className="rtdwh-page-toolbar data-service-toolbar">
          {access.canManageDataService && <Button type="primary" icon={<PlusOutlined/>} onClick={()=>editService()}>新建数据服务</Button>}
          <div className="data-service-filter-group">
            <Input className="data-service-search" allowClear value={serviceKeyword} onChange={event=>setServiceKeyword(event.target.value)} prefix={<SearchOutlined/>} placeholder="搜索服务名称、编码或说明"/>
            <Select className="data-service-status" allowClear value={serviceStatus} onChange={setServiceStatus} placeholder="全部状态" options={[{value:'draft',label:'草稿'},{value:'published',label:'已发布'},{value:'offline',label:'已下线'}]}/>
          </div>
        </div>
        <Table className="data-service-table" size="small" rowKey="id" dataSource={visibleServices} loading={servicesRequest.loading} scroll={{x:1200}} locale={{emptyText:<Empty className="data-service-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description={serviceKeyword.trim() || serviceStatus ? '未找到匹配的数据服务' : '暂无数据服务，创建后即可安全发布接口'}>{access.canManageDataService && !serviceKeyword.trim() && !serviceStatus && <Button type="link" icon={<PlusOutlined/>} onClick={()=>editService()}>新建数据服务</Button>}</Empty>}} columns={[
          {title:'服务',key:'name',width:220,render:(_,r)=><div><b>{r.serviceName}</b><div><Typography.Text code>{r.serviceCode}</Typography.Text></div></div>},
          {title:'草稿查询环境',key:'source',width:220,render:(_,r)=>`${r.catalogName}.${r.databaseName}`},
          {title:'发布版本',key:'version',width:150,render:(_,r)=><Space direction="vertical" size={0}><span>{r.publishedVersionId?`v${r.apiVersion}`:'未发布'}</span>{r.publishedVersionId&&r.hasDraftChanges&&<Tag color="orange">有未发布修改</Tag>}</Space>},
          {title:'草稿运行限制',key:'limit',width:210,render:(_,r)=>`${r.maxRows} 行 · ${r.timeoutSeconds}s · ${r.rateLimitPerMinute}次/分`},
          {title:'状态',dataIndex:'status',width:100,render:v=><Tag color={v==='published'?'success':v==='offline'?'warning':'default'}>{serviceStatusLabels[v] || v}</Tag>},
          {title:'接口',dataIndex:'serviceCode',ellipsis:true,render:v=><Typography.Text copyable code>{`/api/v1/open/data/${v}`}</Typography.Text>},
          {title:'操作',width:420,fixed:'right' as const,render:(_,r)=><Space wrap><Button size="small" icon={<CodeOutlined/>} onClick={()=>showExample(r)}>调用示例</Button><Button size="small" type="primary" ghost onClick={()=>setReleaseServiceId(r.id)}>版本与发布</Button>{access.canManageDataService&&r.manageable&&<><Button size="small" onClick={()=>editService(r)}>编辑草稿</Button>{r.status==='published'&&<Popconfirm title="下线后外部调用将被拒绝，确认下线？" onConfirm={async()=>{await publishDataService(r.id,false,r.revision);message.success('已下线');servicesRequest.refresh();}}><Button size="small">下线</Button></Popconfirm>}<Popconfirm title="确认删除已下线的服务？版本及调用证据会保留。" onConfirm={async()=>{await deleteDataService(r.id);message.success('已删除');servicesRequest.refresh();}}><Button size="small" danger disabled={r.status==='published'}>删除</Button></Popconfirm></>}</Space>},
        ]}/>
      </>},
      {key:'apps',label:`调用应用（${apps.length}）`,children:<>
        {access.canManageDataService&&<div className="rtdwh-page-toolbar data-service-toolbar"><Button type="primary" icon={<KeyOutlined/>} onClick={()=>{setAppOpen(true);appForm.resetFields();}}>创建调用应用</Button></div>}
        <Table className="data-service-table" size="small" rowKey="id" dataSource={apps} loading={appsRequest.loading} columns={[
          {title:'应用名称',dataIndex:'appName'},
          {title:'AppKey',dataIndex:'appKey',render:v=><Typography.Text copyable code>{v}</Typography.Text>},
          {title:'状态',dataIndex:'enabled',width:100,render:v=><Tag color={v?'success':'default'}>{v?'启用':'停用'}</Tag>},
          {title:'有效期',dataIndex:'expiresAt',render:v=>v||'长期有效'},
          {title:'操作',width:300,render:(_,r)=>access.canManageDataService?<Space><Button size="small" onClick={()=>openGrants(r)}>服务授权</Button><Popconfirm title="轮换后旧密钥立即失效，确认继续？" onConfirm={async()=>setCredential(await rotateDataServiceSecret(r.id))}><Button size="small">轮换密钥</Button></Popconfirm><Button size="small" danger={r.enabled} onClick={async()=>{await toggleDataServiceApp(r.id);appsRequest.refresh();}}>{r.enabled?'停用':'启用'}</Button></Space>:<Typography.Text type="secondary">仅查看</Typography.Text>},
        ]}/>
      </>},
      {key:'logs',label:'调用日志',children:<><div className="rtdwh-page-toolbar data-service-toolbar"><Select className="data-service-status" allowClear value={logStatus} onChange={setLogStatus} placeholder="全部调用结果" options={[{value:'success',label:'成功'},{value:'failed',label:'失败'}]}/><Typography.Text className="data-service-toolbar-note" type="secondary">展示最近 200 次调用</Typography.Text></div><Table className="data-service-table" size="small" rowKey="id" dataSource={logs} loading={logsRequest.loading} scroll={{x:1100}} locale={{emptyText:'暂无接口调用日志'}} columns={[
        {title:'时间',dataIndex:'createdAt',width:180,render:formatBackendDateTime},{title:'服务',dataIndex:'serviceCode',width:180},{title:'实际版本',dataIndex:'apiVersion',width:100,render:v=>v==null?'未记录':`v${v}`},{title:'执行用户',dataIndex:'executionUserId',width:100,render:v=>v??'—'},{title:'应用ID',dataIndex:'appId',width:90},{title:'状态',dataIndex:'status',width:90,render:v=><Tag color={v==='success'?'success':'error'}>{invocationStatusLabels[v] || v}</Tag>},{title:'HTTP',dataIndex:'httpStatus',width:80},{title:'行数',dataIndex:'rowCount',width:80},{title:'耗时',dataIndex:'durationMs',width:100,render:v=>v==null?'—':`${v}ms`},{title:'来源IP',dataIndex:'clientIp',width:140},{title:'错误',dataIndex:'errorMessage',ellipsis:true},
      ]}/></>} ]}/></Card>

    <Modal title={editing?'编辑 API 草稿':'新建数据 API'} width={820} open={serviceOpen} onCancel={()=>setServiceOpen(false)} onOk={()=>form.submit()} okText="保存草稿" destroyOnClose>
      <Alert type="info" showIcon message="保存仅更新草稿，需在“版本与发布”中校验发布后才能更新线上接口。" style={{marginBottom:16}} />
      <Form form={form} layout="vertical" onFinish={async values=>{if(editing)await updateDataService(editing.id,{...values,expectedRevision:editing.revision});else await createDataService(values);message.success(editing?'草稿已保存，线上版本保持不变':'草稿已创建');setServiceOpen(false);servicesRequest.refresh();}}>
        <Row gutter={16}><Col xs={24} md={10}><Form.Item name="serviceCode" label="服务编码" rules={[{required:true},{pattern:/^[a-z][a-z0-9_-]{2,63}$/}]}><Input disabled={!!editing} placeholder="order-summary"/></Form.Item></Col><Col xs={24} md={14}><Form.Item name="serviceName" label="服务名称" rules={[{required:true}]}><Input/></Form.Item></Col></Row>
        <Form.Item name="description" label="说明"><Input/></Form.Item>
        <Row gutter={16}><Col xs={24} md={8}><Form.Item name="catalogName" label="Catalog" rules={[{required:true}]}><Input/></Form.Item></Col><Col xs={24} md={8}><Form.Item name="databaseName" label="Database" rules={[{required:true}]}><Input/></Form.Item></Col><Col xs={12} md={8}><Form.Item name="maxRows" label="最大行数"><InputNumber style={{width:'100%'}} min={1} max={50000}/></Form.Item></Col><Col xs={12} md={8}><Form.Item name="timeoutSeconds" label="超时秒数"><InputNumber style={{width:'100%'}} min={1} max={1800}/></Form.Item></Col><Col xs={24} md={8}><Form.Item name="rateLimitPerMinute" label="每分钟限流"><InputNumber style={{width:'100%'}} min={1}/></Form.Item></Col></Row>
        <Form.Item name="sqlTemplate" label="只读 SQL 模板" extra={'值参数使用 {{name}}，不支持动态表名'} rules={[{required:true}]}><Input.TextArea rows={7} spellCheck={false}/></Form.Item>
        <Form.Item label="参数定义（JSON）" extra={<Button size="small" onClick={()=>form.setFieldValue('parameterConfig',parameterTemplate)}>一键代入模板</Button>} name="parameterConfig"><Input.TextArea rows={6} spellCheck={false} placeholder={parameterTemplate}/></Form.Item>
      </Form>
    </Modal>

    <Modal title="创建调用应用" open={appOpen} onCancel={()=>setAppOpen(false)} onOk={()=>appForm.submit()} destroyOnClose><Form form={appForm} layout="vertical" onFinish={async values=>{setCredential(await createDataServiceApp({...values,expiresAt:values.expiresAt?.format('YYYY-MM-DDTHH:mm:ss')}));setAppOpen(false);appsRequest.refresh();}}><Form.Item name="appName" label="应用名称" rules={[{required:true}]}><Input placeholder="订单系统"/></Form.Item><Form.Item name="expiresAt" label="凭证有效期" extra="不填写表示长期有效"><DatePicker showTime style={{width:'100%'}} placeholder="选择失效时间" /></Form.Item></Form></Modal>
    <Modal title="应用凭证（仅展示一次）" open={!!credential} onCancel={()=>setCredential(undefined)} footer={<Button type="primary" onClick={()=>setCredential(undefined)}>我已保存</Button>}><Alert className="data-service-warning-alert" type="warning" showIcon message="关闭后无法再次查看 AppSecret"/><Typography.Paragraph copyable><b>AppKey：</b>{credential?.appKey}</Typography.Paragraph><Typography.Paragraph copyable><b>AppSecret：</b>{credential?.appSecret}</Typography.Paragraph></Modal>
    <Modal title={`服务授权：${grantApp?.appName||''}`} open={!!grantApp} onCancel={()=>setGrantApp(undefined)} footer={null}>
      <Select style={{width:'100%',marginBottom:16}} placeholder="选择要授权的数据服务" options={services.filter(s=>!grants.some(g=>g.serviceId===s.id)).map(s=>({value:s.id,label:`${s.serviceName}（${s.serviceCode}）`}))} onChange={async serviceId=>{if(!grantApp)return;await grantDataService(grantApp.id,serviceId);setGrants(await getDataServiceGrants(grantApp.id));message.success('授权已添加');}}/>
      <Table rowKey="id" dataSource={grants} pagination={false} columns={[{title:'已授权服务',dataIndex:'serviceId',render:id=>services.find(s=>s.id===id)?.serviceName||id},{title:'操作',width:100,render:(_,g)=><Button danger type="link" onClick={async()=>{if(!grantApp)return;await revokeDataService(grantApp.id,g.serviceId);setGrants(await getDataServiceGrants(grantApp.id));}}>移除</Button>}]} />
    </Modal>
    <Modal title={`接口调用示例：${exampleService?.serviceName||''}`} width={760} open={!!exampleService} onCancel={()=>setExampleService(undefined)} footer={<Button type="primary" onClick={()=>setExampleService(undefined)}>关闭</Button>}>
      {exampleService?.status!=='published'&&<Alert className="data-service-warning-alert" type="warning" showIcon message="该服务尚未发布，发布后外部系统才能调用"/>}
      <Typography.Paragraph type="secondary">将占位符替换为调用应用的凭证。{exampleService?.publishedVersionId?`请求体依据已发布 v${exampleService.apiVersion} 的参数生成。`:'当前示例使用未发布草稿的参数。'}</Typography.Paragraph>
      <Typography.Text strong>cURL</Typography.Text>
      <pre className="rtdwh-code-panel"><Typography.Text copyable={{text:curlCommand}} style={{color:'inherit'}}>{curlCommand}</Typography.Text></pre>
      <Typography.Text strong>请求体</Typography.Text>
      <pre className="rtdwh-code-panel"><Typography.Text copyable={{text:JSON.stringify(exampleBody,null,2)}} style={{color:'inherit'}}>{JSON.stringify(exampleBody,null,2)}</Typography.Text></pre>
    </Modal>
    <ReleaseDrawer serviceId={releaseServiceId} canManage={access.canManageDataService} onClose={()=>setReleaseServiceId(undefined)} onChanged={refresh}/>
  </PageContainer>;
};

export default DataService;
