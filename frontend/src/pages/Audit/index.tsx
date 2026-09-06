import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Button, Card, DatePicker, Descriptions, Drawer, Input, Select, Space, Table, Tag, Tooltip, Typography, message,
} from 'antd';
import { DownloadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import dayjs, { Dayjs } from 'dayjs';
import { getOperationAudits } from '@/api';

const { RangePicker } = DatePicker;

type AuditFilters = {
  username?: string;
  keyword?: string;
  resourceType?: string;
  success?: boolean;
  range?: [Dayjs, Dayjs];
};

const resourceOptions = [
  'sync-tasks', 'workflow', 'datasources', 'dwh', 'query', 'reports', 'quality',
  'alerts', 'settings', 'admin', 'data-services',
].map((value) => ({ value, label: value }));

const formatTime = (value?: string | number[]) => {
  if (!value) return '—';
  if (Array.isArray(value)) return `${value[0]}-${String(value[1]).padStart(2, '0')}-${String(value[2]).padStart(2, '0')} ${String(value[3] || 0).padStart(2, '0')}:${String(value[4] || 0).padStart(2, '0')}:${String(value[5] || 0).padStart(2, '0')}`;
  return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
};

const requestParams = (filters: AuditFilters, page: number, size: number) => ({
  username: filters.username || undefined,
  keyword: filters.keyword || undefined,
  resourceType: filters.resourceType || undefined,
  success: filters.success,
  from: filters.range?.[0].startOf('day').format('YYYY-MM-DDTHH:mm:ss'),
  to: filters.range?.[1].endOf('day').format('YYYY-MM-DDTHH:mm:ss'),
  page,
  size,
});

const csvCell = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`;

const Audit: React.FC = () => {
  const [draft, setDraft] = useState<AuditFilters>({});
  const [filters, setFilters] = useState<AuditFilters>({});
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<API.OperationAudit>();
  const [exporting, setExporting] = useState(false);
  const { data, loading, refresh } = useRequest(
    () => getOperationAudits(requestParams(filters, page, size)),
    { refreshDeps: [filters, page, size] },
  );
  const result = data as API.PageResult<API.OperationAudit> | undefined;

  const applyFilters = () => { setFilters({ ...draft }); setPage(0); };
  const resetFilters = () => { setDraft({}); setFilters({}); setPage(0); };
  const exportAudits = async () => {
    setExporting(true);
    try {
      const exportResult = await getOperationAudits(requestParams(filters, 0, 100));
      const header = ['时间', '用户', '方法', '资源类型', '资源ID', '请求路径', '结果', '状态码', '耗时ms', 'IP', '错误'];
      const rows = exportResult.content.map((row) => [formatTime(row.createdAt), row.username, row.httpMethod,
        row.resourceType, row.resourceId, row.requestPath, row.success ? '成功' : '失败', row.responseStatus,
        row.durationMs, row.clientIp, row.errorMessage]);
      const csv = `\uFEFF${[header, ...rows].map((row) => row.map(csvCell).join(',')).join('\n')}`;
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `operation-audit-${dayjs().format('YYYYMMDD-HHmmss')}.csv`;
      anchor.click();
      URL.revokeObjectURL(url);
      message.success(`已导出 ${rows.length} 条审计记录`);
    } finally {
      setExporting(false);
    }
  };

  return <PageContainer title="操作审计" subTitle="按用户、资源、结果和时间追溯平台写操作">
    <Card>
      <div className="rtdwh-page-toolbar">
        <Input value={draft.username} onChange={(event) => setDraft({ ...draft, username: event.target.value })}
          onPressEnter={applyFilters} allowClear prefix={<SearchOutlined />} placeholder="用户名" style={{ width: 180 }} />
        <Input value={draft.keyword} onChange={(event) => setDraft({ ...draft, keyword: event.target.value })}
          onPressEnter={applyFilters} allowClear placeholder="路径、操作、资源 ID 或错误" style={{ width: 260 }} />
        <Select value={draft.resourceType} onChange={(value) => setDraft({ ...draft, resourceType: value })}
          allowClear showSearch placeholder="全部资源" style={{ width: 170 }} options={resourceOptions} />
        <Select value={draft.success} onChange={(value) => setDraft({ ...draft, success: value })}
          allowClear placeholder="全部结果" style={{ width: 130 }} options={[
            { value: true, label: '成功' }, { value: false, label: '失败' },
          ]} />
        <RangePicker value={draft.range} onChange={(value) => setDraft({ ...draft, range: value as [Dayjs, Dayjs] | undefined })}
          allowClear style={{ width: 250 }} />
        <Button type="primary" icon={<SearchOutlined />} onClick={applyFilters}>查询</Button>
        <Button onClick={resetFilters}>重置</Button>
        <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
        <Tooltip title="按当前筛选导出最近 100 条记录">
          <Button icon={<DownloadOutlined />} loading={exporting} onClick={exportAudits}>导出 CSV</Button>
        </Tooltip>
      </div>
      <Table<API.OperationAudit> rowKey="id" loading={loading} dataSource={result?.content || []}
        scroll={{ x: 1250 }} locale={{ emptyText: '当前条件下没有审计记录' }}
        onRow={(record) => ({ onClick: () => setSelected(record), style: { cursor: 'pointer' } })}
        pagination={{ current: page + 1, pageSize: size, total: result?.totalElements || 0, showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`, onChange: (next, nextSize) => { setPage(next - 1); setSize(nextSize); } }}
        columns={[
          { title: '时间', dataIndex: 'createdAt', width: 175, render: formatTime },
          { title: '用户', dataIndex: 'username', width: 110 },
          { title: '操作', key: 'operation', width: 170, render: (_, row) => <Space><Tag color="blue">{row.httpMethod}</Tag>{row.resourceType}</Space> },
          { title: '资源', key: 'resource', width: 310, ellipsis: true, render: (_, row) => <Typography.Text code>{row.requestPath}</Typography.Text> },
          { title: '结果', dataIndex: 'success', width: 85, render: (success) => <Tag color={success ? 'success' : 'error'}>{success ? '成功' : '失败'}</Tag> },
          { title: '状态码', dataIndex: 'responseStatus', width: 85 },
          { title: '耗时', dataIndex: 'durationMs', width: 100, render: (value) => `${value || 0} ms` },
          { title: 'IP', dataIndex: 'clientIp', width: 130 },
          { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
          { title: '操作', width: 80, fixed: 'right', render: (_, row) => <Button type="link" size="small" onClick={(event) => { event.stopPropagation(); setSelected(row); }}>详情</Button> },
        ]} />
    </Card>

    <Drawer title="审计记录详情" width={560} open={!!selected} onClose={() => setSelected(undefined)}>
      {selected && <Descriptions column={1} bordered size="small" labelStyle={{ width: 110 }}>
        <Descriptions.Item label="发生时间">{formatTime(selected.createdAt)}</Descriptions.Item>
        <Descriptions.Item label="用户 / IP">{selected.username} / {selected.clientIp || '—'}</Descriptions.Item>
        <Descriptions.Item label="请求"><Space><Tag color="blue">{selected.httpMethod}</Tag><Typography.Text copyable>{selected.requestPath}</Typography.Text></Space></Descriptions.Item>
        <Descriptions.Item label="资源">{selected.resourceType}{selected.resourceId ? ` #${selected.resourceId}` : ''}</Descriptions.Item>
        <Descriptions.Item label="执行结果"><Tag color={selected.success ? 'success' : 'error'}>{selected.success ? '成功' : '失败'}</Tag> HTTP {selected.responseStatus}</Descriptions.Item>
        <Descriptions.Item label="耗时">{selected.durationMs || 0} ms</Descriptions.Item>
        <Descriptions.Item label="错误信息"><Typography.Paragraph copyable={!!selected.errorMessage} style={{ marginBottom: 0 }}>{selected.errorMessage || '—'}</Typography.Paragraph></Descriptions.Item>
      </Descriptions>}
    </Drawer>
  </PageContainer>;
};

export default Audit;
