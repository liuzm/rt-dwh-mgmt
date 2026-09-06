import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Skeleton,
  Space,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import {
  BarChartOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloudDownloadOutlined,
  EditOutlined,
  EyeOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useAccess, useRequest } from '@umijs/max';
import {
  createReport,
  deleteReport,
  getReportData,
  getReportRunResult,
  getReportRuns,
  getReports,
  runReportNow,
  updateReport,
} from '@/api';
import './index.less';

type ReportType = API.ReportTemplate['reportType'];

interface ReportFormValues {
  reportName: string;
  reportType: ReportType;
  sqlQuery: string;
  filterConfig?: string;
  scheduleEnabled?: boolean;
  scheduleCron?: string;
  scheduleTimezone?: string;
  retainCount?: number;
  maxRows?: number;
  maxRetries?: number;
  notifyOn?: 'never' | 'success' | 'failure' | 'always';
  notifyChannels?: string[];
  recipients?: string;
  scheduleParameters?: string;
}

interface ReportDataState {
  data?: API.QueryResult;
  error?: string;
  loading: boolean;
  updatedAt?: number;
}

const chartTypeColor: Record<ReportType, string> = {
  line: 'blue',
  bar: 'green',
  pie: 'gold',
  table: 'default',
  mixed: 'magenta',
};

const chartTypeLabel: Record<ReportType, string> = {
  line: '趋势图',
  bar: '柱状图',
  pie: '占比图',
  table: '明细表',
  mixed: '混合图',
};

const chartTypeOptions = (Object.keys(chartTypeLabel) as ReportType[]).map((value) => ({
  label: chartTypeLabel[value],
  value,
}));

const palette = ['#1677ff', '#52c41a', '#faad14', '#ff4d4f', '#722ed1', '#13c2c2', '#eb2f96', '#2f54eb'];

const parameterTemplate = JSON.stringify({
  parameters: [
    { name: 'start_date', label: '开始日期', type: 'date', required: true, defaultValue: '2026-08-01' },
    { name: 'region', label: '区域', type: 'string', required: false, defaultValue: '华东' },
  ],
}, null, 2);

const parseParameterDefinitions = (config?: string): API.ReportParameterDefinition[] => {
  if (!config?.trim()) return [];
  try {
    const parsed = JSON.parse(config);
    const parameters = Array.isArray(parsed) ? parsed : parsed.parameters;
    return Array.isArray(parameters) ? parameters : [];
  } catch {
    return [];
  }
};

const defaultParameterValues = (definitions: API.ReportParameterDefinition[]) => Object.fromEntries(
  definitions.filter((item) => item.defaultValue !== undefined && item.defaultValue !== null)
    .map((item) => [item.name, item.defaultValue]),
);

const dateValue = (value?: string) => {
  if (!value) return 0;
  const timestamp = new Date(value).getTime();
  return Number.isNaN(timestamp) ? 0 : timestamp;
};

const formatTime = (value?: string | number) => {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
};

const numericValue = (value: unknown): number | undefined => {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value !== 'string' || !value.trim()) return undefined;
  const parsed = Number(value.replace(/,/g, ''));
  return Number.isFinite(parsed) ? parsed : undefined;
};

const formatNumber = (value: number) => new Intl.NumberFormat('zh-CN', {
  notation: Math.abs(value) >= 100000 ? 'compact' : 'standard',
  maximumFractionDigits: 2,
}).format(value);

