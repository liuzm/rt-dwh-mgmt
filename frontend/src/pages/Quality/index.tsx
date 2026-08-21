import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Badge, Button, Card, Col, Form, Input, InputNumber, message, Modal,
  Popconfirm, Progress, Row, Select, Space, Statistic, Switch, Table, Tabs, Tag,
} from 'antd';
import {
  CheckCircleOutlined, PlusOutlined, ReloadOutlined,
  SafetyCertificateOutlined, WarningOutlined,
} from '@ant-design/icons';
import { useRequest } from '@umijs/max';
import {
  createQualityRule, deleteQualityRule, getQualityAlerts, getQualityRules,
  resolveQualityAlert, runQualityCheck, toggleQualityRule, updateQualityRule,
} from '@/api';

const layerColorMap: Record<string, string> = {
  ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
};

const ruleTypeLabel: Record<string, string> = {
  null_rate: '空值率',
  uniqueness: '唯一性',
  volume_compare: '数据量',
  range_check: '范围检查',
};

const levelConfig: Record<string, { color: string; label: string }> = {
  info: { color: 'blue', label: '提示' },
  warn: { color: 'orange', label: '警告' },
  error: { color: 'red', label: '严重' },
};

const Quality: React.FC = () => {
  const [activeTab, setActiveTab] = useState('overview');
  const [layerFilter, setLayerFilter] = useState<string>();
  const [alertLevel, setAlertLevel] = useState<string>();
  const [alertResolved, setAlertResolved] = useState<boolean>();
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<API.QualityRule>();
  const [submitting, setSubmitting] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [form] = Form.useForm();

  const {
    data: rulesData,
    loading: rulesLoading,
    refresh: refreshRules,
  } = useRequest(() => getQualityRules({ layer: layerFilter }), { refreshDeps: [layerFilter] });
  const {
    data: alertsData,
    loading: alertsLoading,
    refresh: refreshAlerts,
  } = useRequest(
    () => getQualityAlerts({ level: alertLevel, resolved: alertResolved }),
    { refreshDeps: [alertLevel, alertResolved] },
  );

  const rules = (rulesData || []) as API.QualityRule[];
  const alerts = (alertsData || []) as API.QualityAlert[];
  const unresolvedCount = alerts.filter((alert) => !alert.resolved).length;
  const passRate = rules.length
    ? Math.max(0, Math.round(((rules.length - unresolvedCount) / rules.length) * 100))
    : 100;

  const latestAlertByRule = useMemo(() => {
    const map = new Map<number, API.QualityAlert>();
    alerts.forEach((alert) => {
      if (!map.has(alert.ruleId)) map.set(alert.ruleId, alert);
    });
    return map;
  }, [alerts]);

  const refreshAll = () => {
    refreshRules();
    refreshAlerts();
  };

  const openRuleModal = (rule?: API.QualityRule) => {
    setEditingRule(rule);
    form.resetFields();
    form.setFieldsValue(rule || { enabled: true, ruleType: 'null_rate', layer: 'ods' });
    setModalOpen(true);
  };

  const saveRule = async (values: API.QualityRule) => {
    setSubmitting(true);
    try {
      if (editingRule) {
        await updateQualityRule(editingRule.id, values);
        message.success('质量规则已更新');
      } else {
        await createQualityRule(values);
        message.success('质量规则已创建');
      }
      setModalOpen(false);
      refreshRules();
    } catch (error: any) {
      message.error(error?.message || '保存质量规则失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleRunCheck = async (ruleId?: number) => {
    setCheckingId(ruleId || -1);
    try {
      const count = await runQualityCheck(ruleId);
      message.success(`质量检查完成，发现 ${count || 0} 个异常`);
      refreshAlerts();
    } catch (error: any) {
      message.error(error?.message || '质量检查失败');
    } finally {
      setCheckingId(undefined);
    }
  };

  const handleResolveAlert = async (id: number) => {
    try {
      await resolveQualityAlert(id);
      message.success('告警已标记为解决');
      refreshAlerts();
    } catch (error: any) {
      message.error(error?.message || '处理告警失败');
    }
  };

  const handleToggle = async (rule: API.QualityRule, enabled: boolean) => {
    try {
      await toggleQualityRule(rule.id, enabled);
      message.success(enabled ? '规则已启用' : '规则已停用');
      refreshRules();
    } catch (error: any) {
      message.error(error?.message || '更新规则状态失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteQualityRule(id);
      message.success('质量规则已删除');
      refreshRules();
    } catch (error: any) {
      message.error(error?.message || '删除质量规则失败');
    }
  };

  const overviewColumns: any[] = [
    {
      title: '表',
      dataIndex: 'targetTable',
      key: 'table',
      render: (value: string, record: API.QualityRule) => (
        <Space size={6}>
          <Tag color={layerColorMap[record.layer]}>{record.layer?.toUpperCase()}</Tag>
          {value}
        </Space>
      ),
    },
    { title: '规则', dataIndex: 'ruleName', key: 'name' },
    {
      title: '类型', dataIndex: 'ruleType', key: 'type',
      render: (value: string) => ruleTypeLabel[value] || value,
    },
    { title: '阈值', dataIndex: 'threshold', key: 'threshold', width: 90 },
    {
      title: '实际值', key: 'actualValue', width: 100,
      render: (_: unknown, record: API.QualityRule) => latestAlertByRule.get(record.id)?.actualValue ?? '—',
    },
    {
      title: '状态', key: 'status', width: 100,
      render: (_: unknown, record: API.QualityRule) => {
        const latestAlert = latestAlertByRule.get(record.id);
        if (!record.enabled) return <Badge status="default" text="未启用" />;
        return latestAlert && !latestAlert.resolved
          ? <Badge status="error" text="异常" />
          : <Badge status="success" text="通过" />;
      },
    },
    {
      title: '最近检测', key: 'checkedAt', width: 170,
      render: (_: unknown, record: API.QualityRule) => {
        const value = latestAlertByRule.get(record.id)?.triggeredAt;
        return value ? new Date(value).toLocaleString('zh-CN') : '暂无异常记录';
      },
    },
    {
      title: '操作', key: 'action', width: 110,
      render: (_: unknown, record: API.QualityRule) => (
        <Button
          size="small"
          loading={checkingId === record.id}
          disabled={!record.enabled}
          onClick={() => handleRunCheck(record.id)}
        >
          重新检测
        </Button>
      ),
    },
  ];

  return (
    <PageContainer extra={<Button icon={<ReloadOutlined />} onClick={refreshAll}>刷新数据</Button>}>
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={24} md={8}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon" style={{ color: '#1890ff', background: '#e6f7ff' }}>
              <SafetyCertificateOutlined />
            </span>
            <Statistic title="质量规则总数" value={rules.length} suffix="条" valueStyle={{ color: '#1890ff' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon" style={{ color: '#52c41a', background: '#f6ffed' }}>
              <CheckCircleOutlined />
            </span>
            <Statistic title="整体通过率" value={passRate} suffix="%" valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon" style={{ color: '#ff4d4f', background: '#fff2f0' }}>
              <WarningOutlined />
            </span>
            <Statistic title="未解决告警" value={unresolvedCount} suffix="条" valueStyle={{ color: '#ff4d4f' }} />
          </Card>
        </Col>
      </Row>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'overview',
            label: '质量概览',
            children: (
              <>
                <Card style={{ marginBottom: 16 }}>
                  <div className="rtdwh-toolbar">
                    <Select
                      placeholder="筛选分层"
                      allowClear
                      value={layerFilter}
                      onChange={setLayerFilter}
                      style={{ width: 140 }}
                      options={['ods', 'dwd', 'dws', 'ads'].map((value) => ({ label: value.toUpperCase(), value }))}
                    />
                    <Button type="primary" loading={checkingId === -1} onClick={() => handleRunCheck()}>
                      立即检查全部
                    </Button>
                  </div>
                  <Table<API.QualityRule>
                    dataSource={rules}
                    rowKey="id"
                    loading={rulesLoading}
                    size="small"
                    columns={overviewColumns}
                    scroll={{ x: 900 }}
                  />
                </Card>
                <Card title="通过率概览">
                  <Progress percent={passRate} strokeColor="#52c41a" trailColor="#f0f2f5" />
                </Card>
              </>
            ),
          },
          {
            key: 'alerts',
            label: <Badge count={unresolvedCount} size="small" offset={[8, -2]}>异常告警</Badge>,
            children: (
              <Card>
                <div className="rtdwh-toolbar">
                  <Select
                    placeholder="告警级别"
                    allowClear
                    value={alertLevel}
                    onChange={setAlertLevel}
                    style={{ width: 130 }}
                    options={Object.entries(levelConfig).map(([value, config]) => ({ value, label: config.label }))}
                  />
                  <Select
                    placeholder="解决状态"
                    allowClear
                    value={alertResolved}
                    onChange={setAlertResolved}
                    style={{ width: 130 }}
                    options={[{ label: '未解决', value: false }, { label: '已解决', value: true }]}
                  />
                </div>
                <Table<API.QualityAlert>
                  dataSource={alerts}
                  rowKey="id"
                  loading={alertsLoading}
                  size="small"
                  columns={[
                    { title: '时间', dataIndex: 'triggeredAt', width: 170, render: (value) => value ? new Date(value).toLocaleString('zh-CN') : '—' },
                    { title: '表', dataIndex: 'targetTable', width: 160 },
                    { title: '告警内容', dataIndex: 'message', ellipsis: true },
                    { title: '实际值 / 阈值', width: 130, render: (_, record) => `${record.actualValue ?? '—'} / ${record.thresholdValue ?? '—'}` },
                    {
                      title: '级别', dataIndex: 'level', width: 90,
                      render: (value) => <Tag color={levelConfig[value]?.color}>{levelConfig[value]?.label || value}</Tag>,
                    },
                    { title: '状态', width: 90, render: (_, record) => <Badge status={record.resolved ? 'success' : 'error'} text={record.resolved ? '已解决' : '未解决'} /> },
                    {
                      title: '操作', width: 120,
                      render: (_, record) => !record.resolved && (
                        <Button size="small" type="primary" onClick={() => handleResolveAlert(record.id)}>标记已解决</Button>
                      ),
                    },
                  ]}
                  scroll={{ x: 900 }}
                />
              </Card>
            ),
          },
          {
            key: 'rules',
            label: '规则管理',
            children: (
              <Card>
                <div className="rtdwh-toolbar">
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => openRuleModal()}>新建质量规则</Button>
                </div>
                <Table<API.QualityRule>
                  dataSource={rules}
                  rowKey="id"
                  loading={rulesLoading}
                  size="small"
                  columns={[
                    { title: '规则名', dataIndex: 'ruleName' },
                    { title: '目标表', dataIndex: 'targetTable' },
                    { title: '字段', dataIndex: 'targetColumn', render: (value) => value || '全表' },
                    { title: '类型', dataIndex: 'ruleType', render: (value) => <Tag>{ruleTypeLabel[value] || value}</Tag> },
                    { title: '阈值', dataIndex: 'threshold', width: 90 },
                    {
                      title: '启用', dataIndex: 'enabled', width: 80,
                      render: (enabled, record) => <Switch size="small" checked={enabled} onChange={(checked) => handleToggle(record, checked)} />,
                    },
                    {
                      title: '操作', width: 140,
                      render: (_, record) => (
                        <Space size={4}>
                          <Button size="small" type="link" onClick={() => openRuleModal(record)}>编辑</Button>
                          <Popconfirm title="确定删除这条质量规则？" onConfirm={() => handleDelete(record.id)}>
                            <Button size="small" type="link" danger>删除</Button>
                          </Popconfirm>
                        </Space>
                      ),
                    },
                  ]}
                  scroll={{ x: 780 }}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        title={editingRule ? '编辑质量规则' : '新建质量规则'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={saveRule}>
          <Form.Item name="ruleName" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：订单 ID 唯一性" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="layer" label="数仓分层" rules={[{ required: true }]}>
                <Select options={['ods', 'dwd', 'dws', 'ads'].map((value) => ({ label: value.toUpperCase(), value }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="ruleType" label="规则类型" rules={[{ required: true }]}>
                <Select options={Object.entries(ruleTypeLabel).map(([value, label]) => ({ value, label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="targetTable" label="目标表" rules={[{ required: true, message: '请输入目标表' }]}>
            <Input placeholder="例如：ods_orders" />
          </Form.Item>
          <Form.Item name="targetColumn" label="目标字段">
            <Input placeholder="全表规则可留空" />
          </Form.Item>
          <Form.Item name="threshold" label="阈值" rules={[{ required: true, message: '请输入阈值' }]}>
            <InputNumber min={0} precision={4} style={{ width: '100%' }} placeholder="例如：0.05" />
          </Form.Item>
          <Form.Item name="expression" label="检查表达式">
            <Input.TextArea rows={3} placeholder="范围检查时填写，例如：amount >= 0" />
          </Form.Item>
          <Form.Item name="enabled" label="立即启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default Quality;
