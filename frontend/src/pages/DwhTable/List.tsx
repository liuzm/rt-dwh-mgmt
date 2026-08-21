import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Button, Card, Col, Input, Row, Select, Space, Statistic, Table, Tag, Typography, message } from 'antd';
import { DatabaseOutlined, HddOutlined, ReloadOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import { getDwhTables, syncMetadataFromPaimon } from '@/api';

const layerColorMap: Record<string, string> = {
  ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
};

const formatSize = (bytes?: number) => {
  if (bytes === undefined || bytes === null) return '—';
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let index = 0;
  let size = bytes;
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1; }
  return `${size.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
};

const DwhTableList: React.FC = () => {
  const [layer, setLayer] = useState<string>();
  const [database, setDatabase] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [syncing, setSyncing] = useState(false);
  const { data, loading, refresh } = useRequest(getDwhTables);
  const tables = (data || []) as API.DwhTableMeta[];

  const databases = useMemo(() => Array.from(new Set(tables.map((table) => table.paimonDb)))
    .sort().map((value) => ({ label: value, value })), [tables]);
  const filteredTables = useMemo(() => {
    const normalizedKeyword = keyword.trim().toLowerCase();
    return tables.filter((table) => (!layer || table.layer === layer)
      && (!database || table.paimonDb === database)
      && (!normalizedKeyword
        || `${table.paimonDb}.${table.paimonTable} ${table.businessDesc || ''}`
          .toLowerCase().includes(normalizedKeyword)));
  }, [tables, layer, database, keyword]);

  const totalSize = tables.reduce((sum, table) => sum + (table.totalSizeBytes || 0), 0);
  const totalRecords = tables.reduce((sum, table) => sum + (table.recordCount || 0), 0);

  const handleSyncMetadata = async () => {
    setSyncing(true);
    try {
      const count = await syncMetadataFromPaimon();
      message.success(`元数据同步完成，共发现 ${count} 张 Paimon 表`);
      refresh();
    } catch (error: any) {
      message.error(error?.message || '元数据同步失败');
    } finally {
      setSyncing(false);
    }
  };

  return (
    <PageContainer title="数仓表管理" subTitle="统一查看 Paimon Catalog 中的表结构、快照和存储指标">
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}><Card><Statistic title="数仓表" value={tables.length} prefix={<TableOutlined />} suffix="张" /></Card></Col>
        <Col span={6}><Card><Statistic title="数据库" value={databases.length} prefix={<DatabaseOutlined />} suffix="个" /></Card></Col>
        <Col span={6}><Card><Statistic title="记录数" value={totalRecords} formatter={(value) => Number(value).toLocaleString()} /></Card></Col>
        <Col span={6}><Card><Statistic title="数据文件容量" value={formatSize(totalSize)} prefix={<HddOutlined />} /></Card></Col>
      </Row>

      <Card>
        <Space wrap style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }}>
          <Space wrap>
            <Input allowClear placeholder="搜索库名、表名或业务描述" prefix={<SearchOutlined />}
              value={keyword} onChange={(event) => setKeyword(event.target.value)} style={{ width: 280 }} />
            <Select placeholder="全部数据库" allowClear value={database} onChange={setDatabase}
              style={{ width: 180 }} options={databases} />
            <Select placeholder="全部分层" allowClear value={layer} onChange={setLayer}
              style={{ width: 140 }} options={[
                { label: 'ODS 原始层', value: 'ods' },
                { label: 'DWD 明细层', value: 'dwd' },
                { label: 'DWS 汇总层', value: 'dws' },
                { label: 'ADS 应用层', value: 'ads' },
              ]} />
          </Space>
          <Button type="primary" icon={<ReloadOutlined />} loading={syncing} onClick={handleSyncMetadata}>
            同步 Paimon 元数据
          </Button>
        </Space>

        <Table<API.DwhTableMeta>
          dataSource={filteredTables}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 张表` }}
          locale={{ emptyText: '暂无数仓表，请先同步 Paimon 元数据' }}
          columns={[
            { title: '数据库', dataIndex: 'paimonDb', width: 130,
              render: (value) => <Typography.Text code>{value}</Typography.Text> },
            { title: '表名', dataIndex: 'paimonTable', ellipsis: true,
              render: (value) => <Typography.Text strong>{value}</Typography.Text> },
            { title: '分层', dataIndex: 'layer', width: 90,
              render: (value) => <Tag color={layerColorMap[value]}>{String(value).toUpperCase()}</Tag> },
            { title: '业务描述', dataIndex: 'businessDesc', ellipsis: true, render: (value) => value || '—' },
            { title: '记录数', dataIndex: 'recordCount', width: 110, align: 'right',
              render: (value) => value === undefined || value === null ? '—' : Number(value).toLocaleString() },
            { title: '快照', dataIndex: 'snapshotCount', width: 80, align: 'right', render: (value) => value ?? '—' },
            { title: '文件', dataIndex: 'fileCount', width: 80, align: 'right', render: (value) => value ?? '—' },
            { title: '容量', dataIndex: 'totalSizeBytes', width: 110, align: 'right', render: formatSize },
            { title: '操作', fixed: 'right', width: 120, render: (_, record) => (
              <Space size={4}>
                <Button size="small" type="link" href={`/dwh/tables/${record.id}`}>详情</Button>
                <Button size="small" type="link" href={`/dwh/maintenance?tableId=${record.id}`}>维护</Button>
              </Space>
            ) },
          ]}
          scroll={{ x: 1100 }}
        />
      </Card>
    </PageContainer>
  );
};

export default DwhTableList;