const escapeCsvCell = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`;

const exportResult = (name: string, result?: API.QueryResult) => {
  if (!result?.columns?.length) {
    message.warning('当前报表没有可导出的数据');
    return;
  }
  const csv = [
    result.columns.map(escapeCsvCell).join(','),
    ...(result.rows || []).map((row) => row.map(escapeCsvCell).join(',')),
  ].join('\n');
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${name.replace(/[\\/:*?"<>|]/g, '_') || 'report'}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
};

const ResultTable: React.FC<{ result: API.QueryResult; compact?: boolean }> = ({ result, compact }) => {
  const rows = (result.rows || []).slice(0, compact ? 8 : 100);
  const columns = (result.columns || []).map((column, index) => ({
    title: column,
    key: `${column}-${index}`,
    dataIndex: `column_${index}`,
    ellipsis: true,
    width: compact ? 140 : undefined,
  }));
  const dataSource = rows.map((row, rowIndex) => ({
    key: rowIndex,
    ...Object.fromEntries(row.map((value, columnIndex) => [`column_${columnIndex}`, value == null ? '—' : String(value)])),
  }));

  return (
    <Table
      size="small"
      columns={columns}
      dataSource={dataSource}
      pagination={compact ? false : { pageSize: 20, showSizeChanger: false }}
      scroll={{ x: 'max-content' }}
      locale={{ emptyText: '查询成功，但没有返回数据' }}
    />
  );
};

const ReportChart: React.FC<{ result: API.QueryResult; type: ReportType }> = ({ result, type }) => {
  const chart = useMemo(() => {
    const rows = (result.rows || []).slice(0, 16);
    const columns = result.columns || [];
    const dimensionIndex = columns.length > 1 ? 0 : -1;
    const metricIndexes = columns
      .map((_, index) => index)
      .filter((index) => index !== dimensionIndex && rows.some((row) => numericValue(row[index]) !== undefined))
      .slice(0, 2);
    const labels = rows.map((row, index) => {
      const raw = dimensionIndex >= 0 ? row[dimensionIndex] : index + 1;
      const label = String(raw ?? `#${index + 1}`);
      return label.length > 12 ? `${label.slice(0, 11)}…` : label;
    });
    const series = metricIndexes.map((columnIndex, seriesIndex) => ({
      name: columns[columnIndex] || `指标 ${seriesIndex + 1}`,
      color: palette[seriesIndex],
      values: rows.map((row) => numericValue(row[columnIndex]) ?? 0),
    }));
    return { labels, series };
  }, [result]);

  if (type === 'table' || chart.series.length === 0) {
    return <ResultTable result={result} compact />;
  }

  if (type === 'pie') {
    const values = chart.series[0].values.slice(0, 8).map((value) => Math.max(0, value));
    const total = values.reduce((sum, value) => sum + value, 0);
    if (total <= 0) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可用于占比计算的正数值" />;
    let offset = 0;
    return (
      <div className="report-pie-layout">
        <svg className="report-pie" viewBox="0 0 220 220" role="img" aria-label={`${chart.series[0].name}占比图`}>
          <circle cx="110" cy="110" r="70" fill="none" stroke="#f0f2f5" strokeWidth="32" />
          {values.map((value, index) => {
            const percentage = value / total;
            const dash = percentage * 439.82;
            const currentOffset = offset;
            offset += dash;
            return (
              <circle
                key={`${chart.labels[index]}-${index}`}
                cx="110"
                cy="110"
                r="70"
                fill="none"
                stroke={palette[index % palette.length]}
                strokeWidth="32"
                strokeDasharray={`${dash} ${439.82 - dash}`}
                strokeDashoffset={-currentOffset}
                transform="rotate(-90 110 110)"
              >
                <title>{`${chart.labels[index]}：${formatNumber(value)}（${(percentage * 100).toFixed(1)}%）`}</title>
              </circle>
            );
          })}
          <text x="110" y="105" textAnchor="middle" className="report-pie-total">{formatNumber(total)}</text>
          <text x="110" y="128" textAnchor="middle" className="report-pie-caption">总计</text>
        </svg>
        <div className="report-chart-legend report-chart-legend-vertical">
          {values.map((value, index) => (
            <div key={`${chart.labels[index]}-legend`} className="report-chart-legend-item">
              <i style={{ background: palette[index % palette.length] }} />
              <span title={chart.labels[index]}>{chart.labels[index]}</span>
              <strong>{(value / total * 100).toFixed(1)}%</strong>
            </div>
          ))}
        </div>
      </div>
    );
  }

  const width = 720;
  const height = 270;
  const padding = { top: 24, right: 24, bottom: 54, left: 58 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;
  const showBars = type === 'bar' || type === 'mixed';
  const showLines = type === 'line' || type === 'mixed';
  const barSeries = chart.series[0];
  const lineSeries = type === 'mixed' && chart.series[1] ? chart.series[1] : chart.series[0];
  const primaryValues = barSeries.values;
  const maximum = Math.max(...primaryValues, 0);
  const minimum = Math.min(...primaryValues, 0);
  const range = maximum - minimum || 1;
  const secondaryMaximum = Math.max(...lineSeries.values, 0);
  const secondaryMinimum = Math.min(...lineSeries.values, 0);
  const secondaryRange = secondaryMaximum - secondaryMinimum || 1;
  const xAt = (index: number) => padding.left + (chart.labels.length <= 1 ? plotWidth / 2 : index * plotWidth / (chart.labels.length - 1));
  const yAt = (value: number) => padding.top + (maximum - value) / range * plotHeight;
  const lineYAt = (value: number) => type === 'mixed'
    ? padding.top + (secondaryMaximum - value) / secondaryRange * plotHeight
    : yAt(value);
  const barSlot = plotWidth / Math.max(chart.labels.length, 1);
  const barWidth = Math.min(34, barSlot * 0.58);

  return (
    <div className="report-chart-wrap">
      <svg className="report-cartesian-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${chart.series.map((item) => item.name).join('、')}图表`}>
        {[0, 1, 2, 3, 4].map((step) => {
          const y = padding.top + step * plotHeight / 4;
          const value = maximum - step * range / 4;
          return (
            <g key={step}>
              <line x1={padding.left} y1={y} x2={width - padding.right} y2={y} className="report-grid-line" />
              <text x={padding.left - 10} y={y + 4} textAnchor="end" className="report-axis-label">{formatNumber(value)}</text>
              {type === 'mixed' && (
                <text x={width - padding.right + 10} y={y + 4} textAnchor="start" className="report-axis-label">
                  {formatNumber(secondaryMaximum - step * secondaryRange / 4)}
                </text>
              )}
            </g>
          );
        })}
        {showBars && barSeries.values.map((value, index) => {
          const y = yAt(value);
          const zeroY = yAt(0);
          const rectY = Math.min(y, zeroY);
          const rectHeight = Math.max(2, Math.abs(zeroY - y));
          return (
            <rect
              key={`bar-${index}`}
              x={xAt(index) - barWidth / 2}
              y={rectY}
              width={barWidth}
              height={rectHeight}
              rx="4"
              fill={barSeries.color}
              opacity={type === 'mixed' ? 0.38 : 0.82}
            >
              <title>{`${chart.labels[index]} · ${barSeries.name}：${formatNumber(value)}`}</title>
            </rect>
          );
        })}
        {showLines && (
          <>
            <polyline
              points={lineSeries.values.map((value, index) => `${xAt(index)},${lineYAt(value)}`).join(' ')}
              fill="none"
              stroke={type === 'mixed' ? palette[1] : lineSeries.color}
              strokeWidth="3"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
            {lineSeries.values.map((value, index) => (
              <circle key={`point-${index}`} cx={xAt(index)} cy={lineYAt(value)} r="4" fill="#fff" stroke={type === 'mixed' ? palette[1] : lineSeries.color} strokeWidth="2">
                <title>{`${chart.labels[index]} · ${lineSeries.name}：${formatNumber(value)}`}</title>
              </circle>
            ))}
          </>
        )}
        {chart.labels.map((label, index) => (
          <text key={`${label}-${index}`} x={xAt(index)} y={height - 24} textAnchor="middle" className="report-axis-label">
            {chart.labels.length > 9 && index % 2 === 1 ? '' : label}
          </text>
        ))}
      </svg>
      <div className="report-chart-legend">
        {(type === 'mixed' ? chart.series : [chart.series[0]]).map((item, index) => (
          <span key={item.name} className="report-chart-legend-item">
            <i style={{ background: type === 'mixed' && index === 1 ? palette[1] : item.color }} />
            {item.name}
          </span>
        ))}
      </div>
    </div>
  );
};

