import React, { useState } from 'react';
import { Alert, Button, Card, Empty, Modal, Space, Table, Tag, Typography } from 'antd';
import { history, useAccess, useRequest } from '@umijs/max';
import { getAssetContext, getAssetSchemaRevisions, getProductionChecks } from '@/api';

export const assetTypeLabel = (type?: string) => ({doris_view:'Doris 普通 View',paimon_primary_key_table:'Paimon 主键表',paimon_append_table:'Paimon 追加表',paimon_table:'Paimon 表（类型待核验）'}[type || 'paimon_table'] || type);
const date = (value?: string | number[]) => !value ? '—' : Array.isArray(value)
  ? `${value[0]}-${String(value[1]).padStart(2,'0')}-${String(value[2]).padStart(2,'0')}${value.length > 3 ? ` ${String(value[3]).padStart(2,'0')}:${String(value[4]).padStart(2,'0')}:${String(value[5] || 0).padStart(2,'0')}` : ''}` : value;
const severity = (value: string) => <Tag color={{breaking:'error',risk:'warning',compatible:'success',baseline:'default'}[value]}>{{breaking:'破坏性变更',risk:'风险变更',compatible:'兼容变更',baseline:'首次观测'}[value] || value}</Tag>;
const changeLabel: Record<string,string> = {baseline:'首次观测',add_column:'新增字段',drop_column:'删除字段',rename_column:'字段重命名',alter_type:'类型变更',alter_nullable:'可空性变更',alter_default:'默认值变更',alter_column_key:'主键属性变更',alter_keys:'主键变更',alter_partition:'分区键变更',metadata_changed:'元数据变更'};
const evidence: Record<string,string> = {published_view_contract:'View 发布依赖',published_output:'发布产出契约',published_mapping:'发布表映射',published_sql_ast:'发布 SQL 解析',current_report_sql_ast:'当前报表 SQL',current_service_sql_ast:'当前服务 SQL'};

