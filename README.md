# RT-DWH Management Platform

面向中小团队的轻量实时数仓管理平台：**Flink 负责实时写入，Paimon 负责湖仓存储，Doris 负责交互式查询，RT-DWH 负责统一管理。**

![Paimon、Flink 与 Doris 轻量实时数仓架构](docs/diagrams/paimon-flink-doris-lightweight-architecture.svg)

## 目录

- [项目定位](#项目定位)
- [核心架构](#核心架构)
- [产品能力](#产品能力)
- [未完成能力](#未完成能力)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [本地开发](#本地开发)
- [配置说明](#配置说明)
- [构建与验证](#构建与验证)
- [生产部署注意事项](#生产部署注意事项)
- [项目结构](#项目结构)
- [文档与图示](#文档与图示)
- [常见问题](#常见问题)

## 项目定位

RT-DWH 将实时数据接入、湖仓元数据、交互式查询、数据质量和任务运维收敛到一个管理入口，目标是在没有专职大数据平台团队的情况下，也能搭建和维护一套可用的实时数仓。

平台重点解决六类问题：

- **数据接入**：通过 Flink CDC 将 MySQL 等业务数据持续写入 Paimon。
- **数据管理**：同步 Paimon Catalog、Schema、Snapshot 和 ODS／DWD／DWS／ADS 分层元数据。
- **数据查询**：通过 Doris Paimon External Catalog 执行即席查询，不让交互式查询与 Flink CDC Job 争抢 Slot。
- **稳定运行**：管理 Flink Job 的提交、暂停、恢复、停止、Savepoint 和状态自动校准。
- **质量治理**：执行质量规则、表维护、健康检查和多通道告警。
- **平台管控**：统一权限、操作审计、查询配额、运行参数和依赖健康状态。

适合：

- 中小规模实时数仓、数据中台验证和湖仓方案原型；
- 希望用一套页面管理 Flink、Paimon 与 Doris 的工程团队；
- 需要从单机 Compose 起步，再逐步迁移到共享存储和集群部署的项目。

当前边界：

- 默认 Flink 镜像已内置 MySQL／PostgreSQL CDC、Paimon、MySQL JDBC 和 Hadoop 依赖；PostgreSQL CDC 启动前会自动执行 logical replication 权限与 Slot 容量预检。
- Doris 对 Paimon 的定位是只读查询；写入、Compact、Snapshot 清理等操作仍由 Flink／Paimon 完成。
- Compose 使用本地共享卷演示 Paimon Warehouse，只适合单机开发；生产环境必须使用所有 Flink、Doris 节点可访问的共享存储。
- Helm Chart 可部署管理平台前后端，依赖外部 MySQL、Flink、Doris、共享存储和镜像仓库，不负责安装数据基础设施。

## 核心架构

### 写入链路

```text
MySQL／PostgreSQL
        │ CDC
        ▼
Flink 2.2 + Flink CDC
        │ 流式写入
        ▼
Paimon Catalog + Paimon Warehouse
```

Flink 负责 Schema 探测、CDC SQL 生成、Checkpoint、Savepoint 和持续写入。Paimon 使用 MySQL JDBC Catalog 保存元数据，数据文件写入 Warehouse。

### 查询链路

```text
SQL 工作台／报表／API
        │
        ▼
RT-DWH 查询服务
  ├─ 只读 SQL 校验
  ├─ RBAC、超时、行数限制
  └─ 历史、取消、Trace ID
        │ Doris MySQL 协议
        ▼
Doris FE／BE
        │ Paimon External Catalog
        ▼
Paimon Catalog + Warehouse
```

Doris 负责 Paimon 数据的交互式查询。后端启动时可自动创建 `rtdwh_paimon` Catalog，并通过 Doris 动态读取 Database、Table 和 Column，驱动前端 Catalog 树与 SQL 智能提示。

### 管理控制面

RT-DWH 后端通过 Flink REST API、Flink SQL Gateway、Doris MySQL 协议和 Paimon JDBC Metastore 统一管理依赖组件，同时把运行状态持久化到 MySQL。

| 组件 | 主要职责 |
|---|---|
| RT-DWH Frontend | 数据总览、任务、元数据、查询、质量、告警和设置页面 |
| RT-DWH Backend | API、权限、任务编排、状态监控、查询网关和系统配置 |
| Flink JobManager／TaskManager | CDC、流式计算、Checkpoint 与 Savepoint |
| Flink SQL Gateway | 执行生成的 CDC／Paimon SQL 和维护语句 |
| Paimon | Catalog、Schema、Snapshot 与湖仓数据文件 |
| Doris FE／BE | Paimon Catalog 发现、SQL 解析与分布式查询 |
| MySQL | 管理库、Quartz 表和 Paimon JDBC Catalog |

## 产品能力

当前菜单、已落地能力、使用流程与未完成边界见 [产品能力全景](docs/product-capability-map.md)（2026-09-05，基线 `0046f3c`）；下文为产品领域概述，具体成熟度以该清单和实施验收记录为准。

剩余功能与优化项的开发顺序、阶段排期、依赖及验收标准见 [未完成能力与优化开发计划](docs/v2.0-remaining-development-plan.md)。

RT-DWH 不是 Flink、Paimon 或 Doris 的替代品，而是位于三者之上的实时数仓控制面。平台围绕“数据进入湖仓以后，如何持续运行、被发现、被查询、被治理”组织产品能力。

完整的用户角色、领域对象、产品闭环、技术架构和部署决策见 [`docs/product-design-and-technical-solution.md`](docs/product-design-and-technical-solution.md)。

### 产品角色

| 角色 | 主要诉求 | 平台入口 |
|---|---|---|
| 数据工程师 | 接入业务库、创建 CDC／ETL 任务、处理失败和补数 | 数据源、同步任务、任务编排 |
| 数据开发／分析师 | 查找数据资产、编写 SQL、复用查询并制作报表 | 表管理、即席查询、报表看板 |
| 数据治理／运维人员 | 管理质量规则、血缘、生命周期、告警和组件健康 | 数据质量、数据血缘、湖仓维护、系统设置 |
| 平台管理员 | 管理账号权限、运行参数、密钥和操作审计 | 用户权限、系统设置、操作审计 |

### 能力全景

| 产品域 | 核心价值 | 已实现能力 |
|---|---|---|
| 数据接入 | 让业务数据稳定、持续地进入湖仓 | MySQL／PostgreSQL 数据源管理、连通性测试、Schema 探测、表映射、全量＋增量与增量启动模式、CDC SQL 预览 |
| 实时开发与编排 | 让流任务能够发布、恢复、补数和追踪 | Flink Job 生命周期、Checkpoint／Savepoint、失败重试、运行指标、DAG 依赖、环路校验、任务版本、回滚、按业务日期补数和 Cron 周期产出 |
| 湖仓资产管理 | 让 Paimon 中的数据可发现、可理解、可维护 | Catalog 元数据同步、数仓分层、Schema／Snapshot、主键与分区、责任人／业务域／标签／敏感级别、Compact 与快照清理 |
| 查询与数据服务 | 让湖仓数据可低延迟查询并服务分析及系统集成 | Doris 加速 Paimon、SQL 工作台、查询下载、报表看板、周期数据资源、参数化数据 API、应用凭证、服务授权、限流和调用审计 |
| 质量与可观测性 | 让数据异常和运行故障能够被发现、定位和处置 | 质量规则、定时检查、运行批次、任务与组件指标、依赖健康快照、钉钉／企业微信／邮件告警、告警闭环 |
| 安全与平台管控 | 让多人协作有边界、变更有记录、资源有约束 | JWT、接口级 RBAC、Catalog／Database／Table 数据范围、密码加密、写操作审计、查询并发配额、Workload Group 和运行参数管理 |

### 五类公共基础能力

平台提供“公共能力治理中心”，把分散在业务模块中的横向能力汇总为统一入口，并根据当前账号的数据范围展示健康分和待处理风险：

| 公共能力 | 统一提供的能力 |
|---|---|
| 统一检索与资产发现 | 跨数据表、任务、报表和数据接口检索；展示分层、状态、负责人并跳转到原始资源 |
| 权限与数据安全 | 汇总当前角色、接口权限和数据范围；所有资产检索与 SLA 统计继续执行 Catalog／Database／Table 范围校验 |
| 数据质量与 SLA | 汇总启用质量规则、未解决质量告警和周期产出 SLA 风险；按逾期程度标记 warning／high／critical |
| 可观测与告警 | 汇总依赖组件健康、未恢复平台告警和失败任务，提供统一风险视图 |
| 审计与变更追溯 | 汇总最近 24 小时写操作、失败操作和任务版本数量，并跳转审计记录处理 |

治理中心位于 `/foundation`。健康分用于发现治理缺口，不替代质量门禁、告警规则或执行引擎自身监控。

### 1. 数据接入

平台以“业务数据源—源表—平台 Paimon Catalog—同步任务”为核心模型，把业务连接配置、湖仓基础设施和 CDC 作业配置分离：

- 数据源页面只管理 MySQL、PostgreSQL 等业务源连接，提供连通性测试与表结构探测；
- Paimon Catalog 与 Warehouse 是平台级唯一基础设施，在系统设置中统一维护，不由每个任务重复选择；
- 选择源表并映射到 Paimon Database／Table，支持 ODS 等目标分层；
- 根据源表字段、主键和数据库类型自动生成 Flink CDC SQL；
- 支持首次全量后持续增量，以及从最新位点开始的纯增量模式；
- 在提交前预览 SQL、启动模式、并行度和 Checkpoint 参数，降低黑盒配置风险。

默认 Flink 镜像同时内置与 Flink 2.2 匹配的 MySQL CDC、PostgreSQL CDC 和 Paimon Connector；Compose 中的 MySQL 已启用 ROW Binlog，PostgreSQL 已启用 logical replication。

### 2. 实时开发与任务编排

平台显式区分持续任务与周期任务，避免把 Flink Job 状态和调度实例状态混为一套生命周期：

- 持续任务（CDC、流式 SQL、物化维护）支持启动、停止、暂停、恢复、重试和手动 Savepoint；
- 采集 Job 状态、Checkpoint、Lag 与吞吐量，并校准 `NOT_FOUND`、`SUSPENDED` 和集群不可达等状态；
- 周期任务进入独立的发布、DAG、Cron、业务日期、补数和运行实例闭环；
- 每个运行实例固定绑定创建时的已发布版本，后续编辑草稿不会改变在途实例或线上调度所使用的定义；
- 周期 SQL 任务由内置 Flink SQL Runner 自动领取、提交、心跳、重试并回写结果；
- 可声明任务产出的 Catalog／Database／Table、数仓分层、负责人和 SLA；成功实例自动登记产出记录并注册为数据资产；
- 可开启产出质量门禁：命中目标表的质量规则失败时将本次资源标记为 `blocked`，不会发布为可用数据；
- 运行实例具备 Job ID、租约、心跳、超时回收、指数退避重试、取消和人工重跑；
- 调度器在上游同业务日期实例成功后，自动释放下游等待实例。

CDC 是常驻流任务，不参与按日期补数；除内置 Flink SQL Runner 外，外部执行器仍可通过领取、心跳、关联 Job 和完成接口接入 Doris SQL、Spark 或 Python Runner。

### 3. 湖仓资产管理

平台将 Paimon 的物理元数据与数仓治理属性组合成可运营的数据资产：

- 同步 JDBC Catalog 中的 Database、Table、Column、主键、分区键和表选项；
- 识别 ODS／DWD／DWS／ADS 分层，查看 Schema、Snapshot、文件量和数据规模；
- 维护表责任人、业务域、标签、敏感级别、业务描述和生命周期状态；
- 展示由同步映射、任务依赖和 SQL 解析形成的真实数据血缘；
- 执行单表或批量 Compact、Expire Snapshots、Orphan Files Cleanup，并记录维护日志。

#### Paimon 分层存储方案

平台只维护一套 Paimon JDBC Catalog 和一处所有 Flink、Doris 节点均可访问的共享 Warehouse。ODS、DWD、DWS、ADS 是 Warehouse 内按 Database／表目录组织的逻辑分层，不是四套独立存储：

| 分层 | 数据定位 | 写入与表模型 | 分区建议 | 主要消费方 |
|---|---|---|---|---|
| ODS | 与源系统对齐的原始／当前明细，可审计、可重放 | 主键 Upsert，优先接收完整 CDC 变更 | 创建日或业务日，避免频繁修改历史分区 | DWD 清洗、审计与重放 |
| DWD | 清洗、去重、标准化后的原子事实 | 主键 Upsert；按查询兼容性评估 MOR／MOW | 业务日期与稳定维度 | DWS 汇总、明细分析 |
| DWS | 面向主题、可复用的公共汇总与宽表 | 增量聚合或周期覆盖 | 通常按业务日期 | 指标、报表、ADS |
| ADS | 面向具体报表、数据接口和应用场景 | 查询导向，能够从上游稳定重建 | 交付周期或服务主题 | 数据 API、看板与导出 |

物理层级为 `JDBC Catalog → Warehouse → Database → Table → Partition/Bucket → Snapshot/Manifest/Data File`。Catalog 保存表结构和文件指针，Warehouse 保存业务数据及快照文件。

生命周期分成两套策略：`Expire Snapshots` 控制回溯／恢复窗口并释放不再被保留快照引用的旧文件，不等同于业务数据保留天数；业务数据到期需要独立的分区生命周期策略。需要长期审计的节点应先创建 Tag，再执行快照清理。当前平台已提供 Compact、快照清理和孤立文件清理，分区生命周期与 Tag 自动化列入后续建设。维护恢复详情现可查看原端点、提交证据、会话清理重试与人工处置记录；结果未知时阻止重复维护。V29／V30 升级与限制见 [维护恢复契约](docs/maintenance-recovery-contract.md)。

### 4. 查询与数据服务

查询链路使用 Doris 读取 Paimon，避免交互式查询占用 Flink CDC 的计算资源：

- 自动初始化 Doris Paimon External Catalog，并关闭 Paimon table-object 缓存以读取最新 Snapshot；
- 浏览 Catalog／Database／Table／Column，使用 Monaco SQL 编辑器和上下文智能补全；
- 执行只读 SQL 校验、超时控制、最大行数限制、结果截断、用户并发限制和公平短时排队；
- 支持查询取消、CSV 导出、服务端／浏览器 SQL 收藏和查询历史；
- 记录执行引擎、Catalog、Database、Trace ID、耗时、状态和错误信息；
- 采集 Doris 扫描量、CPU、峰值内存和 Query Profile，展示排队耗时、成本软预算及高成本查询排行；
- 基于安全的类型化参数 SQL 模板配置表格、折线、柱状、饼图和混合图报表，支持手动／定时参数、快照与订阅分发。
- 将只读 Doris SQL 发布为参数化数据 API，为外部系统分配 AppKey／AppSecret，并按应用授权具体服务；
- API 草稿与线上版本隔离，发布预检固定参数及结果契约；支持并发修订校验、不可变历史、重新校验后的回退，调用日志记录实际发布版本，详见 [API 发布契约](docs/data-api-publication-contract.md)；
- 对开放接口实施最大行数、超时、每分钟限流和数据访问范围校验，记录来源 IP、耗时、行数、状态与错误；应用密钥只在创建或轮换时返回一次。

这里的“实时可见”由两部分组成：Flink 在 Checkpoint 时提交 Paimon Snapshot，Doris 查询最新 Snapshot。端到端可见延迟通常不超过一个 Checkpoint 周期加查询时间。

### 5. 数据质量与可观测性

平台把规则执行、异常发现、通知和处置组织成治理闭环：

- 提供空值率、唯一性、范围、行数和自定义 SQL 等质量规则；
- 通过 Doris 定时执行质量检查，保留批次与规则级运行记录；
- 汇总 Flink Job、Checkpoint、Lag、吞吐和 MySQL／Paimon／Doris 组件健康；
- 持久化健康快照，区分数据异常、任务异常和基础组件异常；
- 通过钉钉、企业微信和邮件发送通知；
- 自动评估任务失败、数据延迟和质量异常规则，按故障对象去重；
- 指标恢复后自动关闭告警并发送恢复通知，记录通知状态；
- 支持告警确认、解决和处置状态跟踪。

### 6. 安全与平台管控

- 使用 JWT 鉴权和接口级 RBAC，内置 `ADMIN`、`DEVELOPER`、`VISITOR` 三类角色；
- 对数据源密码进行加密存储，生产环境要求配置稳定的加密密钥；
- 对非只读接口记录操作人、资源、请求结果和错误信息；
- 对即席查询设置单用户并发上限、单查询内存和 Workload Group；
- 在系统设置中维护 Flink、Doris 等运行参数并执行连接测试；
- 统一返回业务错误、Trace ID 和依赖健康信息，便于问题定位。

### 典型产品闭环

1. **接入闭环**：创建数据源 → 探测源表 → 配置映射 → 预览 CDC SQL → 启动作业 → 观察 Checkpoint 和 Lag。
2. **分析闭环**：发现 Paimon 表 → 在 SQL 工作台验证口径 → 收藏 SQL → 配置报表 → 加入数据看板。
3. **治理闭环**：补充表责任人和业务标签 → 配置质量规则 → 定时执行 → 异常告警 → 确认并处置。
4. **恢复闭环**：发现任务异常 → 查看运行状态和日志 → Savepoint／重试 → 状态自动校准 → 验证下游数据。
5. **变更闭环**：调整任务配置 → 发布版本 → 检查 DAG → 运行或补数 → 必要时回滚 draft → 审计变更记录。
6. **周期产出闭环**：发布任务版本 → 配置 Cron 和业务日期 → 声明产出资源与 SLA → 自动运行 → 质量门禁 → 资产登记与产出追踪。
7. **接口服务闭环**：验证只读 SQL → 定义类型化参数 → 发布服务 → 创建调用应用 → 服务授权 → 外部调用 → 限流与日志审计。

## 未完成能力

主要模块和首批 P1 能力已有实现，但代码入口不等于生产交付完成。下一大版本建议以“可信湖仓交付”为主线，优先补齐发布、门禁、依赖与运行事实的一致性：

| 优先级 | 近期重点 |
|---|---|
| P0 | 补齐质量门禁与下游依赖、完整发布契约、Paimon 维护执行终态及跨模块数据范围 |
| P1 | 统一资产与普通 View、Flink／Paimon 运维闭环、质量／SLA 和 API 发布契约；补齐 PostgreSQL 兼容、升级恢复与真实部署验收 |
| P2 | 列级血缘与脱敏、统一指标口径、跨团队成本运营和通用变更审批；原生物化等实验能力按专项验收逐步开放 |

各项现状、缺口见 [产品路线图](docs/product-roadmap.md)；下一大版本的产品模型、View／物化边界、UI 流程、工作包与排期见 [2.0 产品逻辑与迭代规划](docs/v2.0-product-iteration-plan.md)。实际落地与验证证据见 [2.0 实施进度](docs/v2.0-implementation-progress.md)，资产身份与结构演进见 [资产契约](docs/asset-contract.md)。

## 技术栈

| 层 | 技术 | 当前项目版本／配置 |
|---|---|---|
| 后端 | Spring Boot、Undertow、JPA、Security、Quartz | Spring Boot 3.3、Java 17 |
| 前端 | Umi Max、Ant Design、ProComponents、Monaco Editor | Umi 4.4、React 18、Ant Design 5 |
| 实时计算 | Apache Flink、Flink CDC | Flink 2.2.1、MySQL／PostgreSQL CDC 3.6.0-2.2 |
| 湖仓 | Apache Paimon | Paimon 2.0.0、JDBC Catalog |
| 查询 | Apache Doris | Doris 4.1.3、Paimon External Catalog |
| 管理数据库 | MySQL、Druid | MySQL 8.0、Druid 1.2.23 |
| 部署 | Docker Compose、Helm | Compose 为默认开发方式，Helm 部署前后端并接入外部基础设施 |
| 可观测性 | Spring Boot Actuator、Prometheus、Grafana | Prometheus 2.52、Grafana 11，可选启用 |

> Doris 4.1 的 Paimon JDBC Catalog 仍属于实验能力。升级 Paimon 或 Doris 时，应先验证 Catalog、主键表、Deletion Vector、Schema Evolution 和复杂类型兼容性。参见 [Apache Doris Paimon Catalog 文档](https://doris.apache.org/docs/4.x/lakehouse/catalogs/paimon-catalog/)。

## 快速开始

### 1. 前置条件

- Docker Engine 或 Docker Desktop；
- Docker Compose v2；
- Git；
- 能够访问 Maven Central 和 Docker Hub，用于首次构建 Flink 镜像及拉取依赖；
- 为 Flink、Doris 和 MySQL 预留足够的本机 CPU、内存与磁盘空间。

本地 Java／Node.js 只在源码开发时需要，纯 Compose 启动不要求预先安装。

### 2. 配置环境变量

```bash
git clone <repo-url> rt-dwh-mgmt
cd rt-dwh-mgmt
cp deploy/.env.example deploy/.env
```

至少填写以下变量：

```dotenv
MYSQL_ROOT_PASSWORD=<MySQL root 密码>
MYSQL_PASSWORD=<rtdwh_admin 密码>
DB_PASSWORD=<与 MYSQL_PASSWORD 相同>
PAIMON_JDBC_PASSWORD=<与 MYSQL_PASSWORD 相同>

JWT_SECRET=<至少 32 个字符的随机密钥>
ENCRYPTION_KEY=<数据源密码加密密钥>

INIT_USERS_ENABLED=true
INIT_ADMIN_PASSWORD=<管理员初始密码>
INIT_DEV_PASSWORD=<开发者初始密码>
INIT_GUEST_PASSWORD=<访客初始密码>
```

默认 Compose 让管理库和 Paimon JDBC Catalog 共用 `rtdwh_admin` 用户，因此 `MYSQL_PASSWORD`、`DB_PASSWORD`、`PAIMON_JDBC_PASSWORD` 应保持一致。

### 3. 启动服务

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

Flink JobManager 使用 Adaptive Scheduler，TaskManager 每个副本的 Slot 数由
`FLINK_TASKMANAGER_SLOTS` 控制。需要在单机开发环境增加或减少 TaskManager
容量时，执行：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d \
  --no-deps --scale flink-taskmanager=3 flink-taskmanager
```

`--scale` 只改变可用 TaskManager/Slot 容量，不等于修改运行中 Job 的并行度。
Job 仍受已配置并行度、最大并行度和各 Vertex resource requirements 约束。
缩容前应确认最近一次 Checkpoint 成功，并先降低 Job 的资源要求；Adaptive
Scheduler 扩缩时会重启受影响的 Tasks，并非零停顿。Compose 的状态卷只在同一
Docker 主机共享，不是生产级分布式存储；后端容器也不会直接执行 Docker 扩缩命令。

从旧版本升级时，已经运行的多表 CDC／自定义多 INSERT 任务可能对应多个 Flink Job，
但旧版数据库只保存最后一个 Job ID，平台无法可靠找到其余 Job，因此会禁止统一扩缩。
请先在 Flink UI／REST 中识别并逐个取消该任务的全部关联 Job，再删除并重建平台任务；
新版本要求使用 Statement Set 将一个平台任务提交为一个 Flink Job。不要只点击平台的
“停止”，否则它只能取消已保存 Job ID 对应的最后一个 Job。

查看状态和日志：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps
docker compose --env-file deploy/.env -f deploy/docker-compose.yml logs -f rtdwh-backend
```

### 4. 访问地址

| 服务 | 地址 | 说明 |
|---|---|---|
| RT-DWH Web | `http://localhost` | 管理平台入口 |
| RT-DWH API | `http://localhost/api/v1` | Nginx 对外 API 前缀 |
| Backend Actuator | `http://localhost/api/v1/actuator/health` | 后端基础健康检查 |
| Flink Web UI | `http://localhost:8081` | Job 与 TaskManager 状态 |
| Flink SQL Gateway | `http://localhost:9083` | 仅绑定本机 |
| Doris FE Web | `http://localhost:8030` | Doris FE 页面 |
| Doris MySQL Protocol | `localhost:9030` | JDBC／MySQL 客户端连接地址 |
| Doris BE Web | `http://localhost:8040` | Doris BE 页面 |

MySQL、PostgreSQL、Flink、Doris、后端和监控端口在 Compose 中默认绑定 `127.0.0.1`；Nginx Web 入口按 `RTDWH_HTTP_PORT` 暴露，请按部署环境调整防火墙和端口映射。

### 5. 首次登录

首次启动时，设置 `INIT_USERS_ENABLED=true` 才会创建不存在的 `admin`、`dev01` 和 `guest` 用户。确认登录正常后，将其改回：

```dotenv
INIT_USERS_ENABLED=false
```

初始化不会覆盖已经存在的用户密码。

### 6. 启用可选监控

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml --profile monitoring up -d
```

- Prometheus：`http://localhost:9090`
- Grafana：`http://localhost:3001`

### 7. 运行端到端验收

完整环境启动且管理员已初始化后，执行：

```bash
RTDWH_ADMIN_PASSWORD='<管理员密码>' ./scripts/smoke-cdc-paimon-doris.sh
```

脚本会创建隔离的 MySQL 源表和临时平台配置，启动真实 CDC Job，写入一条增量事件，并循环通过 Doris 查询 Paimon；成功时输出 `PASS`，任务和临时数据源配置会自动清理。可用 `SMOKE_TIMEOUT_SECONDS` 调整等待时间。

### 8. Kubernetes／Helm

Chart 默认部署 Backend、Frontend、Nginx 配置、ServiceAccount 和 Ingress，并连接集群中已有的 MySQL、Flink、Doris 与共享 Paimon Warehouse。生产密钥不写入 `values.yaml`，先创建 Secret：

```bash
kubectl create secret generic rtdwh-mgmt-secrets \
  --from-literal=DB_PASSWORD='<管理库密码>' \
  --from-literal=PAIMON_JDBC_PASSWORD='<Catalog 密码>' \
  --from-literal=JWT_SECRET='<至少 32 字符随机密钥>' \
  --from-literal=ENCRYPTION_KEY='<数据源加密密钥>'

helm lint deploy/helm/rtdwh-mgmt
helm upgrade --install rtdwh deploy/helm/rtdwh-mgmt \
  --set ingress.host=rtdwh.example.com
```

如使用外部 Secret，修改 `backend.secret.existingSecret`；本地临时验证也可用私有 values 文件设置 `backend.secret.create=true` 和 `backend.secret.data`。Chart 已通过 Helm 3.16 lint 和模板渲染校验。

#### Flink Kubernetes Operator Native Session Cluster

生产环境建议单独安装 Apache Flink Kubernetes Operator 1.15.0，并使用
[`deploy/flink/kubernetes/operator-session-cluster.yaml`](deploy/flink/kubernetes/operator-session-cluster.yaml)
创建 Flink 2.2.1 Native Session Cluster 和独立 SQL Gateway。Operator 是集群级
组件，不作为本项目应用 Chart 的隐式依赖安装。

Operator Webhook 默认要求集群已安装 cert-manager；应先按 Operator 1.15 官方
Quick Start 完成该前置条件，不建议在生产环境为了省略证书管理而关闭 Webhook。

示例清单中的镜像地址和三个 `s3://replace-me-before-apply` URI 必须在应用前替换，
镜像还必须启用对应的 Flink 对象存储文件系统插件，并通过 Workload Identity 或
Secret 提供凭证。Checkpoint、Savepoint 和 HA 元数据禁止使用 `file://`，否则 Pod
替换或跨节点调度后无法恢复。

```bash
helm repo add flink-operator-repo https://downloads.apache.org/flink/flink-kubernetes-operator-1.15.0/
helm install flink-kubernetes-operator flink-operator-repo/flink-kubernetes-operator \
  --version 1.15.0

# 完成镜像、状态 URI 和凭证配置后，再应用到目标 namespace（此处以 rtdwh 为例）。
kubectl apply -n rtdwh \
  -f deploy/flink/kubernetes/operator-session-cluster.yaml
```

Operator 创建的 REST Service 为 `rtdwh-flink-session-rest:8081`，示例 Gateway
Service 为 `rtdwh-flink-sql-gateway:8083`。部署应用 Chart 时应把
`backend.env.FLINK_REST_URL`、`backend.env.SQL_GATEWAY_URL` 和
`backend.env.FLINK_SAVEPOINT_DIR` 指向这些 Service 与同一外部状态存储，并把
`backend.env.FLINK_SCALING_PROVIDER` 设为 `kubernetes-native`。只有启用该标记且
集群实际使用 Native Kubernetes ResourceManager 时，平台才会展示 TaskManager
自动扩展能力；默认 `standalone` 只允许观测容量和调整 Job 资源需求。

Paimon 不能继续使用 Chart 默认的 Pod 本地 `/data/paimon`。必须同时把
`backend.env.PAIMON_WAREHOUSE` 和 `backend.env.DORIS_PAIMON_WAREHOUSE` 设置为
同一个 S3、HDFS 或 OSS Warehouse URI，并确保 SQL Gateway、JobManager、所有
TaskManager 与 Doris 都装有对应文件系统插件且使用同一套凭证。否则扩容出来的
TaskManager 看不到既有 Paimon 文件，不能投入生产。

Native Kubernetes ResourceManager 会根据 Slot 需求创建或删除 TaskManager Pod；
当集群没有可用节点资源时，还需要 Kubernetes 节点自动扩缩或预留容量。当前平台
通过 SQL Gateway 提交的 Job 不对应 `FlinkSessionJob` CR，因此不受 Operator
Autoscaler 管理；它们只能通过 Adaptive Scheduler 的 resource-requirements API
调整并行度。若要使用 Operator Autoscaler，需将 Job 生命周期迁移为
`FlinkSessionJob` 或 Application-mode `FlinkDeployment`，不能仅靠给 Session
Cluster 打开 autoscaler 配置来伪装支持。

生产上线还应按租户容量设置 `FLINK_SCALING_MAX_PARALLELISM`，并在 Flink 所在
Namespace 配置 `ResourceQuota`／`LimitRange`，防止错误的资源需求无限申请 Pod。
同一 Session Cluster 资源紧张时不保证多个 Job 之间的 Slot 公平分配；关键 CDC
任务应拆到独立的 Application Cluster。双 JobManager 还应配置跨节点拓扑分散和
PodDisruptionBudget，避免两个副本落到同一故障域。

## 本地开发

### 开发环境

- Java 17；
- Maven 3.9+；
- Node.js 18+；
- npm；
- 可访问的 MySQL、Flink、Paimon Warehouse 和 Doris。

可以先用 Compose 启动依赖组件：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d \
  mysql postgresql doris-fe doris-be \
  flink-jobmanager flink-taskmanager flink-sql-gateway
```

启动后端：

```bash
cd backend
mvn spring-boot:run
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

- 后端默认地址：`http://localhost:8080`
- 前端开发地址：`http://localhost:8000`
- 前端将 `/api/v1` 代理到本地后端，并移除该前缀。

本地直启后端前，请按 `backend/src/main/resources/application.yml` 配置数据库、Paimon、Flink 和 Doris 连接信息。不要在生产环境使用文件中的开发默认密码。

## 配置说明

完整模板见 [`deploy/.env.example`](deploy/.env.example)。下面列出最常用配置。

### 数据库与密钥

| 变量 | 用途 | Compose 默认值 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | 必填 |
| `MYSQL_USER` | 管理库用户 | `rtdwh_admin` |
| `MYSQL_PASSWORD` | 管理库用户密码 | 必填 |
| `DB_USERNAME`／`DB_PASSWORD` | 后端管理库连接 | 用户默认 `rtdwh_admin`，密码必填 |
| `PAIMON_JDBC_USER`／`PAIMON_JDBC_PASSWORD` | Paimon JDBC Catalog 连接 | 用户默认 `rtdwh_admin`，密码必填 |
| `JWT_SECRET` | JWT 签名密钥 | 必填，至少 32 字符 |
| `ENCRYPTION_KEY` | 数据源密码加密 | 必填 |

### Doris 查询

| 变量 | 用途 | 默认值 |
|---|---|---|
| `DORIS_ENABLED` | 启用 Doris 即席查询 | `true` |
| `DORIS_JDBC_URL` | FE MySQL 协议 JDBC 地址 | Compose 固定为 `jdbc:mysql://doris-fe:9030` |
| `DORIS_HTTP_URL` | FE HTTP 地址 | Compose 固定为 `http://doris-fe:8030` |
| `DORIS_USERNAME`／`DORIS_PASSWORD` | Doris 查询账号 | `root`／空，仅适合本地开发 |
| `DORIS_CATALOG` | Paimon External Catalog 名称 | `rtdwh_paimon` |
| `DORIS_DATABASE` | 默认查询 Database | `ods` |
| `DORIS_INITIALIZE_CATALOG` | 后端启动时创建 Catalog | `true` |
| `DORIS_CATALOG_INIT_RETRY_MS` | Doris 未就绪时重试创建 Catalog 的间隔 | `30000` |
| `DORIS_WORKLOAD_GROUP` | 即席查询 Workload Group | `rtdwh_adhoc` |
| `DORIS_EXEC_MEM_LIMIT_BYTES` | 单查询执行内存限制 | `2147483648` |
| `QUERY_MAX_CONCURRENT_PER_USER` | 单用户并发查询上限 | `2` |
| `QUERY_QUEUE_WAIT_SECONDS` | 并发槽位的最长排队时间 | `3` |
| `QUERY_BUDGET_SCANNED_BYTES` | 单查询扫描量软预算 | `5368709120` |
| `QUERY_BUDGET_CPU_MS` | 单查询 CPU 时间软预算 | `30000` |
| `QUERY_BUDGET_PEAK_MEMORY_BYTES` | 单查询峰值内存软预算 | `2147483648` |

### 监控与用户初始化

| 变量 | 用途 | 默认值 |
|---|---|---|
| `HEALTH_MONITOR_ENABLED` | 持久化依赖健康快照 | `true` |
| `HEALTH_MONITOR_INTERVAL_MS` | 健康检查周期 | `60000` |
| `HEALTH_MONITOR_INITIAL_DELAY_MS` | 启动后首次检查延迟 | `5000` |
| `QUALITY_SCHEDULE_ENABLED` | 启用定时质量检查 | `true` |
| `QUALITY_SCHEDULE_INTERVAL_MS` | 全量质量检查周期 | `3600000` |
| `WORKFLOW_SCHEDULER_ENABLED` | 启用 DAG 依赖实例释放 | `true` |
| `WORKFLOW_DEPENDENCY_CHECK_MS` | 待运行实例依赖检查周期 | `10000` |
| `WORKFLOW_SCHEDULE_TRIGGER_ENABLED` | 启用 Cron 周期实例创建 | `true` |
| `WORKFLOW_SCHEDULE_TRIGGER_INTERVAL_MS` | 周期调度扫描间隔 | `10000` |
| `WORKFLOW_RUNNER_ENABLED` | 启用内置 Flink SQL Runner | Compose 为 `true` |
| `WORKFLOW_RUNNER_MAX_CONCURRENT` | Runner 最大并发实例数 | `2` |
| `WORKFLOW_RUNNER_LEASE_SECONDS` | 实例租约时长 | `60` |
| `WORKFLOW_RUNNER_MAX_RETRIES` | 自动重试次数 | `3` |
| `ALERT_SCHEDULE_ENABLED` | 启用告警规则自动评估 | `true` |
| `ALERT_SCHEDULE_INTERVAL_MS` | 告警评估周期 | `30000` |
| `INIT_USERS_ENABLED` | 首次创建演示用户 | `false` |
| `INIT_ADMIN_PASSWORD` | `admin` 初始密码 | 启用初始化时必填 |
| `INIT_DEV_PASSWORD` | `dev01` 初始密码 | 启用初始化时必填 |
| `INIT_GUEST_PASSWORD` | `guest` 初始密码 | 启用初始化时必填 |

通知相关变量包括 `DINGTALK_WEBHOOK`、`WECOM_WEBHOOK`、`MAIL_HOST` 和 `ALERT_EMAIL_RECIPIENTS`。

## API 约定

对外 API 统一使用 `/api/v1` 前缀。后端业务响应格式为：

```json
{
  "code": 0,
  "data": {},
  "message": "success"
}
```

前端在 `src/app.tsx` 中统一解包 `data`：

- `code === 0`：返回业务数据；
- `code !== 0`：抛出错误并进入全局异常处理；
- Spring Data 分页结果使用 `content`、`totalElements`、`number`、`size`。

发布数据服务并给调用应用授权后，外部系统可通过 HTTPS 调用：

```bash
curl -X POST 'https://rtdwh.example.com/api/v1/open/data/order-summary' \
  -H 'Content-Type: application/json' \
  -H 'X-App-Key: dsa_xxx' \
  -H 'X-App-Secret: <仅创建或轮换时可见的密钥>' \
  -d '{"start_date":"2026-08-21","region":"华东"}'
```

开放接口只执行当前发布快照，编辑草稿不会影响调用。服务端会执行应用授权、有效期、限流、参数类型、执行人当前权限、数据范围、结果契约、最大行数与超时校验，响应和日志包含实际版本。V28 管理端更新／发布／下线／回退请求需携带 `expectedRevision`，升级说明见 [API 发布契约](docs/data-api-publication-contract.md)。生产环境必须启用 HTTPS；多副本部署建议在 API Gateway 或 Redis 中实现共享限流，并按安全要求增加 HMAC 防重放或 OAuth2。

## 构建与验证

后端：

```bash
cd backend
mvn test
mvn package -DskipTests
```

前端：

```bash
cd frontend
npm install
npx tsc --noEmit
npm run build
```

数据库结构由 Flyway 管理，启动后端时会顺序执行 `backend/src/main/resources/db/migration`。生产升级前应先备份管理库，并禁止修改已经发布过的迁移文件。

Compose 配置检查：

```bash
docker compose --env-file deploy/.env -f deploy/docker-compose.yml config --quiet
```

## 生产部署注意事项

### Paimon Warehouse

Compose 通过 `PAIMON_HOST_WAREHOUSE` 指向同一个宿主机目录，并挂载给 Flink、后端和 Doris。生产多节点环境必须改用 HDFS、S3、MinIO、OSS 等共享存储，并为 Flink 与所有 Doris BE 配置相同的 Warehouse 地址和访问凭证。

### Doris Catalog

后端默认使用 Paimon JDBC Catalog。确认以下条件：

- Doris 版本支持目标 Paimon Catalog 类型；
- FE 可以访问 Paimon JDBC Metastore；
- BE 可以读取 Warehouse；
- `DORIS_PAIMON_JDBC_DRIVER_URL` 对 Doris 节点可下载；
- 升级 Doris／Paimon 后完成主键表、Schema Evolution、Deletion Vector 和复杂类型回归。

### 密钥与账号

- 替换 Doris `root` 空密码，并使用最小权限查询账号；
- 不要提交 `deploy/.env`；
- 使用稳定的 `JWT_SECRET` 和 `ENCRYPTION_KEY`，不要在已有加密数据后随意更换；
- 首次用户初始化完成后关闭 `INIT_USERS_ENABLED`；
- 限制 MySQL、Doris、Flink 和监控端口的外部访问。

### Kubernetes

Helm Chart 位于 `deploy/helm/rtdwh-mgmt`，当前用于接入已有基础设施。部署前至少需要：

1. 构建并推送前后端镜像；
2. 准备外部 MySQL、Flink、Doris 和共享 Paimon Warehouse；
3. 把 `values.yaml` 中的示例密码迁移到 Kubernetes Secret；
4. 补齐前端 Service／Deployment 及所需依赖组件；
5. 根据实际 Ingress Controller 校准 `/api/v1` 路由。

## 项目结构

```text
rt-dwh-mgmt/
├── backend/                         # Spring Boot 后端
│   ├── src/main/java/com/rtdwh/
│   │   ├── config/                  # Security、Quartz、Doris Catalog 初始化
│   │   ├── controller/              # REST API
│   │   ├── dto/                     # 请求／响应模型
│   │   ├── entity/                  # JPA 实体
│   │   ├── job/                     # 定时监控任务
│   │   ├── repository/              # Spring Data Repository
│   │   ├── security/                # JWT 与用户认证
│   │   └── service/                 # Flink、Paimon、Doris 与业务服务
│   ├── src/test/java/               # 后端测试
│   ├── Dockerfile
│   └── pom.xml
├── frontend/                        # Umi Max + Ant Design Pro
│   ├── src/api/                     # API 客户端
│   ├── src/pages/                   # 页面模块
│   ├── src/app.tsx                  # 全局请求与运行时配置
│   ├── .umirc.ts                    # 路由与布局
│   └── package.json
├── deploy/
│   ├── docker-compose.yml           # 本地完整环境
│   ├── .env.example                 # 环境变量模板
│   ├── flink/Dockerfile             # Flink 2.2 + CDC + Paimon 镜像
│   ├── helm/rtdwh-mgmt/             # Kubernetes 集成骨架
│   ├── nginx.conf
│   └── prometheus.yml
├── scripts/
│   ├── init-mysql.sql               # 管理库、Catalog 与初始化数据
│   └── setup-flink-sql-gateway.sh   # Connector 准备脚本
└── docs/
    ├── api/                          # API 设计
    ├── er-diagram/                   # ER 图
    ├── page-prototype/               # 页面原型
    └── diagrams/                     # 架构与工作流图
```

## 文档与图示

- [菜单调整与数据质量能力规划](docs/data-quality-capability-plan.md)

| 内容 | 文件 |
|---|---|
| 当前产品能力、成熟度与后续重点 | [产品能力全景](docs/product-capability-map.md) |
| 未完成能力、优化任务与实施顺序 | [剩余开发计划](docs/v2.0-remaining-development-plan.md) |
| 产品设计与技术方案 | [`docs/product-design-and-technical-solution.md`](docs/product-design-and-technical-solution.md) |
| Paimon＋Flink＋Doris 核心架构 | [`docs/diagrams/paimon-flink-doris-lightweight-architecture.svg`](docs/diagrams/paimon-flink-doris-lightweight-architecture.svg) |
| 系统能力总览 | [`docs/diagrams/arch-part1-capability.svg`](docs/diagrams/arch-part1-capability.svg) |
| 技术架构详图 | [`docs/diagrams/arch-part2-technical.svg`](docs/diagrams/arch-part2-technical.svg) |
| 部署拓扑 | [`docs/diagrams/arch-part3-deployment.svg`](docs/diagrams/arch-part3-deployment.svg) |
| 运维一日工作流 | [`docs/diagrams/daily-workflow.svg`](docs/diagrams/daily-workflow.svg) |
| API 设计 | [`docs/api/api-design.html`](docs/api/api-design.html) |
| ER 图 | [`docs/er-diagram/er-diagram.html`](docs/er-diagram/er-diagram.html) |
| 页面原型 | [`docs/page-prototype/page-prototype.html`](docs/page-prototype/page-prototype.html) |
| 平台建设与运行说明 | [`docs/platform-construction.md`](docs/platform-construction.md) |
| 未完成能力与产品路线图 | [`docs/product-roadmap.md`](docs/product-roadmap.md) |

## 常见问题

### 登录提示“用户不存在：admin”

在 `deploy/.env` 中设置 `INIT_USERS_ENABLED=true` 以及三个初始化密码，重启后端；用户创建成功后再关闭该开关。

### 后端提示 Doris Catalog 初始化延迟

依次确认：

1. Doris FE `9030` 可连接；
2. 至少有一个存活的 Doris BE；
3. Doris 查询账号有 Catalog 权限；
4. Paimon JDBC 用户和密码正确；
5. Doris BE 能读取 `/data/paimon` 或生产共享 Warehouse；
6. JDBC Driver URL 可访问。

后端会保持控制面可用，依赖健康页面会显示具体错误。

### 即席查询看不到 Database 或 Table

确认 `DORIS_CATALOG` 已创建、`DORIS_DATABASE` 存在，并在系统设置中执行 Doris 连接测试。Catalog 树直接来自 Doris，不读取 RT-DWH 管理库中的表副本。

### Flink 提交任务时报 Connector 不存在

确认 `deploy/flink/Dockerfile` 中的 Flink、CDC、Paimon Connector 版本一致。默认镜像已同时包含 MySQL 与 PostgreSQL CDC Connector；修改版本后需重新构建 Flink 镜像。

### 查询状态与 Flink UI 不一致

状态监控默认每 30 秒轮询 Flink。`NOT_FOUND` 会校准为已终止，`SUSPENDED` 会校准为已暂停；集群暂时不可达时保留当前状态，避免把网络故障误判为任务结束。

## License

MIT

- [普通 Doris View 发布契约与本地验收](docs/managed-view-contract.md)