const ReportVisualization: React.FC<{ result?: API.QueryResult; type: ReportType }> = ({ result, type }) => {
  if (!result) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无查询结果" />;
  if (result.status && result.status !== 'success') {
    return <Alert type="error" showIcon message="查询执行失败" description={result.errorMsg || '请检查报表 SQL 和 Doris 查询服务'} />;
  }
  if (!result.rows?.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="查询成功，但没有返回数据" />;
  return <ReportChart result={result} type={type} />;
};

const Report: React.FC = () => {
  const access = useAccess();
  const [activeTab, setActiveTab] = useState('dashboard');
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingReport, setEditingReport] = useState<API.ReportTemplate>();
  const [submitting, setSubmitting] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [typeFilter, setTypeFilter] = useState<ReportType>();
  const [statusFilter, setStatusFilter] = useState<'published' | 'draft'>();
  const [reportStates, setReportStates] = useState<Record<number, ReportDataState>>({});
  const [lastRefreshAt, setLastRefreshAt] = useState<number>();
  const [viewReport, setViewReport] = useState<API.ReportTemplate>();
  const [viewData, setViewData] = useState<API.QueryResult>();
  const [viewLoading, setViewLoading] = useState(false);
  const [viewParameters, setViewParameters] = useState<API.ReportParameterDefinition[]>([]);
  const [runsReport, setRunsReport] = useState<API.ReportTemplate>();
  const [reportRuns, setReportRuns] = useState<API.ReportRun[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);
  const autoLoadedKeyRef = useRef('');
  const [form] = Form.useForm<ReportFormValues>();
  const [parameterForm] = Form.useForm<Record<string, any>>();

  const { data: reportsData, loading, refresh } = useRequest(getReports);
  const reports = (reportsData || []) as API.ReportTemplate[];
  const publishedReports = useMemo(() => reports.filter((report) => report.isPublished), [reports]);
  const featuredReports = useMemo(
    () => [...publishedReports]
      .sort((left, right) => dateValue(right.updatedAt || right.createdAt) - dateValue(left.updatedAt || left.createdAt))
      .slice(0, 6),
    [publishedReports],
  );
  const featuredKey = featuredReports.map((report) => `${report.id}:${report.updatedAt || report.createdAt}`).join('|');

  const filteredReports = useMemo(() => reports.filter((report) => {
    const search = keyword.trim().toLowerCase();
    const matchKeyword = !search || report.reportName.toLowerCase().includes(search) || report.sqlQuery.toLowerCase().includes(search);
    const matchType = !typeFilter || report.reportType === typeFilter;
    const matchStatus = !statusFilter || (statusFilter === 'published' ? report.isPublished : !report.isPublished);
    return matchKeyword && matchType && matchStatus;
  }), [keyword, reports, statusFilter, typeFilter]);

  const loadReport = useCallback(async (report: API.ReportTemplate) => {
    setReportStates((current) => ({
      ...current,
      [report.id]: { ...current[report.id], error: undefined, loading: true },
    }));
    try {
      const data = await getReportData(report.id);
      setReportStates((current) => ({
        ...current,
        [report.id]: { data, loading: false, updatedAt: Date.now() },
      }));
      return data;
    } catch (error: any) {
      const errorMessage = error?.message || '报表查询失败';
      setReportStates((current) => ({
        ...current,
        [report.id]: { error: errorMessage, loading: false, updatedAt: Date.now() },
      }));
      return undefined;
    }
  }, []);

  const loadDashboard = useCallback(async (targets: API.ReportTemplate[]) => {
    let cursor = 0;
    const worker = async () => {
      while (cursor < targets.length) {
        const report = targets[cursor];
        cursor += 1;
        await loadReport(report);
      }
    };
    await Promise.all(Array.from({ length: Math.min(2, targets.length) }, worker));
    setLastRefreshAt(Date.now());
  }, [loadReport]);

  useEffect(() => {
    if (activeTab === 'dashboard' && featuredReports.length > 0 && autoLoadedKeyRef.current !== featuredKey) {
      autoLoadedKeyRef.current = featuredKey;
      void loadDashboard(featuredReports);
    }
  // featuredKey intentionally represents the stable report selection and update version.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, featuredKey, loadDashboard]);

  const openCreate = () => {
    setEditingReport(undefined);
    form.resetFields();
    form.setFieldsValue({ reportType: 'line', scheduleEnabled: false, scheduleCron: '0 0 * * * *', scheduleTimezone: 'Asia/Shanghai', retainCount: 30, maxRows: 1000, maxRetries: 0, notifyOn: 'never', notifyChannels: [], scheduleParameters: '{}' });
    setEditorOpen(true);
  };

  const openEdit = (report: API.ReportTemplate) => {
    setEditingReport(report);
    let schedule: any = {};
    let filterConfig: string | undefined;
    try { schedule = report.scheduleConfig ? JSON.parse(report.scheduleConfig) : {}; } catch { schedule = {}; }
    try { filterConfig = report.filterConfig ? JSON.stringify(JSON.parse(report.filterConfig), null, 2) : undefined; } catch { filterConfig = report.filterConfig; }
    form.setFieldsValue({
      reportName: report.reportName,
      reportType: report.reportType,
      sqlQuery: report.sqlQuery,
      filterConfig,
      scheduleEnabled: schedule.enabled || false,
      scheduleCron: schedule.cron || '0 0 * * * *',
      scheduleTimezone: schedule.timezone || 'Asia/Shanghai',
      retainCount: schedule.retainCount || 30,
      maxRows: schedule.maxRows || 1000,
      maxRetries: schedule.maxRetries || 0,
      notifyOn: schedule.notifyOn || 'never',
      notifyChannels: schedule.notifyChannels || [],
      recipients: schedule.recipients || '',
      scheduleParameters: JSON.stringify(schedule.parameters || {}, null, 2),
    });
    setEditorOpen(true);
  };

  const saveReport = async () => {
    try {
      const values = await form.validateFields();
      const { scheduleEnabled, scheduleCron, scheduleTimezone, retainCount, maxRows,
        maxRetries, notifyOn, notifyChannels, recipients, scheduleParameters, filterConfig, ...reportValues } = values;
      const parsedFilterConfig = filterConfig?.trim() ? JSON.parse(filterConfig) : undefined;
      const parsedScheduleParameters = scheduleParameters?.trim() ? JSON.parse(scheduleParameters) : {};
      if (parsedScheduleParameters && (Array.isArray(parsedScheduleParameters) || typeof parsedScheduleParameters !== 'object')) {
        throw new Error('定时参数必须是 JSON 对象');
      }
      const scheduleConfig = JSON.stringify({
        enabled: !!scheduleEnabled,
        cron: scheduleCron || '0 0 * * * *',
        timezone: scheduleTimezone || 'Asia/Shanghai',
        retainCount: retainCount || 30,
        maxRows: maxRows || 1000,
        maxRetries: maxRetries || 0,
        notifyOn: notifyOn || 'never',
        notifyChannels: notifyChannels || [],
        recipients: recipients || '',
        parameters: parsedScheduleParameters,
      });
      const normalizedFilterConfig = parsedFilterConfig ? JSON.stringify(parsedFilterConfig) : undefined;
      setSubmitting(true);
      if (editingReport) {
        await updateReport(editingReport.id, { ...editingReport, ...reportValues, filterConfig: normalizedFilterConfig, scheduleConfig });
        message.success('报表已更新');
      } else {
        await createReport({ ...reportValues, filterConfig: normalizedFilterConfig, scheduleConfig, isPublished: false });
        message.success('报表已创建，可发布后加入看板');
      }
      setEditorOpen(false);
      await refresh();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.message || '保存报表失败');
    } finally {
      setSubmitting(false);
    }
  };

  const openRuns = async (report: API.ReportTemplate) => {
    setRunsReport(report);
    setRunsLoading(true);
    try { setReportRuns(await getReportRuns(report.id)); } finally { setRunsLoading(false); }
  };

  const runNow = async (report: API.ReportTemplate) => {
    const run = await runReportNow(report.id);
    if (run.status === 'success') message.success('报表执行成功');
    else message.error(run.errorMessage || '报表执行失败');
    await refresh();
    if (runsReport?.id === report.id) await openRuns(report);
  };

  const togglePublish = async (report: API.ReportTemplate) => {
    try {
      await updateReport(report.id, { ...report, isPublished: !report.isPublished });
      message.success(report.isPublished ? '报表已下线' : '报表已发布到看板');
      await refresh();
    } catch (error: any) {
      message.error(error?.message || '更新发布状态失败');
    }
  };

  const removeReport = async (report: API.ReportTemplate) => {
    try {
      await deleteReport(report.id);
      setReportStates((current) => {
        const next = { ...current };
        delete next[report.id];
        return next;
      });
      message.success('报表已删除');
      await refresh();
    } catch (error: any) {
      message.error(error?.message || '删除报表失败');
    }
  };

  const loadPreviewData = async (report: API.ReportTemplate, parameters: Record<string, unknown>) => {
    setViewLoading(true);
    try {
      const data = await getReportData(report.id, parameters);
      setViewData(data);
      if (!parseParameterDefinitions(report.filterConfig).length) {
        setReportStates((current) => ({
          ...current,
          [report.id]: { data, loading: false, updatedAt: Date.now() },
        }));
      }
    } catch (error: any) {
      setViewData({ columns: [], rows: [], status: 'failed', errorMsg: error?.message || '报表查询失败' });
    } finally {
      setViewLoading(false);
    }
  };

  const openPreview = async (report: API.ReportTemplate) => {
    const definitions = parseParameterDefinitions(report.filterConfig);
    const defaults = defaultParameterValues(definitions);
    setViewReport(report);
    setViewParameters(definitions);
    setViewData(undefined);
    parameterForm.resetFields();
    parameterForm.setFieldsValue(defaults);
    const cached = definitions.length ? undefined : reportStates[report.id]?.data;
    if (cached) {
      setViewData(cached);
      setViewLoading(false);
      return;
    }
    const missingRequired = definitions.some((item) => item.required
      && (item.defaultValue === undefined || item.defaultValue === null || item.defaultValue === ''));
    if (missingRequired) {
      setViewLoading(false);
      return;
    }
    await loadPreviewData(report, defaults);
  };

  const closePreview = () => {
    setViewReport(undefined);
    setViewData(undefined);
    setViewParameters([]);
    parameterForm.resetFields();
  };

  const refreshDashboard = async () => {
    await refresh();
    if (featuredReports.length) await loadDashboard(featuredReports);
    else setLastRefreshAt(Date.now());
  };

  const loadedRowCount = Object.values(reportStates)
    .reduce((total, state) => total + (state.data?.rowCount ?? state.data?.rows?.length ?? 0), 0);

  const metricCard = (
    title: string,
    value: number,
    color: string,
    background: string,
    icon: React.ReactNode,
    suffix = '个',
  ) => (
    <Card className="report-metric-card">
      <span className="report-metric-icon" style={{ color, background }}>{icon}</span>
      <Statistic title={title} value={value} suffix={suffix} valueStyle={{ color }} />
    </Card>
  );

  return (
    <PageContainer
      className="report-page"
      title="报表看板"
      subTitle="通过 Doris 实时查询 Paimon 数据，统一管理指标报表与可视化看板"
      extra={[
        <Tooltip title="刷新报表清单和看板数据" key="refresh">
          <Button icon={<ReloadOutlined />} loading={loading} onClick={refreshDashboard}>刷新数据</Button>
        </Tooltip>,
        access.canManageReport ? <Button key="create" type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建报表</Button> : null,
      ]}
    >
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'dashboard',
            label: `数据看板（${featuredReports.length}）`,
            children: (
              <>
                <div className="report-dashboard-note">
                  <div>
                    <strong>实时看板</strong>
                    <span>展示最近更新的已发布报表，查询由 Doris 执行，单次最多并发加载 2 个组件。</span>
                  </div>
                  <span><ClockCircleOutlined /> 最近刷新：{lastRefreshAt ? formatTime(lastRefreshAt) : '等待加载'}</span>
                </div>

                <Row gutter={[16, 16]} className="report-metric-row">
                  <Col xs={24} sm={12} xl={6}>{metricCard('报表总数', reports.length, '#1677ff', '#e6f4ff', <FileTextOutlined />)}</Col>
                  <Col xs={24} sm={12} xl={6}>{metricCard('已发布', publishedReports.length, '#52c41a', '#f6ffed', <CheckCircleOutlined />)}</Col>
                  <Col xs={24} sm={12} xl={6}>{metricCard('待发布草稿', reports.length - publishedReports.length, '#faad14', '#fffbe6', <EditOutlined />)}</Col>
                  <Col xs={24} sm={12} xl={6}>{metricCard('已加载数据行', loadedRowCount, '#722ed1', '#f9f0ff', <BarChartOutlined />, '行')}</Col>
                </Row>

                {!loading && featuredReports.length === 0 ? (
                  <Card className="report-empty-card">
                    <Empty description="暂无已发布报表">
                      <Space>
                        {access.canManageReport && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建第一张报表</Button>}
                        {reports.length > 0 && <Button onClick={() => setActiveTab('list')}>前往发布草稿</Button>}
                      </Space>
                    </Empty>
                  </Card>
                ) : (
                  <div className="report-dashboard-grid">
                    {featuredReports.map((report) => {
                      const state = reportStates[report.id] || { loading: true };
                      return (
                        <Card
                          key={report.id}
                          className="report-visual-card"
                          title={<div className="report-card-title"><span>{report.reportName}</span><Tag color={chartTypeColor[report.reportType]}>{chartTypeLabel[report.reportType]}</Tag></div>}
                          extra={(
                            <Space size={4}>
                              <Tooltip title="重新查询"><Button size="small" type="text" icon={<ReloadOutlined />} loading={state.loading} onClick={() => loadReport(report)} /></Tooltip>
                              <Tooltip title="查看详情"><Button size="small" type="text" icon={<EyeOutlined />} onClick={() => openPreview(report)} /></Tooltip>
                              <Tooltip title="导出当前数据"><Button size="small" type="text" icon={<CloudDownloadOutlined />} disabled={!state.data} onClick={() => exportResult(report.reportName, state.data)} /></Tooltip>
                            </Space>
                          )}
                        >
                          <div className="report-visual-body">
                            {state.loading ? (
                              <Skeleton active paragraph={{ rows: 6 }} />
                            ) : state.error ? (
                              <Alert type="error" showIcon message="组件加载失败" description={state.error} action={<Button size="small" onClick={() => loadReport(report)}>重试</Button>} />
                            ) : (
                              <ReportVisualization result={state.data} type={report.reportType} />
                            )}
                          </div>
                          <div className="report-card-footer">
                            <span>{state.data?.rowCount ?? state.data?.rows?.length ?? 0} 行数据</span>
                            <span>耗时 {state.data?.durationMs ?? '—'} ms</span>
                            <span>{state.updatedAt ? formatTime(state.updatedAt) : '—'}</span>
                          </div>
                        </Card>
                      );
                    })}
                  </div>
                )}

                {publishedReports.length > featuredReports.length && (
                  <div className="report-more-tip">
                    当前展示最近更新的 {featuredReports.length} 张报表，另有 {publishedReports.length - featuredReports.length} 张可在报表管理中查看。
                    <Button type="link" onClick={() => setActiveTab('list')}>查看全部</Button>
                  </div>
                )}
              </>
            ),
          },
          {
            key: 'list',
            label: `报表管理（${reports.length}）`,
            children: (
              <Card>
                <div className="report-list-toolbar">
                  <Input.Search allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索报表名称或 SQL" className="report-list-search" />
                  <Select allowClear value={typeFilter} onChange={setTypeFilter} placeholder="图表类型" options={chartTypeOptions} className="report-list-filter" />
                  <Select allowClear value={statusFilter} onChange={setStatusFilter} placeholder="发布状态" options={[{ label: '已发布', value: 'published' }, { label: '草稿', value: 'draft' }]} className="report-list-filter" />
                  <Button icon={<ReloadOutlined />} onClick={() => refresh()}>刷新</Button>
                  <span className="report-list-count">共 {filteredReports.length} 张报表</span>
                </div>

                <Table<API.ReportTemplate>
                  dataSource={filteredReports}
                  rowKey="id"
                  loading={loading}
                  scroll={{ x: 1080 }}
                  pagination={{ pageSize: 10, showSizeChanger: false }}
                  columns={[
                    {
                      title: '报表名称', dataIndex: 'reportName', key: 'name', width: 210,
                      render: (value: string, record) => <div className="report-name-cell"><strong>{value}</strong><span>更新于 {formatTime(record.updatedAt || record.createdAt)}</span></div>,
                    },
                    {
                      title: '图表类型', dataIndex: 'reportType', key: 'type', width: 110,
                      render: (value: ReportType) => <Tag color={chartTypeColor[value]}>{chartTypeLabel[value]}</Tag>,
                    },
                    {
                      title: '查询 SQL', dataIndex: 'sqlQuery', key: 'sql', ellipsis: true,
                      render: (value: string) => <Typography.Text code title={value}>{value}</Typography.Text>,
                    },
                    {
                      title: '状态', dataIndex: 'isPublished', key: 'published', width: 100,
                      render: (value: boolean) => value ? <Tag color="success">已发布</Tag> : <Tag>草稿</Tag>,
                    },
                    {
                      title: '调度', key: 'schedule', width: 170,
                      render: (_, record) => record.scheduleEnabled
                        ? <div><Tag color="processing">已启用</Tag><div style={{ color: '#8c8c8c', marginTop: 4 }}>下次 {formatTime(record.nextRunAt)}</div></div>
                        : <Tag>未启用</Tag>,
                    },
                    { title: '创建时间', dataIndex: 'createdAt', key: 'created', width: 150, render: formatTime },
                    {
                      title: '操作', key: 'action', width: 360, fixed: 'right',
                      render: (_, record) => (
                        <Space size={4}>
                          <Tooltip title={record.isPublished ? '查看报表数据' : '发布后才能查询数据'}>
                            <Button size="small" type="link" disabled={!record.isPublished} onClick={() => openPreview(record)}>查看</Button>
                          </Tooltip>
                          <Button size="small" type="link" onClick={() => openRuns(record)}>运行历史</Button>
                          {access.canManageReport && <><Button size="small" type="link" onClick={() => openEdit(record)}>编辑</Button>
                            <Button size="small" type="link" disabled={!record.isPublished} onClick={() => runNow(record)}>立即运行</Button>
                            <Popconfirm title={record.isPublished ? '确认下线这张报表？' : '确认发布这张报表？'} description={record.isPublished ? '下线后将从数据看板移除。' : '发布后会立即加入数据看板并执行查询。'} onConfirm={() => togglePublish(record)}>
                              <Button size="small" type="link">{record.isPublished ? '下线' : '发布'}</Button>
                            </Popconfirm>
                            <Popconfirm title="确认删除这张报表？" description="删除后无法恢复。" okButtonProps={{ danger: true }} onConfirm={() => removeReport(record)}>
                              <Button size="small" type="link" danger>删除</Button>
                            </Popconfirm></>}
                        </Space>
                      ),
                    },
                  ]}
                />
              </Card>
            ),
          },
        ]}
      />

      <Modal
        title={editingReport ? '编辑报表' : '新建报表'}
        open={editorOpen}
        onCancel={() => setEditorOpen(false)}
        onOk={saveReport}
        confirmLoading={submitting}
        okText={editingReport ? '保存修改' : '创建报表'}
        cancelText="取消"
        width={720}
        forceRender
        rootClassName="report-editor-modal"
      >
        <Alert type="info" showIcon message="报表查询通过 Doris 执行" description="建议第一列返回时间或分类维度，其余列返回数值指标；仅支持 SELECT、WITH、SHOW、DESCRIBE 和 EXPLAIN 等只读语句。" className="report-editor-tip" />
        <Form form={form} layout="vertical" requiredMark="optional">
          <Form.Item name="reportName" label="报表名称" rules={[{ required: true, message: '请输入报表名称' }, { max: 128, message: '报表名称不能超过 128 个字符' }]}>
            <Input placeholder="例如：近 30 天订单趋势" />
          </Form.Item>
          <Form.Item name="reportType" label="展示方式" rules={[{ required: true, message: '请选择展示方式' }]}>
            <Select options={chartTypeOptions.map((option) => ({
              ...option,
              label: `${option.label} · ${option.value === 'line' ? '适合时间趋势' : option.value === 'bar' ? '适合分类对比' : option.value === 'pie' ? '适合结构占比' : option.value === 'table' ? '适合明细数据' : '适合双指标对比'}`,
            }))} />
          </Form.Item>
          <Form.Item
            name="sqlQuery"
            label="查询 SQL"
            extra="结果默认受服务端最大行数和查询超时限制。看板最多展示前 16 个维度，完整结果可在详情中查看或导出。"
            rules={[
              { required: true, message: '请输入查询 SQL' },
              {
                validator: async (_, value?: string) => {
                  if (!value?.trim()) return;
                  const normalized = value.trim().replace(/^\s*\/\*[\s\S]*?\*\//, '').trim();
                  if (!/^(select|with|show|describe|desc|explain)\b/i.test(normalized)) throw new Error('仅支持只读查询语句');
                },
              },
            ]}
          >
            <Input.TextArea className="report-sql-editor" autoSize={{ minRows: 8, maxRows: 16 }} spellCheck={false} placeholder={'SELECT\n  dt,\n  SUM(amount) AS total_amount\nFROM ads_order_daily\nGROUP BY dt\nORDER BY dt'} />
          </Form.Item>
          <Card
            size="small"
            title="查询参数（可选）"
            extra={<Button size="small" onClick={() => form.setFieldValue('filterConfig', parameterTemplate)}>一键代入模板</Button>}
          >
            <Alert
              type="info"
              showIcon
              message="SQL 使用 {{参数名}} 占位"
              description="参数只作为值写入，支持 string、number、boolean、date、datetime、stringList；不支持动态表名或字段名。"
              style={{ marginBottom: 12 }}
            />
            <Form.Item
              name="filterConfig"
              label="参数定义（JSON）"
              extra={'示例：WHERE dt >= {{start_date}} AND region = {{region}}'}
              rules={[{
                validator: async (_, value?: string) => {
                  if (!value?.trim()) return;
                  try {
                    const parsed = JSON.parse(value);
                    const parameters = Array.isArray(parsed) ? parsed : parsed.parameters;
                    if (!Array.isArray(parameters)) throw new Error();
                  } catch {
                    throw new Error('请输入参数数组，或包含 parameters 数组的合法 JSON');
                  }
                },
              }]}
            >
              <Input.TextArea autoSize={{ minRows: 4, maxRows: 12 }} spellCheck={false} placeholder={parameterTemplate} />
            </Form.Item>
          </Card>
          <Card size="small" title="定时运行" style={{ marginTop: 12 }}>
            <Form.Item name="scheduleEnabled" label="启用调度" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item noStyle shouldUpdate={(previous, current) => previous.scheduleEnabled !== current.scheduleEnabled}>
              {({ getFieldValue }) => getFieldValue('scheduleEnabled') && <>
                <Form.Item name="scheduleCron" label="Cron 表达式" extra="使用 Spring 六段 Cron：秒 分 时 日 月 周" rules={[{ required: true, message: '请输入 Cron 表达式' }]}>
                  <Input placeholder="0 0 * * * *（每小时）" />
                </Form.Item>
                <Row gutter={16}>
                  <Col span={12}><Form.Item name="scheduleTimezone" label="时区" rules={[{ required: true }]}>
                    <Select options={[{ value: 'Asia/Shanghai' }, { value: 'UTC' }, { value: 'Asia/Hong_Kong' }]} />
                  </Form.Item></Col>
                  <Col span={6}><Form.Item name="retainCount" label="保留次数"><InputNumber min={1} max={200} style={{ width: '100%' }} /></Form.Item></Col>
                  <Col span={6}><Form.Item name="maxRows" label="快照行数"><InputNumber min={1} max={5000} style={{ width: '100%' }} /></Form.Item></Col>
                </Row>
                <Row gutter={16}>
                  <Col span={8}><Form.Item name="maxRetries" label="失败重试"><InputNumber min={0} max={3} addonAfter="次" style={{ width: '100%' }} /></Form.Item></Col>
                  <Col span={8}><Form.Item name="notifyOn" label="通知时机">
                    <Select options={[{ label: '不通知', value: 'never' }, { label: '仅成功', value: 'success' }, { label: '仅失败', value: 'failure' }, { label: '始终通知', value: 'always' }]} />
                  </Form.Item></Col>
                  <Col span={8}><Form.Item name="notifyChannels" label="通知渠道">
                    <Select mode="multiple" options={[{ label: '邮件', value: 'email' }, { label: '钉钉', value: 'dingtalk' }, { label: '企业微信', value: 'wecom' }]} />
                  </Form.Item></Col>
                </Row>
                <Form.Item noStyle shouldUpdate={(previous, current) => previous.notifyChannels !== current.notifyChannels}>
                  {({ getFieldValue: fieldValue }) => (fieldValue('notifyChannels') || []).includes('email') && (
                    <Form.Item name="recipients" label="订阅邮箱" extra="多个邮箱使用逗号分隔；留空时使用平台默认告警收件人">
                      <Input placeholder="owner@example.com, data-team@example.com" />
                    </Form.Item>
                  )}
                </Form.Item>
                <Form.Item
                  name="scheduleParameters"
                  label="定时运行参数（JSON）"
                  extra="覆盖参数定义中的默认值；必须是键值对象，例如 {&quot;region&quot;:&quot;华东&quot;}"
                  rules={[{
                    validator: async (_, value?: string) => {
                      if (!value?.trim()) return;
                      try {
                        const parsed = JSON.parse(value);
                        if (Array.isArray(parsed) || parsed === null || typeof parsed !== 'object') throw new Error();
                      } catch {
                        throw new Error('请输入合法的 JSON 对象');
                      }
                    },
                  }]}
                >
                  <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} spellCheck={false} placeholder={'{"region":"华东"}'} />
                </Form.Item>
              </>}
            </Form.Item>
          </Card>
        </Form>
      </Modal>

      <Modal
        title={`运行历史 · ${runsReport?.reportName || ''}`}
        open={!!runsReport}
        onCancel={() => setRunsReport(undefined)}
        width={960}
        footer={<Button onClick={() => setRunsReport(undefined)}>关闭</Button>}
      >
        <Table<API.ReportRun>
          rowKey="id"
          loading={runsLoading}
          dataSource={reportRuns}
          pagination={{ pageSize: 10, showSizeChanger: false }}
          columns={[
            { title: '计划时间', dataIndex: 'scheduledAt', width: 170, render: formatTime },
            { title: '触发', dataIndex: 'triggerType', width: 90, render: (value) => value === 'scheduled' ? '定时' : '手动' },
            { title: '状态', dataIndex: 'status', width: 90, render: (value) => <Tag color={value === 'success' ? 'success' : value === 'failed' ? 'error' : 'processing'}>{value}</Tag> },
            { title: '行数', dataIndex: 'rowCount', width: 90, render: (value) => value ?? '—' },
            { title: '耗时', dataIndex: 'durationMs', width: 100, render: (value) => value == null ? '—' : `${value} ms` },
            { title: '尝试', dataIndex: 'attemptCount', width: 70, render: (value) => value ?? '—' },
            { title: '分发', dataIndex: 'deliveryStatus', width: 90, render: (value) => <Tag color={value === 'success' ? 'success' : value === 'failed' ? 'error' : value === 'partial' ? 'warning' : 'default'}>{value || '—'}</Tag> },
            { title: '错误', dataIndex: 'errorMessage', ellipsis: true },
            { title: '操作', width: 90, render: (_, run) => <Button size="small" type="link" disabled={run.status !== 'success'} onClick={async () => {
              if (!runsReport) return;
              const snapshot = await getReportRunResult(runsReport.id, run.id);
              setViewReport(runsReport); setViewData(snapshot.result); setViewLoading(false); setRunsReport(undefined);
            }}>查看快照</Button> },
          ]}
        />
      </Modal>

      <Modal
        title={`报表详情 · ${viewReport?.reportName || ''}`}
        open={!!viewReport}
        onCancel={closePreview}
        width={1080}
        rootClassName="report-detail-modal"
        footer={[
          <Button key="close" onClick={closePreview}>关闭</Button>,
          <Button key="export" type="primary" icon={<CloudDownloadOutlined />} disabled={!viewData?.rows?.length} onClick={() => exportResult(viewReport?.reportName || 'report', viewData)}>导出 CSV</Button>,
        ]}
      >
        {viewReport && viewParameters.length > 0 && (
          <Card size="small" title="查询条件" style={{ marginBottom: 16 }}>
            <Form
              form={parameterForm}
              layout="inline"
              onFinish={(values) => loadPreviewData(viewReport, values)}
            >
              {viewParameters.map((parameter) => (
                <Form.Item
                  key={parameter.name}
                  name={parameter.name}
                  label={parameter.label || parameter.name}
                  rules={[{ required: parameter.required, message: `请填写${parameter.label || parameter.name}` }]}
                  style={{ marginBottom: 12 }}
                >
                  {parameter.type === 'number' ? (
                    <InputNumber placeholder={parameter.placeholder} style={{ width: 180 }} />
                  ) : parameter.type === 'boolean' ? (
                    <Select
                      style={{ width: 140 }}
                      options={[{ label: '是', value: true }, { label: '否', value: false }]}
                      placeholder="请选择"
                    />
                  ) : (
                    <Input
                      style={{ width: 210 }}
                      placeholder={parameter.placeholder || (parameter.type === 'stringList' ? '多个值用逗号分隔' : undefined)}
                    />
                  )}
                </Form.Item>
              ))}
              <Form.Item style={{ marginBottom: 12 }}>
                <Button type="primary" htmlType="submit" loading={viewLoading}>应用条件</Button>
              </Form.Item>
            </Form>
          </Card>
        )}
        {viewLoading ? <Skeleton active paragraph={{ rows: 10 }} /> : viewReport && (
          <>
            <div className="report-detail-meta">
              <Tag color={chartTypeColor[viewReport.reportType]}>{chartTypeLabel[viewReport.reportType]}</Tag>
              <span>{viewData?.rowCount ?? viewData?.rows?.length ?? 0} 行</span>
              <span>查询耗时 {viewData?.durationMs ?? '—'} ms</span>
              {viewData?.truncated && <Tag color="warning">结果已截断</Tag>}
            </div>
            <ReportVisualization result={viewData} type={viewReport.reportType} />
            <Typography.Paragraph className="report-detail-sql" ellipsis={{ rows: 3, expandable: true, symbol: '展开 SQL' }}>
              <Typography.Text code>{viewReport.sqlQuery}</Typography.Text>
            </Typography.Paragraph>
          </>
        )}
      </Modal>
    </PageContainer>
  );
};

export default Report;
