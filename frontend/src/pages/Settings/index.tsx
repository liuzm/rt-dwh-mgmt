import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  Modal,
  Row,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
  message,
  Select,
} from 'antd';
import { useRequest } from '@umijs/max';
import {
  ApiOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  EditOutlined,
  ExclamationCircleFilled,
  HddOutlined,
  ReloadOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import {
  getFlinkClusterConfig,
  getHealthStatus,
  healthCheck,
  healthCheckComponent,
  testFlinkClusterConfig,
  updateFlinkClusterConfig,
} from '@/api';

type ComponentKey = 'flink' | 'paimon' | 'mysql';

const DEFAULT_FLINK_CONFIG: API.FlinkClusterConfig = {
  restApiUrl: 'http://localhost:8081',
  submissionMode: 'application',
  savepointDir: 'file:///tmp/flink-savepoints',
  sqlGatewayEnabled: false,
  sqlGatewayUrl: 'http://localhost:9083',
  flinkVersion: '',
  source: 'environment',
};

const statusMeta: Record<string, { label: string; color: string; tone: string }> = {
  healthy: { label: '运行正常', color: '#52c41a', tone: '#f6ffed' },
  degraded: { label: '部分异常', color: '#faad14', tone: '#fffbe6' },
  unhealthy: { label: '检查失败', color: '#ff4d4f', tone: '#fff2f0' },
  unreachable: { label: '无法连接', color: '#ff4d4f', tone: '#fff2f0' },
  not_initialized: { label: '待初始化', color: '#faad14', tone: '#fffbe6' },
  unknown: { label: '尚未检查', color: '#8c8c8c', tone: '#fafafa' },
};

const getStatusMeta = (status?: string) => statusMeta[status || 'unknown'] || statusMeta.unknown;

const formatTime = (value?: string) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false })
  : '—';

const formatResponseTime = (value?: number) => value == null ? '—' : `${value} ms`;

const HealthCard: React.FC<{
  component: ComponentKey;
  title: string;
  icon: React.ReactNode;
  result?: API.HealthComponent;
  checking: boolean;
  onCheck: (component: ComponentKey) => void;
}> = ({ component, title, icon, result, checking, onCheck }) => {
  const meta = getStatusMeta(result?.status);

  const details = useMemo(() => {
    if (component === 'flink') {
      return [
        ['访问地址', result?.endpoint || '—'],
        ['集群版本', result?.flinkVersion || '—'],
        ['运行任务', `${result?.runningJobs ?? 0} 个`],
        ['可用 Slot', `${result?.taskSlotsAvailable ?? 0} / ${result?.taskSlotsTotal ?? 0}`],
        ['TaskManager', `${result?.taskManagers ?? 0} 个`],
        ['响应耗时', formatResponseTime(result?.responseTimeMs)],
      ];
    }
    if (component === 'paimon') {
      return [
        ['Warehouse', result?.warehousePath || '—'],
        ['元数据库', result?.metastoreUri || '—'],
        ['库数量', `${result?.databaseCount ?? 0} 个`],
        ['连接模式', result?.readOnly ? '只读' : '可读写'],
        ['响应耗时', formatResponseTime(result?.responseTimeMs)],
      ];
    }
    return [
      ['数据库', result?.database || '—'],
      ['数据库版本', result?.dbVersion || '—'],
      ['驱动', result?.driver || '—'],
      ['连接模式', result?.readOnly ? '只读' : '可读写'],
      ['响应耗时', formatResponseTime(result?.responseTimeMs)],
    ];
  }, [component, result]);

  return (
    <Card className="settings-health-card">
      <div className="settings-health-card-header">
        <span className="settings-health-icon" style={{ color: meta.color, background: meta.tone }}>
          {icon}
        </span>
        <div className="settings-health-title">
          <div>{title}</div>
          <Tag color={result?.status === 'healthy'
            ? 'success'
            : result?.status === 'not_initialized' ? 'warning' : result ? 'error' : 'default'}>
            {meta.label}
          </Tag>
        </div>
        <Button
          size="small"
          icon={<ReloadOutlined spin={checking} />}
          disabled={checking}
          onClick={() => onCheck(component)}
        >
          检查
        </Button>
      </div>

      <div className="settings-health-details">
        {details.map(([label, value]) => (
          <div className="settings-health-detail" key={label}>
            <span>{label}</span>
            <Typography.Text ellipsis={{ tooltip: value }}>{value}</Typography.Text>
          </div>
        ))}
      </div>

      {result?.error && (
        <Alert
          className="settings-health-error"
          type="error"
          showIcon
          message="检查未通过"
          description={(
            <Space direction="vertical" size={2}>
              <span>{result.error}</span>
              {result.suggestion && <span className="settings-health-suggestion">建议：{result.suggestion}</span>}
              {result.contentType && <span className="settings-health-technical">响应类型：{result.contentType}</span>}
            </Space>
          )}
        />
      )}
    </Card>
  );
};

