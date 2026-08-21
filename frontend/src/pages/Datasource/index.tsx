import React, { useCallback, useEffect, useState } from 'react';
import { PageContainer, ProForm, ProFormSelect, ProFormText, ProFormDigit, ProFormTextArea } from '@ant-design/pro-components';
import { Card, Table, Tag, Button, Space, Modal, message, Popconfirm, Descriptions, Badge } from 'antd';
import { PlusOutlined, ReloadOutlined, LinkOutlined, DisconnectOutlined } from '@ant-design/icons';
import { getDatasources, createDatasource, testDatasourceConnection, deleteDatasource, updateDatasource } from '@/api';

const dbTypeColorMap: Record<string, string> = {
  mysql: 'blue',
  postgresql: 'green',
  paimon: 'orange',
};

const dbTypeLabelMap: Record<string, string> = {
  mysql: 'MySQL',
  postgresql: 'PostgreSQL',
  paimon: 'Paimon',
};

const formatBackendDateTime = (value: unknown) => {
  if (!value) return '—';
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nano = 0] = value.map(Number);
    const date = new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1_000_000));
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false });
  }
  const date = new Date(value as string | number | Date);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false });
};

const Datasource: React.FC = () => {
  const [createModalVisible, setCreateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [editingDs, setEditingDs] = useState<API.DatasourceConfig | null>(null);
  const [editForm, setEditForm] = useState<any>({});
  const [testResult, setTestResult] = useState<{ id: number; result: any } | null>(null);
  const [datasources, setDatasources] = useState<API.DatasourceConfig[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getDatasources();
      setDatasources(result);
    } catch (error: any) {
      setDatasources([]);
      message.error(error?.message || '数据源列表加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleCreate = async (values: any) => {
    try {
      const payload = {
        ...values,
        passwordEncrypted: values.password,
        extraParams: values.extraConfig,
      };
      delete payload.password;
      delete payload.extraConfig;
      await createDatasource(payload);
      message.success('数据源创建成功');
      setCreateModalVisible(false);
      await refresh();
    } catch (e: any) {
      message.error(e?.message || '创建异常');
    }
  };

  const handleTestConnection = async (id: number) => {
    try {
      message.loading({ content: '正在测试连接...', key: 'test-conn', duration: 0 });
      const result = await testDatasourceConnection(id);
      message.destroy('test-conn');
      if (result.success) {
        message.success(`连接成功! ${result.dbVersion || ''}`);
        setTestResult({ id, result });
      } else {
        message.error(`连接失败: ${result.message}`);
        setTestResult({ id, result });
      }
    } catch (e) {
      message.destroy('test-conn');
      message.error('测试连接异常');
    }
  };

  const handleEdit = (ds: API.DatasourceConfig) => {
    setEditingDs(ds);
    setEditForm({
      configName: ds.configName,
      dbType: ds.dbType,
      host: ds.host,
      port: ds.port,
      database: ds.database,
      username: ds.username,
      extraConfig: ds.extraParams,
    });
    setEditModalVisible(true);
  };

  const handleEditSubmit = async (values: any) => {
    if (!editingDs) return;
    try {
      const payload = {
        ...values,
        extraParams: values.extraConfig,
      };
      if (values.password?.trim()) {
        payload.passwordEncrypted = values.password;
      }
      delete payload.password;
      delete payload.extraConfig;
      await updateDatasource(editingDs.id, payload);
      message.success('数据源已更新');
      setEditModalVisible(false);
      setEditingDs(null);
      await refresh();
    } catch (e: any) {
      message.error(e?.message || '更新异常');
    }
  };

  // Paimon form fields differ from MySQL/PostgreSQL
  const isPaimonType = (type: string) => type === 'paimon';

  return (
    <PageContainer>
      <Card>
        <Space style={{ marginBottom: 16 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalVisible(true)}>
            新建数据源
          </Button>
          <Button icon={<ReloadOutlined spin={loading} />} onClick={() => void refresh()} loading={loading}>
            刷新
          </Button>
        </Space>

        <Table<API.DatasourceConfig>
          dataSource={datasources}
          rowKey="id"
          loading={loading}
          columns={[
            { title: 'ID', dataIndex: 'id', key: 'id', width: 60 },
            { title: '配置名称', dataIndex: 'configName', key: 'name' },
            {
              title: '类型',
              dataIndex: 'dbType',
              key: 'dbType',
              width: 120,
              render: (v) => <Tag color={dbTypeColorMap[v]}>{dbTypeLabelMap[v]}</Tag>,
            },
            {
              title: '连接地址',
              key: 'host',
              width: 200,
              render: (_, record) =>
                record.dbType === 'paimon'
                  ? record.host
                  : `${record.host}:${record.port}/${record.database}`,
            },
            { title: '用户名', dataIndex: 'username', key: 'user', width: 100 },
            {
              title: '额外参数',
              dataIndex: 'extraParams',
              key: 'extraParams',
              width: 120,
              ellipsis: true,
              render: (v) => v || '—',
            },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              key: 'created',
              width: 160,
              render: formatBackendDateTime,
            },
            {
              title: '操作',
              key: 'action',
              width: 200,
              render: (_, record) => (
                <Space>
                  <Button
                    size="small"
                    type="primary"
                    icon={<LinkOutlined />}
                    onClick={() => handleTestConnection(record.id)}
                  >
                    测试连接
                  </Button>
                  <Button size="small" type="link" onClick={() => handleEdit(record)}>编辑</Button>
                  <Popconfirm title="确认删除此数据源？" onConfirm={async () => {
                    try {
                      await deleteDatasource(record.id);
                      message.success('数据源已删除');
                      await refresh();
                    } catch (e) {
                      message.error('删除失败');
                    }
                  }}>
                    <Button size="small" type="link" danger>删除</Button>
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />

        {testResult && (
          <Card title="最近测试连接结果" style={{ marginTop: 16 }} size="small">
            <Descriptions size="small" column={3}>
              <Descriptions.Item label="数据源 ID">{testResult.id}</Descriptions.Item>
              <Descriptions.Item label="连接状态">
                <Badge
                  status={testResult.result.success ? 'success' : 'error'}
                  text={testResult.result.success ? '成功' : '失败'}
                />
              </Descriptions.Item>
              <Descriptions.Item label="数据库版本">{testResult.result.dbVersion || '—'}</Descriptions.Item>
              {testResult.result.message && (
                <Descriptions.Item label="详情" span={3}>{testResult.result.message}</Descriptions.Item>
              )}
            </Descriptions>
          </Card>
        )}
      </Card>

      <Modal
        title="新建数据源"
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
        width={600}
      >
        <ProForm
          onFinish={handleCreate}
          submitter={{
            searchConfig: { submitText: '创建' },
            resetButtonProps: { style: { display: 'none' } },
          }}
          onValuesChange={(values) => {
            // Force re-render when type changes
            if (values.dbType) {
              setCreateModalVisible(true);
            }
          }}
        >
          <ProFormText
            name="configName"
            label="配置名称"
            placeholder="例如: business_mysql, ods_paimon"
            rules={[{ required: true, message: '请输入配置名称' }]}
          />
          <ProFormSelect
            name="dbType"
            label="数据库类型"
            options={[
              { label: 'MySQL', value: 'mysql' },
              { label: 'PostgreSQL', value: 'postgresql' },
              { label: 'Paimon (湖仓)', value: 'paimon' },
            ]}
            rules={[{ required: true }]}
          />

          {/* Conditional fields based on dbType - shown via dependency */}
          <ProFormText
            name="host"
            label="主机地址"
            placeholder="MySQL/PG: 192.168.1.10 | Paimon: hdfs:///warehouse"
            rules={[{ required: true }]}
          />
          <ProFormDigit
            name="port"
            label="端口"
            placeholder="MySQL: 3306, PG: 5432, Paimon: 不需要"
            fieldProps={{ min: 1, max: 65535 }}
          />
          <ProFormText
            name="database"
            label="数据库"
            placeholder="MySQL/PG: business_db | Paimon: 不需要"
          />
          <ProFormText
            name="username"
            label="用户名"
            placeholder="root / pg_user | Paimon: 不需要"
          />
          <ProFormText.Password
            name="password"
            label="密码"
            placeholder="数据库密码"
          />
          <ProFormTextArea
            name="extraConfig"
            label="额外配置 (JSON)"
            placeholder='{"hive.metastore.uris": "thrift://hive:9083"}'
            fieldProps={{ autoSize: { minRows: 2, maxRows: 4 } }}
          />
        </ProForm>
      </Modal>

      {/* Edit Modal */}
      <Modal
        title="编辑数据源"
        open={editModalVisible}
        onCancel={() => { setEditModalVisible(false); setEditingDs(null); }}
        footer={null}
        width={600}
      >
        <ProForm
          initialValues={editForm}
          onFinish={handleEditSubmit}
          submitter={{
            searchConfig: { submitText: '保存' },
            resetButtonProps: { style: { display: 'none' } },
          }}
        >
          <ProFormText name="configName" label="配置名称" rules={[{ required: true, message: '请输入配置名称' }]} />
          <ProFormSelect name="dbType" label="数据库类型" disabled options={[
            { label: 'MySQL', value: 'mysql' },
            { label: 'PostgreSQL', value: 'postgresql' },
            { label: 'Paimon (湖仓)', value: 'paimon' },
          ]} />
          <ProFormText name="host" label="主机地址" rules={[{ required: true }]} />
          <ProFormDigit name="port" label="端口" fieldProps={{ min: 1, max: 65535 }} />
          <ProFormText name="database" label="数据库" />
          <ProFormText name="username" label="用户名" />
          <ProFormText.Password name="password" label="新密码 (留空则不修改)" />
          <ProFormTextArea name="extraConfig" label="额外配置 (JSON)" fieldProps={{ autoSize: { minRows: 2, maxRows: 4 } }} />
        </ProForm>
      </Modal>
    </PageContainer>
  );
};

export default Datasource;
