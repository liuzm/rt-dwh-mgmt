import React, { useCallback, useEffect, useState } from 'react';
import {
  PageContainer,
  ProForm,
  ProFormDependency,
  ProFormDigit,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import { Alert, Badge, Button, Card, Descriptions, Form, Modal, Popconfirm, Space, Table, Tag, message } from 'antd';
import { DatabaseOutlined, LinkOutlined, PlusOutlined, ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { getDatasources, createDatasource, testDatasourceConnection, deleteDatasource, updateDatasource } from '@/api';
import { useAccess } from '@umijs/max';
import './index.less';

type DatasourceType = 'mysql' | 'postgresql';

interface DatasourceTemplate {
  key: string;
  name: string;
  description: string;
  recommended?: boolean;
  values: {
    configName: string;
    dbType: DatasourceType;
    host: string;
    port: number;
    database: string;
    username: string;
    password?: string;
    extraConfig: string;
  };
}

const datasourceTemplates: Record<DatasourceType, DatasourceTemplate[]> = {
  mysql: [
    {
      key: 'mysql-compose',
      name: 'Docker Compose 内置 MySQL',
      description: '适合整套平台通过 deploy/docker-compose.yml 启动时联调。',
      recommended: true,
      values: {
        configName: 'compose_mysql',
        dbType: 'mysql',
        host: 'mysql',
        port: 3306,
        database: 'rtdwh_mgmt',
        username: 'rtdwh_admin',
        extraConfig: JSON.stringify({ useSSL: false, serverTimezone: 'Asia/Shanghai' }, null, 2),
      },
    },
    {
      key: 'mysql-local',
      name: '本地 MySQL 业务库',
      description: '适合后端通过源码在宿主机运行，连接本机业务数据库。',
      values: {
        configName: 'local_mysql',
        dbType: 'mysql',
        host: '127.0.0.1',
        port: 3306,
        database: 'business_db',
        username: 'root',
        extraConfig: JSON.stringify({ useSSL: false, serverTimezone: 'Asia/Shanghai' }, null, 2),
      },
    },
  ],
  postgresql: [
    {
      key: 'postgres-compose',
      name: 'Docker Compose 内置 PostgreSQL',
      description: '对应 Compose 中可选的 PostgreSQL 业务源库示例。',
      recommended: true,
      values: {
        configName: 'compose_postgresql',
        dbType: 'postgresql',
        host: 'postgresql',
        port: 5432,
        database: 'inventory',
        username: 'rtdwh_admin',
        extraConfig: JSON.stringify({ schema: 'public', 'decoding.plugin.name': 'pgoutput' }, null, 2),
      },
    },
    {
      key: 'postgres-local',
      name: '本地 PostgreSQL 业务库',
      description: '适合后端通过源码在宿主机运行，连接本机 PostgreSQL。',
      values: {
        configName: 'local_postgresql',
        dbType: 'postgresql',
        host: '127.0.0.1',
        port: 5432,
        database: 'business_db',
        username: 'postgres',
        extraConfig: JSON.stringify({ schema: 'public', 'decoding.plugin.name': 'pgoutput' }, null, 2),
      },
    },
  ],
};

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
  const access = useAccess();
  const [createForm] = Form.useForm();
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

  const openCreateModal = () => {
    createForm.resetFields();
    setCreateModalVisible(true);
  };

  const applyTemplate = (template: DatasourceTemplate) => {
    const existingName = createForm.getFieldValue('configName');
    createForm.setFieldsValue({
      ...template.values,
      configName: existingName?.trim() || template.values.configName,
      password: undefined,
    });
    message.success(`已代入“${template.name}”模板，请补充密码并按实际环境调整`);
  };

  return (
    <PageContainer>
      <Card>
        <Alert
          type="info"
          showIcon
          message="这里仅管理业务源库"
          description="Paimon Catalog、Metastore 和 Warehouse 是平台运行配置，请在系统设置中统一维护。"
          style={{ marginBottom: 16 }}
        />
        <Space style={{ marginBottom: 16 }}>
          {access.canManageDatasource && <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建数据源
          </Button>}
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
              render: (_, record) => access.canManageDatasource ? (
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
              ) : <span style={{ color: '#8c8c8c' }}>仅查看</span>,
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
        title={(
          <div className="datasource-create-title">
            <span className="datasource-create-title-icon"><DatabaseOutlined /></span>
            <span>
              <strong>新建数据源</strong>
              <small>配置数据库连接与认证信息</small>
            </span>
          </div>
        )}
        open={createModalVisible}
        onCancel={() => setCreateModalVisible(false)}
        footer={null}
        width={580}
        forceRender
        rootClassName="datasource-create-modal"
      >
        <ProForm
          form={createForm}
          onFinish={handleCreate}
          submitter={{
            searchConfig: { submitText: '创建' },
            resetButtonProps: false,
            render: (_, dom) => <div className="datasource-create-actions">{dom}</div>,
          }}
        >
          <ProFormText
            name="configName"
            label="配置名称"
            placeholder="例如：business_mysql"
            rules={[{ required: true, message: '请输入配置名称' }]}
          />
          <ProFormSelect
            name="dbType"
            label="数据库类型"
            options={[
              { label: 'MySQL', value: 'mysql' },
              { label: 'PostgreSQL', value: 'postgresql' },
            ]}
            rules={[{ required: true }]}
          />

          <ProFormDependency name={['dbType']}>
            {({ dbType }: { dbType?: DatasourceType }) => dbType ? (
              <div className="datasource-template-panel">
                <div className="datasource-template-heading">
                  <span><ThunderboltOutlined /> 快速模板</span>
                  <small>一键填充非敏感配置，已输入的配置名称会保留</small>
                </div>
                <div className="datasource-template-list">
                  {datasourceTemplates[dbType].map((template) => (
                    <button key={template.key} type="button" className="datasource-template-item" onClick={() => applyTemplate(template)}>
                      <span>
                        <strong>{template.name}</strong>
                        {template.recommended && <Tag color="blue">推荐</Tag>}
                      </span>
                      <small>{template.description}</small>
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              <div className="datasource-template-empty">选择数据库类型后，可使用对应的一键配置模板</div>
            )}
          </ProFormDependency>

          <div className="datasource-form-grid datasource-form-grid-host">
            <ProFormText
              name="host"
              label="主机地址"
              placeholder="例如：192.168.1.10"
              rules={[{ required: true }]}
            />
            <ProFormDigit
              name="port"
              label="端口"
              placeholder="3306 / 5432"
              fieldProps={{ min: 1, max: 65535 }}
              rules={[{ required: true, message: '请输入端口' }]}
            />
          </div>
          <div className="datasource-form-grid">
            <ProFormText
              name="database"
              label="数据库"
              placeholder="业务数据库"
              rules={[{ required: true, message: '请输入数据库名称' }]}
            />
            <ProFormText
              name="username"
              label="用户名"
              placeholder="数据库用户"
              rules={[{ required: true, message: '请输入用户名' }]}
            />
          </div>
          <ProFormText.Password
            name="password"
            label="密码"
            placeholder="不会由模板自动填写"
            rules={[{ required: true, message: '请输入数据库密码' }]}
          />
          <ProFormTextArea
            name="extraConfig"
            label="额外配置 (JSON)"
            placeholder='{"hive.metastore.uris": "thrift://hive:9083"}'
            fieldProps={{ autoSize: { minRows: 2, maxRows: 4 } }}
            rules={[{
              validator: async (_: unknown, value?: string) => {
                if (!value?.trim()) return;
                try {
                  JSON.parse(value);
                } catch {
                  throw new Error('请输入合法的 JSON 配置');
                }
              },
            }]}
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
