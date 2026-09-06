import React from 'react';
import {
  ApiOutlined, CodeOutlined, DatabaseOutlined, FileTextOutlined,
  ScheduleOutlined, SyncOutlined, ThunderboltOutlined,
} from '@ant-design/icons';

export type TaskType = 'cdc_sync' | 'etl' | 'materialized';
export type ExecutionMode = 'continuous' | 'scheduled';
export type TaskScenarioStatus = 'available' | 'planned' | 'experimental';
export type TaskScenarioCategory = 'ingest' | 'develop' | 'compute';

export type TaskScenario = {
  code: string;
  category: TaskScenarioCategory;
  title: string;
  description: string;
  hint: string;
  taskType: TaskType;
  executionMode: ExecutionMode;
  status: TaskScenarioStatus;
  icon: React.ReactNode;
  tags: string[];
  sourceTypes?: string[];
  targetTypes?: string[];
  inputMode: 'tables' | 'sql';
  tableSelection?: 'selected' | 'all';
};

export const taskScenarioGroups: Array<{
  key: TaskScenarioCategory;
  title: string;
  description: string;
}> = [
  { key: 'ingest', title: '数据接入', description: '把业务数据稳定写入 Paimon 湖仓' },
  { key: 'develop', title: '数据加工', description: '使用 Flink SQL 建设明细、汇总与数据产品' },
  { key: 'compute', title: '持续计算', description: '持续维护实时聚合、宽表和物化结果' },
];

/**
 * 产品场景注册中心。
 *
 * taskType 只描述后端执行适配器；code 描述用户真正选择的产品场景。
 * 新增场景时优先复用已有执行适配器，再按需扩展专属配置面板。
 */
export const taskScenarios: TaskScenario[] = [
  {
    code: 'table_realtime_sync',
    category: 'ingest',
    title: '表级实时同步',
    description: 'MySQL／PostgreSQL → Paimon ODS',
    hint: '选择需要同步的表，自动生成映射和 CDC SQL',
    taskType: 'cdc_sync',
    executionMode: 'continuous',
    status: 'available',
    icon: <SyncOutlined />,
    tags: ['实时', 'CDC'],
    sourceTypes: ['mysql', 'postgresql'],
    targetTypes: ['paimon'],
    inputMode: 'tables',
    tableSelection: 'selected',
  },
  {
    code: 'database_realtime_sync',
    category: 'ingest',
    title: '整库实时同步',
    description: '业务库 → Paimon ODS',
    hint: '读取源库全部表并批量建立 ODS 映射，可按需排除',
    taskType: 'cdc_sync',
    executionMode: 'continuous',
    status: 'available',
    icon: <DatabaseOutlined />,
    tags: ['整库', 'CDC'],
    sourceTypes: ['mysql', 'postgresql'],
    targetTypes: ['paimon'],
    inputMode: 'tables',
    tableSelection: 'all',
  },
  {
    code: 'kafka_realtime_ingest',
    category: 'ingest',
    title: 'Kafka 实时入湖',
    description: 'Kafka Topic → Paimon',
    hint: '注册 Kafka 数据源与 Schema 后可接入该执行适配器',
    taskType: 'cdc_sync',
    executionMode: 'continuous',
    status: 'planned',
    icon: <ApiOutlined />,
    tags: ['规划中'],
    sourceTypes: ['kafka'],
    targetTypes: ['paimon'],
    inputMode: 'tables',
  },
  {
    code: 'file_batch_ingest',
    category: 'ingest',
    title: '文件批量入湖',
    description: 'CSV／JSON／Parquet → Paimon',
    hint: '支持对象存储目录监听、分区规则和重复文件校验',
    taskType: 'etl',
    executionMode: 'scheduled',
    status: 'planned',
    icon: <FileTextOutlined />,
    tags: ['规划中'],
    inputMode: 'sql',
  },
  {
    code: 'sql_transform',
    category: 'develop',
    title: 'Flink SQL 加工',
    description: 'ODS → DWD／DWS／ADS',
    hint: '编写 Flink SQL，适合实时明细、汇总和指标加工',
    taskType: 'etl',
    executionMode: 'continuous',
    status: 'available',
    icon: <CodeOutlined />,
    tags: ['SQL', '开发'],
    inputMode: 'sql',
  },
  {
    code: 'scheduled_sql_output',
    category: 'develop',
    title: '定时数据产出',
    description: '周期 SQL → 数据资源',
    hint: '创建后配置周期、依赖、补数和产出登记',
    taskType: 'etl',
    executionMode: 'scheduled',
    status: 'available',
    icon: <ScheduleOutlined />,
    tags: ['周期', '可补数'],
    inputMode: 'sql',
  },
  {
    code: 'materialized_table',
    category: 'compute',
    title: '原生物化表',
    description: 'Flink 2.x Materialized Table',
    hint: '刷新与恢复能力尚未完成验收，暂不开放新建；实时聚合可使用 Flink SQL 加工',
    taskType: 'materialized',
    executionMode: 'continuous',
    status: 'experimental',
    icon: <ThunderboltOutlined />,
    tags: ['实验能力 · 未开放'],
    inputMode: 'sql',
  },
];

export const defaultTaskScenario = taskScenarios[0];

export const availableTaskScenarios = taskScenarios.filter((item) => item.status === 'available');

export const taskTypeLabel: Record<TaskType, string> = {
  cdc_sync: 'CDC 执行器',
  etl: 'Flink SQL 执行器',
  materialized: '物化表执行器',
};

export const getTaskScenario = (scenarioCode?: string) => (
  taskScenarios.find((item) => item.code === scenarioCode)
);

export const getTaskScenarioLabel = (scenarioCode?: string, taskType?: string) => {
  const scenario = getTaskScenario(scenarioCode);
  if (scenario) return scenario.title;
  if (taskType === 'cdc_sync') return '实时同步';
  if (taskType === 'etl') return 'SQL 加工';
  if (taskType === 'materialized') return '物化任务';
  return scenarioCode || taskType || '未知场景';
};

export const getTaskScenarioColor = (scenarioCode?: string, taskType?: string) => {
  const scenario = getTaskScenario(scenarioCode);
  if (scenario?.category === 'ingest' || taskType === 'cdc_sync') return 'blue';
  if (scenario?.category === 'develop' || taskType === 'etl') return 'green';
  if (scenario?.category === 'compute' || taskType === 'materialized') return 'purple';
  return 'default';
};