export const AssetContextPanel: React.FC<{table:API.DwhTableMeta}> = ({table}) => {
  const access = useAccess();
  const {data,loading,error,refresh} = useRequest(() => getAssetContext(table.assetId), {refreshDeps:[table.assetId,JSON.stringify(table.schemaObservedAt)]});
  const [checks,setChecks] = useState<API.ProductionCheck[]>();
  return <Space direction="vertical" style={{width:'100%'}} size={16}>
    {error && <Alert type="error" message="资产关系加载失败" action={<Button onClick={refresh}>重试</Button>}/>}
    <Alert type="info" message={data?.coverage || '正在加载资产关系'} />
    {access.canViewQuality && <Button onClick={() => history.push(`/quality?tab=rules&targetTable=${encodeURIComponent(`${table.catalogName}.${table.paimonDb}.${table.paimonTable}`)}`)}>查看该资产质量规则</Button>}
    <Card title="生产任务与消费入口" style={{width:'100%'}}>
      <Table<API.AssetUsage> loading={loading} rowKey={row=>`${row.kind}:${row.id}:${row.relation}`} dataSource={data?.usages || []} pagination={{pageSize:10}} scroll={{x:750}} columns={[
        {title:'对象',dataIndex:'name',render:(value,row)=><Button type="link" href={row.href}>{value}</Button>},
        {title:'角色',dataIndex:'relation',render:value=>value==='producer'?'生产方':'消费方'},
        {title:'对象类型',dataIndex:'kind',render:value=>(({view:'普通 View',task:'任务',report:'报表',service:'数据服务'} as Record<string,string>)[value] || value)},
        {title:'版本',dataIndex:'versionNo',render:(value,row)=>value ? `V${value}${row.versionId ? ` · #${row.versionId}` : ''}`:'当前定义'},
        {title:'关系依据',dataIndex:'evidence',render:value=>evidence[value] || value},
      ]}/>
    </Card>
    <Card title="关联资产" style={{width:'100%'}}>
      {data?.relatedAssets.length ? <Space wrap>{data.relatedAssets.map(item=><Button key={item.direction+item.assetId} href={`/dwh/assets/${item.assetId}?tab=usage`}>{item.direction==='upstream'?'上游':'下游'} · {item.name}</Button>)}</Space> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前可验证范围内没有关联资产"/>}
    </Card>
    {table.assetType !== 'doris_view' && <Card title="产出与质量交付" style={{width:'100%'}}>
      <Table<API.DatasetProduction> rowKey="id" loading={loading} dataSource={data?.productions || []} pagination={{pageSize:10}} scroll={{x:950}} columns={[
        {title:'产出 ID',dataIndex:'id'}, {title:'业务日期',dataIndex:'businessDate',render:date},
        {title:'执行实例',dataIndex:'instanceId',render:(value,row)=><Button type="link" href={`/sync-task/workflow?taskId=${row.taskId}`}>#{value}</Button>},
        {title:'发布版本',dataIndex:'definitionVersionId',render:value=>value ? `#${value}`:'历史未记录'},
        {title:'交付',dataIndex:'status',render:value=><Tag color={value==='available'?'success':'error'}>{value}</Tag>},
        {title:'原因',dataIndex:'reason'}, {title:'产出时间',dataIndex:'producedAt',render:date},
        {title:'检测',render:(_,row)=><Button size="small" onClick={async()=>setChecks(await getProductionChecks(row.id))}>检测记录</Button>},
      ]}/>
    </Card>}
    <Modal rootClassName="asset-contract-modal" title="产出检测历史" open={!!checks} onCancel={()=>setChecks(undefined)} footer={null} width={850}>
      <Table rowKey="id" dataSource={checks} columns={[{title:'状态',dataIndex:'status'},{title:'原因',dataIndex:'reason'},{title:'批次',dataIndex:'qualityBatchId'},{title:'时间',dataIndex:'checkedAt',render:date}]}/>
    </Modal>
  </Space>;
};

export const AssetSchemaHistory: React.FC<{table:API.DwhTableMeta}> = ({table}) => {
  const {data,loading,error,refresh} = useRequest(() => getAssetSchemaRevisions(table.assetId), {refreshDeps:[table.assetId,JSON.stringify(table.schemaObservedAt)]});
  const [selected,setSelected] = useState<API.AssetSchemaRevision>();
  const changes = (value:string) => { try { const parsed=JSON.parse(value); return Array.isArray(parsed)?parsed:[]; } catch { return []; } };
  const pretty = (value?:string) => value ? JSON.stringify(JSON.parse(value),null,2) : '无历史观测基线';
  return <Card>
    <Alert style={{marginBottom:16}} type="info" message="变更分级来自 Schema 观测，不会自动修改物理表。风险和破坏性变更需结合生产与消费关系评估；未观测到的历史不作推断。"/>
    {error && <Alert type="error" message="Schema 历史加载失败" action={<Button onClick={refresh}>重试</Button>}/>}
    <Table<API.AssetSchemaRevision> rowKey="id" dataSource={data || []} loading={loading} pagination={{pageSize:10}} scroll={{x:650}} columns={[
      {title:'修订',dataIndex:'revisionNo',render:value=>`R${value}`}, {title:'分级',dataIndex:'severity',render:severity},
      {title:'变更内容',dataIndex:'changesJson',render:value=><Space direction="vertical" size={2}>{changes(value).map((item,index)=><span key={index}>{item.field ? `${item.field}：` : ''}{changeLabel[item.kind] || item.kind} · {item.detail}</span>)}</Space>},
      {title:'观测时间',dataIndex:'observedAt',render:date},
      {title:'详情',render:(_,row)=><Button size="small" onClick={()=>setSelected(row)}>查看契约</Button>},
    ]}/>
    <Modal rootClassName="asset-contract-modal" title={`Schema 修订 R${selected?.revisionNo || ''}`} open={!!selected} onCancel={()=>setSelected(undefined)} footer={null} width={950}>
      {selected && <><Typography.Paragraph>来源：{selected.evidenceSource} · {severity(selected.severity)}</Typography.Paragraph>
        <Typography.Paragraph copyable>指纹：{selected.fingerprint}</Typography.Paragraph>
        <Card title="变更前"><pre style={{overflow:'auto',maxHeight:260}}>{pretty(selected.beforeSchema)}</pre></Card>
        <Card title="变更后"><pre style={{overflow:'auto',maxHeight:260}}>{pretty(selected.afterSchema)}</pre></Card></>}
    </Modal>
  </Card>;
};
