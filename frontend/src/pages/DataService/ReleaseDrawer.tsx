import React, { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Empty, Input, message, Modal, Space, Spin, Table, Tabs, Tag, Typography } from 'antd';
import { getDataService, getDataServiceVersions, previewDataService, publishDataService, rollbackDataService } from '@/api';
import { formatBackendDateTime } from '@/utils/backendDateTime';

const formatJson = (value?: string) => {
  if (!value) return '未记录';
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
};
const code = (value?: string) => <pre className="rtdwh-code-panel" style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 260, overflow: 'auto' }}>{value || '无'}</pre>;

const ReleaseDrawer: React.FC<{ serviceId?: number; canManage: boolean; onClose: () => void; onChanged: () => void }> = ({ serviceId, canManage, onClose, onChanged }) => {
  const [definition, setDefinition] = useState<API.DataServiceDefinition>();
  const [versions, setVersions] = useState<API.DataServiceVersion[]>([]);
  const [preview, setPreview] = useState<API.DataServicePublicationPreview>();
  const [selected, setSelected] = useState<API.DataServiceVersion>();
  const [summary, setSummary] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const load = async (id: number) => {
    const [d, history] = await Promise.all([getDataService(id), getDataServiceVersions(id)]);
    setDefinition(d); setVersions(history); setPreview(undefined);
  };
  useEffect(() => {
    let active = true;
    setDefinition(undefined); setVersions([]); setPreview(undefined); setSelected(undefined); setSummary(''); setError(undefined);
    if (serviceId !== undefined) {
      setLoading(true);
      Promise.all([getDataService(serviceId), getDataServiceVersions(serviceId)]).then(([d, history]) => {
        if (active) { setDefinition(d); setVersions(history); }
      }).catch(() => { if (active) setError('无法读取发布信息，请检查权限和连接后重试'); })
        .finally(() => { if (active) setLoading(false); });
    }
    return () => { active = false; };
  }, [serviceId]);
  const current = versions.find(v => v.id === definition?.publishedVersionId);
  const manageable = canManage && definition?.manageable;
  const act = async (action: () => Promise<unknown>) => {
    setBusy(true); setError(undefined);
    try { await action(); } catch (e: any) { setError(e?.data?.message || e?.message || '操作失败，请刷新后重试'); }
    finally { setBusy(false); }
  };

  return <Drawer rootClassName="rtdwh-evidence-surface" title={`发布管理${definition ? ` · ${definition.serviceName}` : ''}`} width={Math.min(960, window.innerWidth)} open={serviceId !== undefined} onClose={busy ? undefined : onClose} destroyOnClose>
    {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} action={serviceId !== undefined && <Button size="small" onClick={() => act(() => load(serviceId))}>重新加载</Button>} />}
    <Spin spinning={loading || busy}>
      {definition && <>
        <Space wrap style={{ marginBottom: 12 }}>
          <Tag color={definition.status === 'published' ? 'success' : 'default'}>{definition.status === 'published' ? `线上 v${definition.apiVersion}` : definition.status === 'offline' ? '已下线' : '尚未发布'}</Tag>
          {definition.hasDraftChanges && <Tag color="orange">有未发布修改</Tag>}
          <Typography.Text type="secondary">保存草稿后，校验并发布才会更新外部调用。</Typography.Text>
        </Space>
        <Tabs items={[
          { key: 'publish', label: '草稿与发布', children: <>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 16 }}>
              <div><Typography.Text strong>当前发布 SQL {current && `· v${current.versionNo}`}</Typography.Text>{code(current?.sqlTemplate)}<Typography.Text type="secondary">当前发布参数</Typography.Text>{code(formatJson(current?.parameterConfig))}</div>
              <div><Typography.Text strong>已保存草稿</Typography.Text>{code(definition.sqlTemplate)}<Typography.Text type="secondary">草稿参数</Typography.Text>{code(formatJson(definition.parameterConfig))}</div>
            </div>
            <Descriptions size="small" column={1} style={{ marginBottom: 12 }} items={[
              { key: 'online', label: '当前发布配置', children: current ? `${current.catalogName}.${current.databaseName} · ${current.maxRows} 行 · ${current.timeoutSeconds}s · ${current.rateLimitPerMinute} 次每分` : '尚未发布' },
              { key: 'draft', label: '草稿配置', children: `${definition.catalogName}.${definition.databaseName} · ${definition.maxRows} 行 · ${definition.timeoutSeconds}s · ${definition.rateLimitPerMinute} 次每分` },
            ]} />
            {current?.origin === 'legacy_capture' && <Alert showIcon type="warning" message="升级时捕获的旧定义" description="SQL 与参数已固定，旧版本未记录结果列契约；再次发布会校验当前旧定义与新草稿的结构。" style={{ marginBottom: 12 }} />}
            {manageable && <Space direction="vertical" style={{ width: '100%', marginBottom: 16 }}>
              <Input value={summary} onChange={e => setSummary(e.target.value)} maxLength={512} placeholder="本次发布说明（可选）" />
              <Space><Button onClick={() => act(async () => setPreview(await previewDataService(definition.id, definition.revision)))}>校验发布</Button>
                <Button type="primary" disabled={!preview?.publishable || preview.revision !== definition.revision} onClick={() => act(async () => {
                  await publishDataService(definition.id, true, definition.revision, summary);
                  message.success('新版本已发布'); await load(definition.id); onChanged();
                })}>发布新版本</Button>
              </Space>
            </Space>}
            {preview && <>
              <Alert showIcon type={preview.publishable ? 'success' : 'error'} message={preview.publishable ? '发布校验通过' : '存在不兼容变更'} description={preview.breakingChanges.length ? preview.breakingChanges.join('；') : `变更内容：${preview.changes.join('、') || '定义未变化'}`} style={{ marginBottom: 12 }} />
              <Typography.Paragraph type="secondary">发布时再次校验权限和结构；结果列不兼容时请使用新服务编码迁移。预览只查询结构，不读取业务结果。</Typography.Paragraph>
              <Table size="small" rowKey="name" pagination={false} dataSource={preview.resultColumns} columns={[
                { title: '输出列', dataIndex: 'name' }, { title: '类型', key: 'type', render: (_, r) => `${r.type} (${r.precision}, ${r.scale})` },
                { title: '允许空值', dataIndex: 'nullable', render: v => v ? '是' : '否' },
              ]} />
              <Typography.Paragraph style={{ marginTop: 12 }}>直接依赖：{preview.dependencies.map(d => `${d.catalog}.${d.database}.${d.table}`).join('、') || '无物理表依赖'}</Typography.Paragraph>
            </>}
          </> },
          { key: 'history', label: `版本历史（${versions.length}）`, children: <Table rowKey="id" size="small" dataSource={versions} locale={{ emptyText: <Empty description="暂无发布版本" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} columns={[
            { title: '版本', key: 'version', width: 110, render: (_, r) => <Space>v{r.versionNo}{r.id === definition.publishedVersionId && <Tag color="blue">当前</Tag>}</Space> },
            { title: '来源', dataIndex: 'origin', render: v => ({ publish: '发布草稿', rollback: '回退后发布', legacy_capture: '升级捕获' }[v as string] || v) },
            { title: '时间', dataIndex: 'createdAt', width: 180, render: formatBackendDateTime }, { title: '说明', dataIndex: 'changeSummary', ellipsis: true },
            { title: '操作', width: 100, render: (_, r) => <Button size="small" onClick={() => setSelected(r)}>查看版本</Button> },
          ]} /> },
        ]} />
      </>}
    </Spin>
    <Modal rootClassName="rtdwh-evidence-surface" title={selected ? `发布版本 v${selected.versionNo}` : '发布版本'} width={760} open={!!selected} onCancel={busy ? undefined : () => setSelected(undefined)} footer={<Space><Button disabled={busy} onClick={() => setSelected(undefined)}>关闭</Button>{manageable && selected && definition && (selected.id !== definition.publishedVersionId || definition.status !== 'published') && <Button type="primary" loading={busy} onClick={() => act(async () => {
      await rollbackDataService(definition.id, selected.id, definition.revision, `回退至 v${selected.versionNo}`);
      message.success('所选定义已校验并发布为新版本，草稿保持不变'); setSelected(undefined); await load(definition.id); onChanged();
    })}>校验并回退发布</Button>}</Space>}>
      {selected && <>
        {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
        <Alert showIcon type="info" message="回退会重新核验当前权限和结构，生成新的发布记录，并保留现有草稿。" style={{ marginBottom: 12 }} />
        <Descriptions size="small" column={2} items={[
          { key: 'catalog', label: '查询环境', children: `${selected.catalogName}.${selected.databaseName}` },
          { key: 'limits', label: '运行限制', children: `${selected.maxRows} 行 / ${selected.timeoutSeconds}s / ${selected.rateLimitPerMinute} 次每分` },
          { key: 'actor', label: '发布人 ID', children: selected.publishedBy ?? '升级捕获，未推断历史发布人' },
          { key: 'source', label: '来源版本 ID', children: selected.sourceVersionId ?? '—' },
        ]} />
        <Typography.Text strong>SQL</Typography.Text>{code(selected.sqlTemplate)}
        <Typography.Text strong>参数契约</Typography.Text>{code(formatJson(selected.parameterConfig))}
        <Typography.Text strong>结果列契约</Typography.Text>{code(formatJson(selected.resultColumnsJson))}
      </>}
    </Modal>
  </Drawer>;
};
export default ReleaseDrawer;
