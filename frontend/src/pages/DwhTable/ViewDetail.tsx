import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Button, Card, Descriptions, Input, Space, Table, Tabs, Tag, Typography, message } from 'antd';
import { useAccess, useRequest, useSearchParams } from '@umijs/max';
import { checkManagedView, getManagedView, previewManagedView, publishManagedView, saveManagedView } from '@/api';
import { AssetContextPanel } from './AssetPanels';

export default function ViewDetail({asset, returnTo}: {asset:API.DwhTableMeta;returnTo:string}) {
  const access = useAccess();
  const [params, setParams] = useSearchParams();
  const tabKey = ['definition','versions','usage'].includes(params.get('tab') || '') ? params.get('tab')! : 'definition';
  const {data, error, refresh} = useRequest(() => getManagedView(asset.assetId), {refreshDeps:[asset.assetId]});
  const [sql, setSql] = useState<string>();
  const [preview, setPreview] = useState<any>();
  const [health, setHealth] = useState<any>();
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<any>();
  const definition = data?.definition;
  useRequest(() => checkManagedView(asset.assetId), {ready:!!definition?.publishedVersionId, refreshDeps:[asset.assetId,definition?.version],onSuccess:setHealth});
  const run = async (action:()=>Promise<void>) => { setBusy(true); try {await action();} catch(e:any) {message.error(e?.message || '操作失败');} finally {setBusy(false);} };
  const blocked = definition && definition.operationState !== 'idle';
  const dirty = sql !== undefined && sql !== definition?.draftSql;
  return <PageContainer className="asset-detail-page" title={`${asset.paimonDb}.${asset.paimonTable}`} subTitle="Doris 普通 View · 定义复用，不存储查询结果"
    extra={<Space><Button href={returnTo}>返回资产目录</Button><Button onClick={refresh}>刷新</Button><Button disabled={!definition?.publishedVersionId || blocked}
      href={`/query/adhoc?assetId=${asset.assetId}&assetContext=${encodeURIComponent(params.toString())}`}>查询 View</Button></Space>}>
    {error && <Alert type="error" message="无法读取 View 定义，请确认依赖权限" />}
    {blocked && <Alert type="error" showIcon message="发布结果待核对，已暂停修改与查询" description={definition.lastError || '存在尚未完成的发布意图，请管理员核对 Doris 与发布记录。'} />}
    <Card style={{marginBottom:16}}><Descriptions size="small" column={2} items={[
      {key:'id',label:'资产 ID',children:asset.assetId}, {key:'engine',label:'Catalog',children:'internal'},
      {key:'published',label:'当前发布版本',children:data?.versions?.find((v:any)=>v.id===definition?.publishedVersionId)?.versionNo || '尚未发布'},
      {key:'state',label:'发布状态',children:({idle:'就绪',applying:'发布中，待核对',unknown:'发布结果待核对'} as Record<string,string>)[definition?.operationState] || '加载中'},
    ]}/><Space style={{marginTop:12}}><Button loading={busy} onClick={()=>run(async()=>setHealth(await checkManagedView(asset.assetId)))}>核验 View 有效性</Button>
      {health && <Typography.Text type={health.valid?'success':'danger'}>{health.message}</Typography.Text>}</Space></Card>
    <Tabs activeKey={tabKey} onChange={key=>{const next=new URLSearchParams(params);next.set('tab',key);setParams(next,{replace:true});}} items={[
      {key:'definition',label:'定义与发布',children:<Card>
        <Alert type="info" showIcon message="依赖必须使用 catalog.database.table 三段名称" description="当前支持 SELECT、关联、聚合及子查询。已发布 View 的依赖集合和输出列保持稳定；需要变更时，创建新 View 并迁移消费者。" style={{marginBottom:16}} />
        <Input.TextArea aria-label="View SQL 定义" rows={12} value={sql ?? definition?.draftSql ?? ''} readOnly={!access.canManageDwh || blocked}
          onChange={e=>{setSql(e.target.value);setPreview(undefined);}} style={{fontFamily:'monospace'}} />
        {access.canManageDwh && <Space style={{marginTop:16}} wrap>
          <Button disabled={!dirty || blocked} loading={busy} onClick={()=>run(async()=>{
            await saveManagedView(asset.assetId,{sql:sql!,description:asset.businessDesc,expectedVersion:definition.version});
            setSql(undefined);setPreview(undefined);refresh();message.success('草稿已保存');
          })}>保存草稿</Button>
          <Button disabled={!definition || dirty || blocked} loading={busy} onClick={()=>run(async()=>setPreview(await previewManagedView(asset.assetId)))}>校验并预览发布</Button>
          <Button type="primary" disabled={!preview?.publishable || dirty || blocked} loading={busy} onClick={()=>run(async()=>{
            const result=await publishManagedView(asset.assetId,definition.version);setPreview(undefined);setHealth(undefined);refresh();
            if(result.definition.operationState==='idle')message.success('View 已发布，Doris 定义已核验');else message.warning('发布结果待核对，请查看状态');
          })}>发布到 Doris</Button>
          {dirty && <Typography.Text type="secondary">先保存草稿，再校验发布</Typography.Text>}
        </Space>}
        {preview && <div style={{marginTop:16}}><Alert showIcon type={preview.publishable?'success':'warning'} message={preview.message}/>
          <Typography.Title level={5}>直接依赖</Typography.Title>
          <Space wrap>{preview.dependencies.map((d:any)=><Tag key={JSON.stringify(d.name)}>{d.name.catalog}.{d.name.database}.{d.name.table}</Tag>)}</Space>
          <Typography.Title level={5}>输出列契约</Typography.Title><Table size="small" rowKey="name" pagination={false} dataSource={preview.columns} columns={[
            {title:'列名',dataIndex:'name'}, {title:'类型',dataIndex:'type'}, {title:'可为空',dataIndex:'nullable',render:v=>v?'是':'否'},
          ]}/></div>}
      </Card>},
      {key:'versions',label:'发布历史',children:<Card><Table size="small" rowKey="id" dataSource={data?.versions || []} columns={[
        {title:'版本',dataIndex:'versionNo'}, {title:'状态',dataIndex:'status',render:v=><Tag>{({published:'已发布',applying:'发布中',unknown:'结果待核对'} as Record<string,string>)[v] || v}</Tag>},
        {title:'发布人 ID',dataIndex:'createdBy'}, {title:'定义',render:(_,v)=><Button type="link" onClick={()=>setSelected(v)}>查看版本</Button>},
      ]}/>{selected && <><Typography.Title level={5}>版本 {selected.versionNo}</Typography.Title><pre style={{whiteSpace:'pre-wrap'}}>{selected.sqlContent}</pre>
        <Typography.Text type="secondary">该版本的 SQL、依赖和输出列在创建后保持不变。</Typography.Text>
        <pre style={{whiteSpace:'pre-wrap'}}>{JSON.stringify(JSON.parse(selected.columnsJson),null,2)}</pre></>}</Card>},
      {key:'usage',label:'下游使用',children:<AssetContextPanel table={data?.asset || asset}/>},
    ]}/>
  </PageContainer>;
}