const Settings: React.FC = () => {
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [health, setHealth] = useState<API.SystemHealth>();
  const [checkingComponent, setCheckingComponent] = useState<ComponentKey>();
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [refreshingHealth, setRefreshingHealth] = useState(false);
  const [testResult, setTestResult] = useState<API.HealthComponent>();
  const [configLoadError, setConfigLoadError] = useState<string>();
  const [healthRequestError, setHealthRequestError] = useState<string>();
  const [form] = Form.useForm<API.FlinkClusterConfig>();

  const {
    data: configData,
    loading: configLoading,
    refresh: refreshConfig,
  } = useRequest(getFlinkClusterConfig, {
    onSuccess: () => setConfigLoadError(undefined),
    onError: (error: any) => setConfigLoadError(error?.message || '配置读取失败'),
  });

  const { loading: healthLoading } = useRequest(getHealthStatus, {
    pollingInterval: 30000,
    onSuccess: (data) => {
      setHealth(data);
      setHealthRequestError(undefined);
    },
    onError: (error: any) => setHealthRequestError(error?.message || '健康状态读取失败'),
  });

  const handleRefreshAll = async () => {
    setRefreshingHealth(true);
    try {
      const result = await healthCheck();
      setHealth(result);
      setHealthRequestError(undefined);
      message.success('系统健康检查已完成并保存');
    } catch (error: any) {
      setHealthRequestError(error?.message || '全量健康检查请求失败');
      message.error(error?.message || '系统健康检查失败');
    } finally {
      setRefreshingHealth(false);
    }
  };

  const config = configData;
  const editableConfig = config || DEFAULT_FLINK_CONFIG;
  const overallMeta = getStatusMeta(health?.overall);

  const openEditor = () => {
    form.setFieldsValue({
      restApiUrl: editableConfig.restApiUrl,
      submissionMode: editableConfig.submissionMode || 'application',
      flinkVersion: editableConfig.flinkVersion || health?.flink?.flinkVersion || '',
      savepointDir: editableConfig.savepointDir || 'file:///tmp/flink-savepoints',
      sqlGatewayEnabled: Boolean(editableConfig.sqlGatewayEnabled),
      sqlGatewayUrl: editableConfig.sqlGatewayUrl || 'http://localhost:9083',
    });
    setTestResult(undefined);
    setEditModalOpen(true);
  };

  const handleSaveConfig = async (values: API.FlinkClusterConfig) => {
    setSaving(true);
    try {
      await updateFlinkClusterConfig(values);
      message.success('配置已保存并立即生效');
      setEditModalOpen(false);
      await refreshConfig();
      void handleRefreshAll();
    } catch (error: any) {
      message.error(error?.message || '配置保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleTestDraft = async () => {
    try {
      const values = await form.validateFields();
      setTesting(true);
      setTestResult(undefined);
      const result = await testFlinkClusterConfig(values);
      setTestResult(result);
      if (result.status === 'healthy') {
        if (result.flinkVersion) {
          form.setFieldValue('flinkVersion', result.flinkVersion);
        }
        message.success(`连接成功，响应 ${result.responseTimeMs ?? 0} ms`);
      } else {
        message.error('连接测试未通过');
      }
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.message || '连接测试失败');
    } finally {
      setTesting(false);
    }
  };

  const handleTestCurrent = async () => {
    setCheckingComponent('flink');
    try {
      const result = await testFlinkClusterConfig(editableConfig);
      setHealth((previous) => previous ? { ...previous, flink: result, checkedAt: new Date().toISOString() } : previous);
      if (result.status === 'healthy') {
        message.success(`Flink 连接正常，响应 ${result.responseTimeMs ?? 0} ms`);
      } else {
        message.error(result.error || 'Flink 连接测试未通过');
      }
    } catch (error: any) {
      message.error(error?.message || 'Flink 连接测试失败');
    } finally {
      setCheckingComponent(undefined);
    }
  };

  const handleCheckComponent = async (component: ComponentKey) => {
    setCheckingComponent(component);
    try {
      const result = await healthCheckComponent(component);
      setHealth(result);
      message.success(`${component.toUpperCase()} 检查完成并已保存`);
    } catch (error: any) {
      message.error(error?.message || '检查失败');
    } finally {
      setCheckingComponent(undefined);
    }
  };

  return (
    <PageContainer
      className="settings-page"
      extra={[
        <Button
          key="refresh"
          icon={<ReloadOutlined spin={refreshingHealth} />}
          loading={refreshingHealth}
          onClick={handleRefreshAll}
        >
          全量检查
        </Button>,
      ]}
    >
      <Card
        className="settings-section-card"
        title={<Space><SettingOutlined />Flink 集群配置</Space>}
        extra={<Button type="primary" icon={<EditOutlined />} onClick={openEditor} loading={configLoading}>编辑配置</Button>}
        loading={configLoading}
      >
        {!config && !configLoading && (
          <Alert
            type="warning"
            showIcon
            message="配置暂未加载"
            description={configLoadError || '可以直接点击“编辑配置”，填写 Flink JobManager REST 地址并保存。'}
            action={<Button size="small" onClick={() => refreshConfig()}>重新加载</Button>}
          />
        )}
        {config && (
          <>
            {config.loadError && (
              <Alert type="warning" showIcon message={config.loadError} style={{ marginBottom: 16 }} />
            )}
            <Descriptions column={{ xs: 1, sm: 2, xl: 3 }} size="small">
              <Descriptions.Item label="REST API 地址">
                <Typography.Text copyable>{config.restApiUrl || '未配置'}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="提交模式">
                <Tag color="blue">{config.submissionMode === 'session' ? 'Session Mode' : 'Application Mode'}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="集群版本">
                {health?.flink?.flinkVersion || config.flinkVersion || '等待连接检测'}
              </Descriptions.Item>
              <Descriptions.Item label="Savepoint 目录">
                <Typography.Text copyable>{config.savepointDir || '未配置'}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="SQL Gateway">
                <Tag color={config.sqlGatewayEnabled ? 'success' : 'default'}>
                  {config.sqlGatewayEnabled ? '已启用' : '未启用'}
                </Tag>
                {config.sqlGatewayEnabled && config.sqlGatewayUrl}
              </Descriptions.Item>
              <Descriptions.Item label="配置来源">
                {config.source === 'database' ? '管理库持久化配置' : '部署环境变量'}
              </Descriptions.Item>
            </Descriptions>

            <div className="settings-config-footer">
              <span>最后更新：{formatTime(config.updatedAt)}</span>
              {config.updatedBy && <span>操作人：{config.updatedBy}</span>}
              <Button
                icon={<ApiOutlined />}
                loading={checkingComponent === 'flink'}
                onClick={handleTestCurrent}
              >
                测试当前连接
              </Button>
            </div>
          </>
        )}
      </Card>

      <Card
        className="settings-section-card"
        title="系统健康检查"
        extra={health && (
          <Space>
            <Tag color={health.overall === 'healthy' ? 'success' : health.overall === 'degraded' ? 'warning' : 'error'}>
              {overallMeta.label}
            </Tag>
            <span className="settings-check-time">
              <ClockCircleOutlined /> {formatTime(health.checkedAt)} · {health.durationMs ?? 0} ms
            </span>
          </Space>
        )}
      >
        <div className="settings-overall-bar" style={{ borderColor: overallMeta.color, background: overallMeta.tone }}>
          {healthLoading && !health ? (
            <Space><Spin size="small" />正在检查系统依赖...</Space>
          ) : healthRequestError && !health ? (
            <>
              <ExclamationCircleFilled style={{ color: '#ff4d4f' }} />
              <div>
                <strong>全量检查请求失败</strong>
                <span>{healthRequestError}，可使用下方单项“检查”继续诊断。</span>
              </div>
            </>
          ) : (
            <>
              {health?.overall === 'healthy'
                ? <CheckCircleFilled style={{ color: overallMeta.color }} />
                : <ExclamationCircleFilled style={{ color: overallMeta.color }} />}
              <div>
                <strong>{overallMeta.label}</strong>
                <span>
                  {health?.overall === 'healthy'
                    ? 'Flink、Paimon 元数据与管理数据库均可正常访问。'
                    : health?.overall === 'unknown'
                      ? '后台健康检查尚未产生结果，稍后会自动刷新，也可以立即执行全量检查。'
                      : '存在不可用依赖，请查看下方组件详情和错误信息。'}
                </span>
              </div>
            </>
          )}
        </div>

        <Row gutter={[16, 16]}>
          <Col xs={24} xl={8}>
            <HealthCard
              component="flink"
              title="Flink 集群"
              icon={<CloudServerOutlined />}
              result={health?.flink}
              checking={checkingComponent === 'flink'}
              onCheck={handleCheckComponent}
            />
          </Col>
          <Col xs={24} xl={8}>
            <HealthCard
              component="paimon"
              title="Paimon 存储"
              icon={<HddOutlined />}
              result={health?.paimon}
              checking={checkingComponent === 'paimon'}
              onCheck={handleCheckComponent}
            />
          </Col>
          <Col xs={24} xl={8}>
            <HealthCard
              component="mysql"
              title="MySQL 管理库"
              icon={<DatabaseOutlined />}
              result={health?.mysql}
              checking={checkingComponent === 'mysql'}
              onCheck={handleCheckComponent}
            />
          </Col>
        </Row>
      </Card>

      <Modal
        title="编辑 Flink 集群配置"
        open={editModalOpen}
        width={620}
        onCancel={() => setEditModalOpen(false)}
        footer={[
          <Button key="test" icon={<ApiOutlined />} loading={testing} onClick={handleTestDraft}>测试当前配置</Button>,
          <Button key="cancel" onClick={() => setEditModalOpen(false)}>取消</Button>,
          <Button key="save" type="primary" loading={saving} onClick={() => form.submit()}>保存并应用</Button>,
        ]}
      >
        <Form form={form} layout="vertical" onFinish={handleSaveConfig} preserve={false}>
          <Form.Item
            name="restApiUrl"
            label="REST API 地址"
            tooltip="Flink JobManager REST 服务地址"
            rules={[
              { required: true, message: '请输入 REST API 地址' },
              { type: 'url', message: '请输入有效的 HTTP(S) 地址' },
            ]}
          >
            <Input prefix={<ApiOutlined />} placeholder="http://localhost:8081" />
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="submissionMode" label="提交模式" rules={[{ required: true }]}>
                <Select options={[
                  { label: 'Application Mode', value: 'application' },
                  { label: 'Session Mode', value: 'session' },
                ]} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="flinkVersion" label="期望版本" tooltip="连接测试成功后会自动回填实际版本">
                <Input placeholder="例如 2.2.0" />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            name="savepointDir"
            label="Savepoint 目录"
            rules={[{ required: true, message: '请输入 Savepoint 目录' }]}
          >
            <Input placeholder="file:///tmp/flink-savepoints 或 s3://bucket/savepoints" />
          </Form.Item>

          <Form.Item name="sqlGatewayEnabled" label="启用 SQL Gateway" valuePropName="checked">
            <Switch />
          </Form.Item>

          <Form.Item noStyle shouldUpdate={(previous, current) => previous.sqlGatewayEnabled !== current.sqlGatewayEnabled}>
            {({ getFieldValue }) => getFieldValue('sqlGatewayEnabled') && (
              <Form.Item
                name="sqlGatewayUrl"
                label="SQL Gateway 地址"
                rules={[
                  { required: true, message: '请输入 SQL Gateway 地址' },
                  { type: 'url', message: '请输入有效的 HTTP(S) 地址' },
                ]}
              >
                <Input placeholder="http://localhost:9083" />
              </Form.Item>
            )}
          </Form.Item>

          {testResult && (
            <Alert
              showIcon
              type={testResult.status === 'healthy' ? 'success' : 'error'}
              message={testResult.status === 'healthy' ? '连接测试通过' : '连接测试失败'}
              description={testResult.status === 'healthy'
                ? `Flink ${testResult.flinkVersion || '未知版本'}，响应 ${testResult.responseTimeMs ?? 0} ms`
                : testResult.error || '目标服务未返回有效响应'}
            />
          )}
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default Settings;
