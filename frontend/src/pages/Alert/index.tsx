import React, { useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Card, Table, Tag, Space, Select, Button, Badge, Modal, Input, message, Switch, Form } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import {
  getAlertRules, getAlertRecords, createAlertRule, updateAlertRule,
  deleteAlertRule, toggleAlertRule, resolveAlertRecord,
  evaluateAlertRules,
} from '@/api';

const alertLevelColor: Record<string, string> = {
  info: 'default',
  warn: 'warning',
  error: 'error',
  low: 'default',
  medium: 'warning',
  high: 'error',
};

const alertLevelLabel: Record<string, string> = {
  info: 'Info',
  warn: 'Warn',
  error: 'Critical',
  low: '低',
  medium: '中',
  high: '高',
};

const channelLabel: Record<string, string> = {
  email: '邮件',
  dingtalk: '钉钉',
  wecom: '企微',
  slack: 'Slack',
};

const Alert: React.FC = () => {
  const access = useAccess();
  const [createRuleVisible, setCreateRuleVisible] = useState(false);
  const [editingRule, setEditingRule] = useState<any>(null);
  const [ruleTypeFilter, setRuleTypeFilter] = useState<string>();
  const [form] = Form.useForm();

  const { data: rulesData, loading: rulesLoading, refresh: refreshRules } = useRequest(getAlertRules);
  const { data: recordsData, loading: recordsLoading, refresh: refreshRecords } = useRequest(getAlertRecords, { pollingInterval: 30000 });

  const rules = ((rulesData || []) as any[]).filter((rule) => !ruleTypeFilter || rule.ruleType === ruleTypeFilter);
  const records = (recordsData || []) as any[];

  const handleCreateOrUpdate = async (values: any) => {
    try {
      const payload = {
        ruleName: values.ruleName,
        ruleType: values.ruleType,
        expression: values.expression || '',
        notifyChannel: (values.channels || []).join(','),
        enabled: values.enabled !== false,
      };
      if (editingRule) {
        await updateAlertRule(editingRule.id, payload);
        message.success('规则已更新');
      } else {
        await createAlertRule(payload);
        message.success('告警规则已创建');
      }
      setCreateRuleVisible(false);
      setEditingRule(null);
      form.resetFields();
      refreshRules();
    } catch (e) {
      message.error(editingRule ? '更新失败' : '创建失败');
    }
  };

  const handleToggle = async (id: number, enabled: boolean) => {
    try {
      await toggleAlertRule(id);
      message.success(enabled ? '规则已启用' : '规则已禁用');
      refreshRules();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteAlertRule(id);
      message.success('规则已删除');
      refreshRules();
    } catch (e) {
      message.error('删除失败');
    }
  };

  const handleResolve = async (id: number) => {
    try {
      await resolveAlertRecord(id);
      message.success('已标记为解决');
      refreshRecords();
    } catch (e) {
      message.error('操作失败');
    }
  };

  const handleEvaluate = async () => {
    try {
      const result = await evaluateAlertRules();
      message.success(`评估完成：新增 ${result.triggered || 0} 条，恢复 ${result.recovered || 0} 条`);
      refreshRecords();
    } catch (e) {
      message.error('告警评估失败');
    }
  };

  return (
    <PageContainer className="alert-page">
      <Space className="alert-toolbar" style={{ marginBottom: 32 }}>
        {access.canManageAlert && <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingRule(null); form.resetFields(); setCreateRuleVisible(true); }}>
          新增告警配置
        </Button>}
        {access.canManageAlert && <Button icon={<ReloadOutlined />} onClick={handleEvaluate}>立即评估</Button>}
        <Select
          value={ruleTypeFilter}
          onChange={setRuleTypeFilter}
          allowClear
          placeholder="全部类型"
          style={{ width: 160 }}
          options={[
            { label: '任务失败', value: 'task_failure' },
            { label: '延迟超限', value: 'data_delay' },
            { label: '质量异常', value: 'quality_failure' },
          ]}
        />
      </Space>

      <Card title="告警配置" className="alert-table-card">
        <Table
                  dataSource={rules}
                  rowKey="id"
                  loading={rulesLoading}
                  size="small"
                  columns={[
                    { title: '名称', dataIndex: 'ruleName', key: 'name', width: 240 },
                    {
                      title: '类型', dataIndex: 'ruleType', key: 'type', width: 180,
                      render: (v: string) => ({ task_failure: '任务失败', data_delay: '延迟超限', quality_failure: '质量异常' }[v] || v),
                    },
                    {
                      title: '通知渠道', dataIndex: 'notifyChannel', key: 'channels', width: 220,
                      render: (v: string) => (v || '—').split(',').filter(Boolean).map((c) => <Tag key={c}>{channelLabel[c] || c}</Tag>),
                    },
                    { title: '阈值', dataIndex: 'expression', key: 'expression', width: 180, render: (v: string) => v || '—' },
                    {
                      title: '启用',
                      dataIndex: 'enabled',
                      key: 'enabled',
                      width: 80,
                      render: (v: boolean, record: any) => (
                        <Switch checked={v} size="small" disabled={!access.canManageAlert} onChange={(checked) => handleToggle(record.id, checked)} />
                      ),
                    },
                    {
                      title: '操作', key: 'action', width: 120,
                      render: (_: any, record: any) => (
                        access.canManageAlert ? <Space>
                          <Button size="small" onClick={() => { setEditingRule(record); form.setFieldsValue({ ruleName: record.ruleName, ruleType: record.ruleType, expression: record.expression, channels: (record.notifyChannel || '').split(',').filter(Boolean), enabled: record.enabled }); setCreateRuleVisible(true); }}>编辑</Button>
                          <Button size="small" type="link" danger onClick={() => handleDelete(record.id)}>删除</Button>
                        </Space> : <span style={{ color: '#8c8c8c' }}>仅查看</span>
                      ),
                    },
                  ]}
                />
      </Card>

      <Card title="近期告警记录" className="alert-table-card" style={{ marginTop: 32 }}>
        <Table
                  dataSource={records}
                  rowKey="id"
                  loading={recordsLoading}
                  size="small"
                  columns={[
                    { title: '时间', dataIndex: 'triggeredAt', key: 'time', width: 220, render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '—' },
                    { title: '告警', dataIndex: 'ruleType', key: 'rule', width: 220, render: (v: string) => ({ task_failure: 'CDC任务失败告警', data_delay: '延迟超5秒告警', quality_failure: '质量检测失败' }[v] || v) },
                    { title: '内容', dataIndex: 'message', key: 'msg', ellipsis: true },
                    { title: '通知', dataIndex: 'notificationStatus', key: 'notificationStatus', width: 90, render: (v: string) => v ? <Tag>{v}</Tag> : '—' },
                    {
                      title: '级别',
                      dataIndex: 'level',
                      key: 'level',
                      width: 80,
                      render: (v) => <Tag color={alertLevelColor[v]}>{alertLevelLabel[v] || v}</Tag>,
                    },
                    {
                      title: '状态',
                      key: 'resolved',
                      width: 80,
                      render: (_: any, r: any) => <Badge status={r.resolved ? 'success' : 'error'} text={r.resolved ? '已解决' : '未解决'} />,
                    },
                    {
                      title: '操作',
                      key: 'action',
                      width: 120,
                      render: (_: any, r: any) => (
                        <Space>
                          {access.canManageAlert && !r.resolved && <Button size="small" type="primary" onClick={() => handleResolve(r.id)}>标记已解决</Button>}
                        </Space>
                      ),
                    },
                  ]}
                />
      </Card>

      <Modal
        title={editingRule ? '编辑告警规则' : '新建告警规则'}
        open={createRuleVisible}
        onCancel={() => { setCreateRuleVisible(false); setEditingRule(null); form.resetFields(); }}
        onOk={() => form.submit()}
        okText={editingRule ? '保存' : '创建规则'}
        width={640}
      >
        <Form form={form} layout="vertical" onFinish={handleCreateOrUpdate}>
          <Form.Item name="ruleName" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如: 任务延迟超限告警" />
          </Form.Item>
          <Form.Item name="ruleType" label="告警类型" rules={[{ required: true, message: '请选择告警类型' }]}>
            <Select options={[
              { label: '任务失败', value: 'task_failure' },
              { label: '延迟超限', value: 'data_delay' },
              { label: '质量异常', value: 'quality_failure' },
            ]} />
          </Form.Item>
          <Form.Item name="expression" label="阈值 / 表达式">
            <Input placeholder="例如: 5000ms 或失败次数 > 0" />
          </Form.Item>
          <Form.Item name="channels" label="通知渠道">
            <Select mode="multiple" options={[
              { label: '邮件', value: 'email' },
              { label: '钉钉', value: 'dingtalk' },
              { label: '企微', value: 'wecom' },
            ]} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked" initialValue>
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default Alert;
