import React, { useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert, Badge, Button, Card, Col, DatePicker, Descriptions, Empty, Form, Input, InputNumber, message, Modal,
  Popconfirm, Progress, Row, Select, Space, Statistic, Switch, Table, Tabs, Tag, Tooltip, Typography,
} from 'antd';
import {
  CheckCircleOutlined, ClockCircleOutlined, DatabaseOutlined,
  InfoCircleOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, WarningOutlined,
} from '@ant-design/icons';
import { history, useLocation, useAccess, useRequest } from '@umijs/max';
import dayjs from 'dayjs';
import {
  previewQualityRule, createQualityRule, deleteQualityRule, getQualityAlerts, getQualityRules,
  getQualityOverview, getQualityRuns, resolveQualityAlert, runQualityCheck, toggleQualityRule, updateQualityRule,
} from '@/api';
import './index.less';

const layers = ['ods', 'dwd', 'dws', 'ads'];

const layerColorMap: Record<string, string> = {
  ods: 'blue', dwd: 'green', dws: 'orange', ads: 'red',
};

const ruleTypeLabel: Record<string, string> = {
  null_rate: '空值率',
  uniqueness: '唯一率',
  volume_compare: '数据量下限',
  range_check: '越界率',
};

const ruleLogicConfig: Record<string, { thresholdLabel: string; hint: string; comparator: string }> = {
  null_rate: { thresholdLabel: '允许空值率上限', hint: '空值行数 ÷ 总行数，实际值不高于阈值时通过。', comparator: '≤' },
  uniqueness: { thresholdLabel: '唯一率下限', hint: '非空去重值数 ÷ 总行数（NULL 与重复的额外行均降低唯一率），实际值不低于阈值时通过。', comparator: '≥' },
  volume_compare: { thresholdLabel: '最小数据行数', hint: '统计所选检测范围内的总行数，实际行数不低于阈值时通过。', comparator: '≥' },
  range_check: { thresholdLabel: '允许越界率上限', hint: '表达式不成立或结果为 NULL 的行数 ÷ 总行数，实际值不高于阈值时通过。', comparator: '≤' },
};

const levelConfig: Record<string, { color: string; label: string; weight: number }> = {
  info: { color: 'blue', label: '提示', weight: 1 },
  low: { color: 'blue', label: '提示', weight: 1 },
  warn: { color: 'orange', label: '警告', weight: 2 },
  medium: { color: 'orange', label: '警告', weight: 2 },
  error: { color: 'red', label: '严重', weight: 3 },
  high: { color: 'red', label: '严重', weight: 3 },
};

const runStatusConfig: Record<string, { color: string; label: string }> = {
  passed: { color: 'green', label: '通过' },
  failed: { color: 'red', label: '未通过' },
  error: { color: 'volcano', label: '执行异常' },
  running: { color: 'blue', label: '执行中' },
};

const triggerTypeLabel: Record<string, string> = {
  manual: '手动', scheduled: '定时', production: '产出联动',
};

const toPercent = (value: number, total: number) => total ? Math.round((value / total) * 100) : 0;

const qualityDate = (value: any) => Array.isArray(value)
  ? dayjs(new Date(value[0], value[1] - 1, value[2], value[3] || 0, value[4] || 0, value[5] || 0)) : dayjs(value);

const formatDateTime = (value?: string) => value ? qualityDate(value).format('YYYY-MM-DD HH:mm:ss') : '—';

const formatNumber = (value?: number) => {
  if (value == null) return '—';
  if (Number.isInteger(value)) return value.toLocaleString('zh-CN');
  return value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
};

const formatQualityValue = (value?: number, ruleType?: string) => {
  if (value == null) return '—';
  if (ruleType === 'volume_compare') return `${Math.round(value).toLocaleString('zh-CN')} 行`;
  if (ruleType && ruleLogicConfig[ruleType]) {
    const percent = value * 100;
    return `${percent.toFixed(percent >= 10 ? 1 : 2).replace(/\.0+$/, '')}%`;
  }
  return formatNumber(value);
};

const Quality: React.FC = () => {
  const access = useAccess();
  const location = useLocation();
  const url = new URLSearchParams(location.search);
  const activeTab = ['overview', 'rules', 'alerts', 'runs'].includes(url.get('tab') || '') ? url.get('tab')! : 'overview';
  const setUrlValue = (key: string, value: string) => {
    const params = new URLSearchParams(location.search);
    value ? params.set(key, value) : params.delete(key);
    history.replace({ pathname: location.pathname, search: params.toString() });
  };
  const setActiveTab = (value: string) => setUrlValue('tab', value);
  const businessDate = /^\d{4}-\d{2}-\d{2}$/.test(url.get('businessDate') || '') && dayjs(url.get('businessDate')).isValid()
    ? url.get('businessDate')! : dayjs().subtract(1, 'day').format('YYYY-MM-DD');
  const targetTable = url.get('targetTable') || '';
  const matchesTarget = (table?: string, layer?: string) => !targetTable || table === targetTable
    || targetTable.endsWith(`.${table?.includes('.') ? table : `${layer}.${table}`}`);
  const [preview, setPreview] = useState<API.QualityPreview>();
  const [previewing, setPreviewing] = useState(false);
  const [layerFilter, setLayerFilter] = useState<string>();
  const [ruleTypeFilter, setRuleTypeFilter] = useState<string>();
  const [ruleSearch, setRuleSearch] = useState('');
  const [alertLevel, setAlertLevel] = useState<string>();
  const [alertResolved, setAlertResolved] = useState<boolean>();
  const [runStatus, setRunStatus] = useState<string>();
  const [runTrigger, setRunTrigger] = useState<string>();
  const [runSearch, setRunSearch] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editingRule, setEditingRule] = useState<API.QualityRule>();
  const [ruleFormInitialValues, setRuleFormInitialValues] = useState<API.QualityRuleInput>({
    enabled: true, ruleType: 'null_rate', layer: 'ods',
  } as API.QualityRuleInput);
  const [submitting, setSubmitting] = useState(false);
  const [checkingId, setCheckingId] = useState<number>();
  const [form] = Form.useForm<API.QualityRuleInput>();
  const selectedScope = Form.useWatch('checkScope', form) || 'full_table';
  const selectedRuleType = Form.useWatch('ruleType', form) || 'null_rate';
  const isChecking = checkingId !== undefined;

  const {
    data: rulesData,
    loading: rulesLoading,
    refresh: refreshRules,
  } = useRequest(() => getQualityRules());
  const {
    data: alertsData,
    loading: alertsLoading,
    refresh: refreshAlerts,
  } = useRequest(() => getQualityAlerts());
  const { data: runsData, loading: runsLoading, refresh: refreshRuns } = useRequest(getQualityRuns);
  const { data: overviewData, refresh: refreshOverview } = useRequest(() => getQualityOverview({ businessDate }), { refreshDeps: [businessDate] });

  const rules = (rulesData || []) as API.QualityRule[];
  const alerts = (alertsData || []) as API.QualityAlert[];
  const runs = (runsData || []) as API.QualityCheckRun[];
  const overview = overviewData as API.QualityOverviewSummary | undefined;

  const latestRunByRule = useMemo(() => {
    const map = new Map<number, API.QualityCheckRun>();
    (overview?.latestRuns || []).forEach((run) => {
      const current = map.get(run.ruleId);
      if (!current || qualityDate(run.startedAt).valueOf() > qualityDate(current.startedAt).valueOf()) {
        map.set(run.ruleId, run);
      }
    });
    return map;
  }, [overview?.latestRuns]);

  const currentRunByRule = useMemo(() => {
    const map = new Map<number, API.QualityCheckRun>();
    rules.forEach((rule) => {
      const latest = latestRunByRule.get(rule.id);
      if (latest && latest.ruleVersion === rule.version && latest.status !== 'running') map.set(rule.id, latest);
    });
    return map;
  }, [latestRunByRule, rules]);

  const metrics = useMemo(() => {
    const enabledRules = rules.filter((rule) => rule.enabled);
    const checkedRules = enabledRules.filter((rule) => currentRunByRule.has(rule.id));
    const passedRules = checkedRules.filter((rule) => currentRunByRule.get(rule.id)?.status === 'passed');
    const unresolvedAlerts = alerts.filter((alert) => !alert.resolved);
    const severeAlerts = unresolvedAlerts.filter((alert) => (levelConfig[alert.level]?.weight || 1) >= 3);
    const enabledRate = toPercent(enabledRules.length, rules.length);
    const checkCoverage = toPercent(checkedRules.length, enabledRules.length);
    const passRate = toPercent(passedRules.length, checkedRules.length);
    return {
      enabledRules,
      checkedRules,
      unresolvedAlerts,
      severeAlerts,
      enabledRate,
      checkCoverage,
      passRate,
      avgDuration: overview?.averageDurationMs || 0,
      last24hRuns: overview?.last24hRuns || 0,
      coveredTables: new Set(enabledRules.map((rule) => `${rule.layer}.${rule.targetTable}`)).size,
      neverChecked: enabledRules.length - checkedRules.length,
      disabledRules: rules.length - enabledRules.length,
    };
  }, [alerts, currentRunByRule, overview?.averageDurationMs, overview?.last24hRuns, rules]);

  const layerStats = useMemo(() => layers.map((layer) => {
    const layerRules = rules.filter((rule) => rule.layer?.toLowerCase() === layer);
    const enabledRules = layerRules.filter((rule) => rule.enabled);
    const checkedRules = enabledRules.filter((rule) => currentRunByRule.has(rule.id));
    const passedRules = checkedRules.filter((rule) => currentRunByRule.get(rule.id)?.status === 'passed');
    const ruleIds = new Set(layerRules.map((rule) => rule.id));
    const unresolvedAlerts = alerts.filter((alert) => !alert.resolved && ruleIds.has(alert.ruleId));
    return {
      layer,
      ruleCount: layerRules.length,
      tableCount: new Set(enabledRules.map((rule) => rule.targetTable)).size,
      checkedCount: checkedRules.length,
      passRate: toPercent(passedRules.length, checkedRules.length),
      unresolvedCount: unresolvedAlerts.length,
    };
  }), [alerts, currentRunByRule, rules]);

  const trendData = useMemo(() => {
    const dailyRuns = new Map((overview?.dailyRuns || []).map((item) => [qualityDate(item.date).format('YYYY-MM-DD'), item]));
    return Array.from({ length: 7 }, (_, index) => {
    const date = dayjs().subtract(6 - index, 'day');
    const summary = dailyRuns.get(date.format('YYYY-MM-DD'));
    const total = summary?.total || 0;
    const passed = summary?.passed || 0;
    const abnormal = summary?.abnormal || 0;
    return {
      key: date.format('YYYY-MM-DD'),
      label: date.format('MM/DD'),
      total,
      passed,
      abnormal,
      passRate: toPercent(passed, passed + abnormal),
    };
    });
  }, [overview?.dailyRuns]);

  const visibleRules = useMemo(() => {
    const keyword = ruleSearch.trim().toLowerCase();
    return rules.filter((rule) => {
      if (!matchesTarget(rule.targetTable, rule.layer)) return false;
    if (layerFilter && rule.layer !== layerFilter) return false;
      if (ruleTypeFilter && rule.ruleType !== ruleTypeFilter) return false;
      if (!keyword) return true;
      return [rule.ruleName, rule.targetTable, rule.targetColumn]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword));
    });
  }, [layerFilter, ruleSearch, ruleTypeFilter, rules, targetTable]);

  const visibleAlerts = useMemo(() => alerts.filter((alert) => {
    if (!matchesTarget(alert.targetTable, alert.layer)) return false;
    if (alertLevel && alert.level !== alertLevel) return false;
    return alertResolved == null || alert.resolved === alertResolved;
  }), [alertLevel, alertResolved, alerts, targetTable]);

  const visibleRuns = useMemo(() => runs.filter((run) => {
    if (!matchesTarget(run.targetTable, run.layer)) return false;
    if (runStatus && run.status !== runStatus) return false;
    if (runTrigger && run.triggerType !== runTrigger) return false;
    const keyword = runSearch.trim().toLowerCase();
    if (!keyword) return true;
    return [run.ruleName, run.batchId, run.targetTable, run.targetColumn, run.errorMessage]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(keyword));
  }), [runSearch, runStatus, runTrigger, runs, targetTable]);

  const priorityRisks = useMemo(() => [...metrics.unresolvedAlerts]
    .sort((left, right) => {
      const levelDiff = (levelConfig[right.level]?.weight || 1) - (levelConfig[left.level]?.weight || 1);
      return levelDiff || qualityDate(right.triggeredAt).valueOf() - qualityDate(left.triggeredAt).valueOf();
    })
    .slice(0, 5), [metrics.unresolvedAlerts]);

  const refreshAll = () => {
    refreshRules();
    refreshAlerts();
    refreshRuns();
    refreshOverview();
  };

  const openRuleModal = (rule?: API.QualityRule) => {
    const initialValues: API.QualityRuleInput = rule ? {
      ruleName: rule.ruleName,
      layer: rule.layer,
      ruleType: rule.ruleType,
      targetTable: rule.targetTable,
      targetColumn: rule.targetColumn,
      expression: rule.expression,
      threshold: rule.threshold,
      checkScope: rule.checkScope || 'full_table', timeColumn: rule.timeColumn, emptyPolicy: rule.emptyPolicy || 'fail',
      enabled: rule.enabled,
    } : {
      ruleName: '', layer: (targetTable.split('.').at(-2) || 'ods'), ruleType: 'null_rate', targetTable,
      targetColumn: '', threshold: 0.05, enabled: true, checkScope: 'full_table', emptyPolicy: 'fail',
    };
    setPreview(undefined);
    setEditingRule(rule);
    setRuleFormInitialValues(initialValues);
    setModalOpen(true);
  };

  const saveRule = async (values: API.QualityRuleInput) => {
    setSubmitting(true);
    try {
      const payload: API.QualityRuleInput = {
        ...values,
        ruleName: values.ruleName.trim(),
        targetTable: values.targetTable.trim(),
        targetColumn: values.ruleType === 'volume_compare' ? undefined : values.targetColumn?.trim(),
        expression: values.ruleType === 'range_check' ? values.expression?.trim() : undefined,
        enabled: values.enabled ?? true,
      };
      if (editingRule) {
        await updateQualityRule(editingRule.id, payload);
        message.success('质量规则已更新');
      } else {
        await createQualityRule(payload);
        message.success('质量规则已创建');
      }
      setModalOpen(false);
      refreshAll();
    } catch (error: any) {
      message.error(error?.message || '保存质量规则失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleRunCheck = async (ruleId?: number) => {
    if (isChecking) return;
    setCheckingId(ruleId ?? -1);
    try {
      let result: API.QualityCheckSummary;
      try {
        result = await runQualityCheck(ruleId, businessDate);
      } catch (error: any) {
        message.error(error?.message || '质量检查失败');
        return;
      }
      if (!result.total) {
        message.info('当前没有可执行的启用规则');
      } else if (result.errorCount) {
        message.warning(`检查完成：通过 ${result.passed} 条，未通过 ${result.failed} 条，执行异常 ${result.errorCount} 条`);
      } else if (result.failed) {
        message.warning(`检查完成：通过 ${result.passed} 条，未通过 ${result.failed} 条`);
      } else {
        message.success(`检查完成：${result.passed} 条规则全部通过`);
      }
      setActiveTab('runs');
      const refreshed = await Promise.allSettled([refreshAlerts(), refreshRuns(), refreshOverview()]);
      if (refreshed.some((item) => item.status === 'rejected')) {
        message.warning('检查已完成，但部分结果刷新失败，请稍后点击刷新数据');
      }
    } finally {
      setCheckingId(undefined);
    }
  };

  const handleResolveAlert = async (id: number) => {
    try {
      await resolveQualityAlert(id);
      message.success('告警已人工确认；检测结果和产出门禁不会因此改为通过');
      refreshAlerts();
    } catch (error: any) {
      message.error(error?.message || '处理告警失败');
    }
  };

  const handleToggle = async (rule: API.QualityRule, enabled: boolean) => {
    try {
      await toggleQualityRule(rule.id, enabled);
      message.success(enabled ? '规则已启用' : '规则已停用');
      refreshAll();
    } catch (error: any) {
      message.error(error?.message || '更新规则状态失败');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteQualityRule(id);
      message.success('质量规则已删除');
      refreshAll();
    } catch (error: any) {
      message.error(error?.message || '删除质量规则失败');
    }
  };

  const ruleFilters = (
    <div className="rtdwh-toolbar quality-filter-bar">
      <Input.Search
        allowClear
        value={ruleSearch}
        onChange={(event) => setRuleSearch(event.target.value)}
        placeholder="搜索规则、表或字段"
        className="quality-rule-search"
      />
      <Select
        placeholder="数仓分层"
        allowClear
        value={layerFilter}
        onChange={setLayerFilter}
        options={layers.map((value) => ({ label: value.toUpperCase(), value }))}
      />
      <Select
        placeholder="规则类型"
        allowClear
        value={ruleTypeFilter}
        onChange={setRuleTypeFilter}
        options={Object.entries(ruleTypeLabel).map(([value, label]) => ({ value, label }))}
      />
    </div>
  );

  const managementColumns: any[] = [
    {
      title: '质量规则',
      dataIndex: 'ruleName',
      key: 'rule',
      width: 250,
      render: (value: string, record: API.QualityRule) => (
        <div className="quality-rule-info">
          <span><Tag color={layerColorMap[record.layer]}>{record.layer?.toUpperCase()}</Tag><b>{value}</b></span>
          <small>{record.targetTable}{record.targetColumn ? ` · ${record.targetColumn}` : ' · 全表'}</small>
        </div>
      ),
    },
    {
      title: '判定条件',
      key: 'condition',
      width: 180,
      render: (_: unknown, record: API.QualityRule) => (
        <div className="quality-rule-condition">
          <span>{ruleTypeLabel[record.ruleType] || record.ruleType}</span>
          <small>通过条件：实际值 {ruleLogicConfig[record.ruleType]?.comparator || '—'} {formatQualityValue(record.threshold, record.ruleType)}</small>
        </div>
      ),
    },
    { title: '检测范围', width: 170, render: (_: unknown, rule: API.QualityRule) => <div>
      <Tag>{rule.checkScope === 'business_window' ? '业务窗口' : '全表'}</Tag>
      <small>{rule.timeColumn || '全部数据'} · 空数据{rule.emptyPolicy === 'allow' ? '按指标判断' : '不通过'}</small>
    </div> },
    {
      title: '最近结果', key: 'status', width: 150,
      render: (_: unknown, record: API.QualityRule) => {
        const latestRun = currentRunByRule.get(record.id);
        const historicalRun = latestRunByRule.get(record.id);
        if (!record.enabled) return <Badge status="default" text="未启用" />;
        if (historicalRun?.status === 'running' && historicalRun.ruleVersion === record.version) return <Badge status="processing" text="检测中" />;
        if (!latestRun && historicalRun) return <Badge status="warning" text="规则已变更，待检测" />;
        if (!latestRun) return <Badge status="default" text="尚未检测" />;
        const config = runStatusConfig[latestRun.status];
        return (
          <div className="quality-latest-result">
            <Tag color={config?.color || 'default'}>{config?.label || latestRun.status}</Tag>
            <small>实际 {formatQualityValue(latestRun.actualValue, latestRun.ruleType || record.ruleType)}</small>
          </div>
        );
      },
    },
    {
      title: '最近执行', key: 'checkedAt', width: 165,
      render: (_: unknown, record: API.QualityRule) => formatDateTime(latestRunByRule.get(record.id)?.startedAt),
    },
    {
      title: '启用', dataIndex: 'enabled', width: 68,
      render: (enabled: boolean, record: API.QualityRule) => (
        <Switch
          size="small"
          checked={enabled}
          disabled={!access.canManageQuality || isChecking}
          onChange={(checked) => handleToggle(record, checked)}
        />
      ),
    },
    {
      title: '操作', key: 'action', width: 190, fixed: 'right',
      render: (_: unknown, record: API.QualityRule) => access.canManageQuality ? (
        <Space size={2}>
          <Button
            size="small"
            type="link"
            loading={checkingId === record.id}
            disabled={!record.enabled || (isChecking && checkingId !== record.id)}
            onClick={() => handleRunCheck(record.id)}
          >
            检测
          </Button>
          <Button size="small" type="link" disabled={isChecking} onClick={() => openRuleModal(record)}>编辑</Button>
          <Popconfirm disabled={isChecking} title="确定删除这条质量规则？" onConfirm={() => handleDelete(record.id)}>
            <Button size="small" type="link" danger disabled={isChecking}>删除</Button>
          </Popconfirm>
        </Space>
      ) : <span className="quality-muted">仅查看</span>,
    },
  ];

  return (
    <PageContainer
      title={(
        <span className="quality-page-title">
          <SafetyCertificateOutlined />
          质量工作台
        </span>
      )}
      subTitle="配置规则、按业务窗口检测、追溯异常与产出门禁"
      className="quality-page"
      extra={(
        <Space wrap size={8} className="quality-header-actions">
          <Button icon={<ReloadOutlined />} onClick={refreshAll}>刷新数据</Button>
          {access.canManageQuality && (
            <Button
              type="primary"
              icon={<CheckCircleOutlined />}
              loading={checkingId === -1}
              disabled={isChecking && checkingId !== -1}
              onClick={() => handleRunCheck()}
            >
              检查全部可访问启用规则
            </Button>
          )}
        </Space>
      )}
    >
      <Space wrap style={{ marginBottom: 16 }}>
        <span>业务日期</span>
        <DatePicker value={dayjs(businessDate)} allowClear={false} onChange={(date) => date && setUrlValue('businessDate', date.format('YYYY-MM-DD'))} />
        <Typography.Text type="secondary">窗口规则按该日期检测；全表规则检查全部数据。定时检测使用前一日。</Typography.Text>
        {targetTable && <Tag closable onClose={() => setUrlValue('targetTable', '')}>目标：{targetTable}</Tag>}
      </Space>
      <Row gutter={[12, 12]} className="quality-metrics">
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card quality-health-metric">
            <span className="rtdwh-metric-icon quality-icon-blue"><SafetyCertificateOutlined /></span>
            <Statistic title="当前版本检测覆盖率" value={metrics.enabledRules.length ? metrics.checkCoverage : '—'} suffix={metrics.enabledRules.length ? '%' : undefined} />
            <div className="quality-metric-foot">按所选业务日期与当前规则版本统计</div>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon quality-icon-green"><CheckCircleOutlined /></span>
            <Statistic title="最新检测通过率" value={metrics.checkedRules.length ? metrics.passRate : '—'} suffix={metrics.checkedRules.length ? '%' : undefined} />
            <div className="quality-metric-foot">已检测 {metrics.checkedRules.length} 条，待检测 {metrics.neverChecked} 条</div>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon quality-icon-purple"><DatabaseOutlined /></span>
            <Statistic title="质量覆盖数据表" value={metrics.coveredTables} suffix="张" />
            <div className="quality-metric-foot">启用 {metrics.enabledRules.length} / {rules.length} 条规则</div>
          </Card>
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <Card className="rtdwh-metric-card">
            <span className="rtdwh-metric-icon quality-icon-red"><WarningOutlined /></span>
            <Statistic title="未解决告警" value={metrics.unresolvedAlerts.length} suffix="条" />
            <div className="quality-metric-foot">其中严重告警 {metrics.severeAlerts.length} 条</div>
          </Card>
        </Col>
      </Row>

      <Tabs
        className="quality-tabs"
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'overview',
            label: '质量概览',
            children: (
              <>
                <Row gutter={[12, 12]}>
                  <Col xs={24} xl={9}>
                    <Card title="检测覆盖与通过情况" className="quality-panel-card">
                      <div className="quality-health-layout">
                        <div className="quality-progress-list">
                          <div>
                            <span>规则启用率</span><b>{metrics.enabledRate}%</b>
                            <Progress percent={metrics.enabledRate} showInfo={false} size="small" />
                          </div>
                          <div>
                            <span>检测覆盖率</span><b>{metrics.checkCoverage}%</b>
                            <Progress percent={metrics.checkCoverage} showInfo={false} size="small" strokeColor="#722ed1" />
                          </div>
                          <div>
                            <span>最新通过率</span><b>{metrics.passRate}%</b>
                            <Progress percent={metrics.passRate} showInfo={false} size="small" strokeColor="#52c41a" />
                          </div>
                        </div>
                      </div>
                      <div className="quality-health-summary">
                        <span><ClockCircleOutlined /> 近 24 小时检测 <b>{metrics.last24hRuns}</b> 次</span>
                        <span>平均耗时 <b>{metrics.avgDuration.toLocaleString('zh-CN')}</b> ms</span>
                      </div>
                    </Card>
                  </Col>
                  <Col xs={24} xl={15}>
                    <Card title="数仓分层质量" className="quality-panel-card">
                      <div className="quality-layer-grid">
                        {layerStats.map((item) => (
                          <button
                            type="button"
                            className={`quality-layer-card${layerFilter === item.layer ? ' is-active' : ''}`}
                            key={item.layer}
                            aria-pressed={layerFilter === item.layer}
                            onClick={() => setLayerFilter((current) => current === item.layer ? undefined : item.layer)}
                          >
                            <div className="quality-layer-head">
                              <Tag color={layerColorMap[item.layer]}>{item.layer.toUpperCase()}</Tag>
                              <b className={item.unresolvedCount ? 'quality-danger' : 'quality-muted'}>
                                {item.unresolvedCount ? `${item.unresolvedCount} 个异常` : item.checkedCount ? '无待处理告警' : '尚未检测'}
                              </b>
                            </div>
                            <strong>{item.checkedCount ? `${item.passRate}%` : '—'}</strong>
                            <span>最新通过率</span>
                            <Progress percent={item.passRate} showInfo={false} size="small" />
                            <div className="quality-layer-meta">
                              <span>{item.ruleCount} 条规则</span>
                              <span>{item.tableCount} 张表</span>
                              <span>{item.checkedCount} 条已检测</span>
                            </div>
                          </button>
                        ))}
                      </div>
                    </Card>
                  </Col>
                </Row>

                <Row gutter={[12, 12]} className="quality-section-row">
                  <Col xs={24} xl={14}>
                    <Card title="近 7 天检测趋势" className="quality-panel-card">
                      <div className="quality-trend-legend">
                        <span><i className="quality-dot quality-dot-success" />通过</span>
                        <span><i className="quality-dot quality-dot-danger" />异常</span>
                      </div>
                      <div className="quality-trend">
                        {trendData.map((item) => (
                          <div className="quality-trend-row" key={item.key}>
                            <span className="quality-trend-date">{item.label}</span>
                            <div className="quality-trend-track">
                              {item.total ? (
                                <>
                                  <i className="quality-trend-passed" style={{ width: `${toPercent(item.passed, item.total)}%` }} />
                                  <i className="quality-trend-failed" style={{ width: `${toPercent(item.abnormal, item.total)}%` }} />
                                </>
                              ) : <span className="quality-trend-empty">当日无检测</span>}
                            </div>
                            <span className="quality-trend-value">{item.total} 次 / {item.passRate}%</span>
                          </div>
                        ))}
                      </div>
                    </Card>
                  </Col>
                  <Col xs={24} xl={10}>
                    <Card
                      title="待处理风险"
                      className="quality-panel-card"
                      extra={metrics.unresolvedAlerts.length > 0 && <Button type="link" onClick={() => setActiveTab('alerts')}>查看全部</Button>}
                    >
                      {priorityRisks.length ? (
                        <div className="quality-risk-list">
                          {priorityRisks.map((alert) => (
                            <button type="button" key={alert.id} onClick={() => setActiveTab('alerts')}>
                              <Tag color={levelConfig[alert.level]?.color || 'default'}>
                                {levelConfig[alert.level]?.label || alert.level}
                              </Tag>
                              <span className="quality-risk-main">
                                <b>{alert.targetTable}</b>
                                <small>{alert.message}</small>
                              </span>
                              <time>{qualityDate(alert.triggeredAt).format('MM-DD HH:mm')}</time>
                            </button>
                          ))}
                        </div>
                      ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待处理质量风险" />}
                    </Card>
                  </Col>
                </Row>

                {(metrics.neverChecked > 0 || metrics.disabledRules > 0) && (
                  <Alert
                    className="quality-governance-tip"
                    type="warning"
                    showIcon
                    message="质量覆盖仍有提升空间"
                    description={`当前有 ${metrics.neverChecked} 条启用规则尚未检测，${metrics.disabledRules} 条规则处于停用状态。建议先补齐检测覆盖，再处理失败规则。`}
                  />
                )}

              </>
            ),
          },
          { key: 'rules', label: '质量规则', children: (
                <Card
                  title="规则管理"
                  className="quality-rule-card"
                  extra={access.canManageQuality && (
                    <Button type="primary" size="small" icon={<PlusOutlined />} disabled={isChecking} onClick={() => openRuleModal()}>
                      新建规则
                    </Button>
                  )}
                >
                  <div className="quality-rule-toolbar">{ruleFilters}</div>
                  <Table<API.QualityRule>
                    dataSource={visibleRules}
                    rowKey="id"
                    loading={rulesLoading}
                    size="small"
                    columns={managementColumns}
                    locale={{ emptyText: '暂无匹配的质量规则' }}
                    pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
                    scroll={{ x: 1050 }}
                  />
                </Card>
          ) },
          {
            key: 'alerts',
            label: <Badge count={metrics.unresolvedAlerts.length} size="small" offset={[8, -2]}>异常告警</Badge>,
            children: (
              <Card>
                <div className="rtdwh-toolbar quality-filter-bar">
                  <Select
                    placeholder="告警级别"
                    allowClear
                    value={alertLevel}
                    onChange={setAlertLevel}
                    options={[
                      { value: 'error', label: '严重' },
                      { value: 'warn', label: '警告' },
                      { value: 'info', label: '提示' },
                    ]}
                  />
                  <Select
                    placeholder="处置状态"
                    allowClear
                    value={alertResolved}
                    onChange={setAlertResolved}
                    options={[{ label: '未解决', value: false }, { label: '已解决', value: true }]}
                  />
                  <span className="quality-filter-result">筛选结果 {visibleAlerts.length} 条</span>
                </div>
                <Table<API.QualityAlert>
                  dataSource={visibleAlerts}
                  rowKey="id"
                  loading={alertsLoading}
                  size="small"
                  columns={[
                    { title: '发生时间', dataIndex: 'triggeredAt', width: 170, render: formatDateTime },
                    { title: '目标表', dataIndex: 'targetTable', width: 180 },
                    { title: '检测窗口', width: 160, render: (_, record) => record.windowStart ? `${formatDateTime(record.windowStart)} 起` : '全表' },
                    { title: '告警内容', dataIndex: 'message', ellipsis: true },
                    {
                      title: '实际值 / 阈值', width: 140,
                      render: (_, record) => `${formatQualityValue(record.actualValue, record.ruleType)} / ${formatQualityValue(record.thresholdValue, record.ruleType)}`,
                    },
                    {
                      title: '级别', dataIndex: 'level', width: 90,
                      render: (value) => <Tag color={levelConfig[value]?.color}>{levelConfig[value]?.label || value}</Tag>,
                    },
                    {
                      title: '处置状态', width: 100,
                      render: (_, record) => <Badge status={record.resolved ? 'success' : 'error'} text={!record.resolved ? '待处理' : record.resolutionReason === 'recovered' ? '检测恢复' : record.resolutionReason === 'acknowledged' ? '人工确认' : '规则变更关闭'} />,
                    },
                    {
                      title: '操作', width: 120, fixed: 'right',
                      render: (_, record) => access.canManageQuality && !record.resolved ? (
                        <Button size="small" type="primary" onClick={() => handleResolveAlert(record.id)}>确认告警</Button>
                      ) : <span className="quality-muted">—</span>,
                    },
                  ]}
                  locale={{ emptyText: '暂无匹配的质量告警' }}
                  pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
                  scroll={{ x: 980 }}
                />
              </Card>
            ),
          },
          {
            key: 'runs',
            label: '执行记录',
            children: (
              <Card
                title="规则执行记录"
                extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => refreshRuns()}>刷新记录</Button>}
              >
                <div className="rtdwh-toolbar quality-filter-bar">
                  <Input.Search
                    allowClear
                    value={runSearch}
                    onChange={(event) => setRunSearch(event.target.value)}
                    placeholder="搜索规则、表或批次 ID"
                    className="quality-run-search"
                  />
                  <Select
                    placeholder="执行状态"
                    allowClear
                    value={runStatus}
                    onChange={setRunStatus}
                    options={Object.entries(runStatusConfig).map(([value, config]) => ({ value, label: config.label }))}
                  />
                  <Select
                    placeholder="触发方式"
                    allowClear
                    value={runTrigger}
                    onChange={setRunTrigger}
                    options={Object.entries(triggerTypeLabel).map(([value, label]) => ({ value, label }))}
                  />
                  <span className="quality-filter-result">当前展示最近 100 条，筛选结果 {visibleRuns.length} 条</span>
                </div>
                <Table<API.QualityCheckRun>
                  dataSource={visibleRuns}
                  rowKey="id"
                  loading={runsLoading}
                  size="small"
                  rowClassName="quality-run-row"
                  columns={[
                    { title: '开始时间', dataIndex: 'startedAt', width: 165, render: formatDateTime },
                    {
                      title: '批次 ID', dataIndex: 'batchId', width: 125,
                      render: (value: string) => (
                        <Typography.Text code copyable={{ text: value }}>{value.slice(0, 8)}</Typography.Text>
                      ),
                    },
                    {
                      title: '质量规则', dataIndex: 'ruleName', width: 220,
                      render: (value: string, record: API.QualityCheckRun) => (
                        <div className="quality-run-rule">
                          <b>{value}</b>
                          <small>{record.targetTable || '—'}{record.targetColumn ? ` · ${record.targetColumn}` : ''}</small>
                        </div>
                      ),
                    },
                    {
                      title: '检查类型', dataIndex: 'ruleType', width: 105,
                      render: (value: string) => ruleTypeLabel[value] || value || '—',
                    },
                    {
                      title: '触发方式', dataIndex: 'triggerType', width: 90,
                      render: (value) => triggerTypeLabel[value] || value,
                    },
                    {
                      title: '实际值 / 阈值', width: 150,
                      render: (_, record) => (
                        <span>{formatQualityValue(record.actualValue, record.ruleType)} / {formatQualityValue(record.thresholdValue, record.ruleType)}</span>
                      ),
                    },
                    {
                      title: '耗时', dataIndex: 'durationMs', width: 90,
                      render: (value) => value == null ? '—' : `${Number(value).toLocaleString('zh-CN')} ms`,
                    },
                    {
                      title: '状态', dataIndex: 'status', width: 95,
                      render: (value: API.QualityCheckRun['status']) => (
                        <Tag color={runStatusConfig[value]?.color || 'default'}>{runStatusConfig[value]?.label || value}</Tag>
                      ),
                    },
                    { title: '检测行数 / 异常行数', width: 155, render: (_, record) => `${record.checkedRows ?? '—'} / ${record.violationRows ?? '不适用'}` },
                    { title: '检测范围', width: 170, render: (_, record) => record.windowStart ? `${qualityDate(record.windowStart).format('YYYY-MM-DD')} 窗口` : record.scopeKey === 'missing_window' ? '缺少业务窗口' : '全表' },
                  ]}
                  expandable={{
                    expandRowByClick: false,
                    expandedRowRender: (record) => (
                      <div className="quality-run-detail">
                        <Descriptions
                          size="small"
                          column={{ xs: 1, sm: 2, lg: 4 }}
                          items={[
                            { key: 'batch', label: '完整批次 ID', children: <Typography.Text copyable>{record.batchId}</Typography.Text> },
                            { key: 'engine', label: '执行引擎', children: record.engine || '—' },
                            { key: 'version', label: '冻结规则版本', children: record.ruleVersion ?? '—' },
                            { key: 'window', label: '检测范围 [开始, 结束)', children: record.windowStart ? `${formatDateTime(record.windowStart)} 至 ${formatDateTime(record.windowEnd)}` : record.scopeKey === 'missing_window' ? '缺少业务窗口' : '全表' },
                            { key: 'empty', label: '空数据策略', children: record.emptyPolicy ? (record.emptyPolicy === 'allow' ? '按指标判断' : '不通过') : '历史记录未保存' },
                            { key: 'start', label: '开始时间', children: formatDateTime(record.startedAt) },
                            { key: 'finish', label: '完成时间', children: formatDateTime(record.finishedAt) },
                          ]}
                        />
                        <div className="quality-run-sql-title">检查 SQL</div>
                        <pre className="quality-sql-preview">{record.checkSql}</pre>
                        {record.errorMessage && (
                          <Alert type="error" showIcon message="未通过原因" description={record.errorMessage} />
                        )}
                      </div>
                    ),
                  }}
                  locale={{ emptyText: '暂无匹配的执行记录' }}
                  pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }}
                  scroll={{ x: 1300 }}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        title={editingRule ? '编辑质量规则' : '新建质量规则'}
        open={modalOpen}
        width={680}
        rootClassName="quality-rule-modal"
        centered
        okText={editingRule ? '保存修改' : '创建规则'}
        cancelText="取消"
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        destroyOnHidden
        footer={(_, { OkBtn, CancelBtn }) => <Space><Button loading={previewing} onClick={async () => {
          try {
            const values = await form.validateFields();
            setPreviewing(true);
            setPreview(await previewQualityRule(values, businessDate));
          } catch (error: any) { if (!error.errorFields) message.error(error.message || '预览失败'); }
          finally { setPreviewing(false); }
        }}>预览检查 SQL</Button><CancelBtn /><OkBtn /></Space>}
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={ruleFormInitialValues}
          clearOnDestroy
          onValuesChange={() => setPreview(undefined)}
          onFinish={saveRule}
        >
          <Form.Item
            name="ruleName"
            label="规则名称"
            rules={[{ required: true, whitespace: true, message: '请输入规则名称' }, { max: 100, message: '规则名称不能超过 100 个字符' }]}
          >
            <Input maxLength={100} placeholder="例如：订单 ID 唯一性" />
          </Form.Item>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item name="layer" label="数仓分层" rules={[{ required: true, message: '请选择数仓分层' }]}>
                <Select options={layers.map((value) => ({ label: value.toUpperCase(), value }))} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="ruleType" label="规则类型" rules={[{ required: true, message: '请选择规则类型' }]}>
                <Select
                  options={Object.entries(ruleTypeLabel).map(([value, label]) => ({ value, label }))}
                  onChange={() => form.setFieldValue('threshold', undefined)}
                />
              </Form.Item>
            </Col>
          </Row>
          <div className="quality-rule-logic-hint">
            <InfoCircleOutlined />
            <span>
              <b>{ruleTypeLabel[selectedRuleType]}</b>
              {ruleLogicConfig[selectedRuleType]?.hint}
            </span>
          </div>
          <Form.Item
            name="targetTable"
            label="目标表"
            rules={[
              { required: true, whitespace: true, message: '请输入目标表' },
              { max: 100, message: '目标表不能超过 100 个字符' },
              { pattern: /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*){0,2}$/, message: '请输入 table、database.table 或 catalog.database.table' },
            ]}
          >
            <Input maxLength={100} placeholder="例如：ods_orders" />
          </Form.Item>
          {selectedRuleType !== 'volume_compare' && (
            <Form.Item
              name="targetColumn"
              label="目标字段"
              rules={[
                { required: true, whitespace: true, message: '请输入目标字段' },
                { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: '目标字段格式不正确' },
              ]}
            >
              <Input maxLength={100} placeholder="例如：order_id" />
            </Form.Item>
          )}
          <Form.Item
            name="threshold"
            label={ruleLogicConfig[selectedRuleType]?.thresholdLabel || '阈值'}
            extra={selectedRuleType === 'volume_compare' ? '填写非负整数，例如 1000' : '填写 0～1 的小数，例如 0.05 表示 5%'}
            rules={[
              { required: true, message: '请输入阈值' },
              {
                validator: (_, value?: number) => {
                  if (value == null) return Promise.resolve();
                  if (selectedRuleType === 'volume_compare') {
                    return value >= 0 && Number.isInteger(value)
                      ? Promise.resolve()
                      : Promise.reject(new Error('数据量下限必须是非负整数'));
                  }
                  return value >= 0 && value <= 1
                    ? Promise.resolve()
                    : Promise.reject(new Error('比率阈值必须在 0 到 1 之间'));
                },
              },
            ]}
          >
            <InputNumber
              min={0}
              max={selectedRuleType === 'volume_compare' ? undefined : 1}
              precision={selectedRuleType === 'volume_compare' ? 0 : 4}
              style={{ width: '100%' }}
              placeholder={selectedRuleType === 'volume_compare' ? '例如：1000' : '例如：0.05'}
            />
          </Form.Item>
          {selectedRuleType === 'range_check' && (
            <Form.Item
              name="expression"
              label="有效数据表达式"
              extra="表达式成立的数据视为有效；不成立或结果为 NULL 的数据计入越界率。"
              rules={[
                { required: true, whitespace: true, message: '请输入范围检查表达式' },
                { max: 500, message: '检查表达式不能超过 500 个字符' },
              ]}
            >
              <Input.TextArea rows={3} maxLength={500} showCount placeholder="例如：amount >= 0 AND amount <= 100000" />
            </Form.Item>
          )}
          <Row gutter={12}>
            <Col xs={24} sm={12}><Form.Item name="checkScope" label="检测范围" rules={[{ required: true }]}>
              <Select options={[{ value: 'full_table', label: '全表检测' }, { value: 'business_window', label: '按业务日期窗口检测' }]} />
            </Form.Item></Col>
            <Col xs={24} sm={12}><Form.Item name="emptyPolicy" label="范围内无数据时" rules={[{ required: true }]}>
              <Select options={[{ value: 'fail', label: '不通过（默认）' }, { value: 'allow', label: '允许空数据，仍按指标阈值判断' }]} />
            </Form.Item></Col>
          </Row>
          {selectedScope === 'business_window' && <Form.Item name="timeColumn" label="业务时间字段" extra={`使用 DATE / DATETIME 字段，预览与手动检测窗口为 ${businessDate}，不做时区转换。产出检测使用任务实例窗口。`}
            rules={[{ required: true, message: '请输入业务时间字段' }, { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: '字段名格式不正确' }]}>
            <Input placeholder="例如：business_date 或 event_time" maxLength={100} />
          </Form.Item>}
          <Form.Item name="enabled" label="立即启用" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
        {preview && <Alert type="info" message="检查 SQL 预览（未执行检测、未保存规则）" description={<pre className="quality-sql-preview">{preview.checkSql}</pre>} />}
      </Modal>
    </PageContainer>
  );
};

export default Quality;
