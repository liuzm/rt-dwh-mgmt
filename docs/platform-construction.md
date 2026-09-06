# 平台建设与运行说明

本轮建设按“先稳定、再治理、后编排”的顺序完成，避免在运行状态和权限边界不可靠时继续堆叠功能。

产品定位、用户场景、领域对象、总体架构和关键技术决策统一维护在 [产品设计与技术方案](product-design-and-technical-solution.md)；本文只记录已经落地的建设结果、运行约定和上线检查。

## 已落地能力

1. **运行稳定性**：Flink 活跃任务定时校准；系统健康状态持久化；质量规则由 Doris 执行并保留批次记录。
2. **真实治理数据**：血缘来自数据源、任务表映射、SQL 解析及已持久化血缘，不再生成演示节点。
3. **安全与审计**：控制器使用资源权限码保护；内置角色自动绑定权限；非 GET 操作写入操作审计。
4. **数据库演进**：Flyway 管理质量、审计、编排和治理字段迁移，已有数据库以版本 0 建立基线。
5. **任务编排**：支持 DAG 依赖、环检测、版本快照、draft 回滚、补数实例和依赖释放。
6. **元数据与查询治理**：表责任人、业务域、标签、敏感级别、生命周期；查询并发配额、成功率和 P95。
7. **公共能力治理中心**：统一检索数据表、任务、报表和接口；汇总权限、质量与 SLA、组件告警及审计变更健康分。

## 编排运行约定

- `cdc_sync` 是持续运行的实时任务，不创建按日期补数实例。
- `etl` 和 `materialized` 任务可按日期范围创建补数实例。
- 没有上游的实例直接进入 `queued`；有上游的实例先进入 `waiting`。
- 定时任务按同一业务日期检查所有上游是否存在 `success` 实例，满足后转为 `queued`。
- 外部执行器通过 `POST /api/v1/workflow/instances/claim?executorId=...` 原子领取一个实例，执行后调用 `POST /api/v1/workflow/instances/{id}/complete` 回写结果。
- 当前控制面不假定具体 ETL 执行引擎；可以接 Flink SQL、Doris SQL 或独立 Spark／Python Runner。

## 主要接口

| 能力 | 接口 |
|---|---|
| DAG 图 | `GET /api/v1/workflow/graph` |
| 添加／删除依赖 | `POST／DELETE /api/v1/workflow/dependencies` |
| 发布／查看／回滚版本 | `/api/v1/workflow/tasks/{taskId}/...` |
| 创建补数 | `POST /api/v1/workflow/tasks/{taskId}/backfill` |
| 查询／领取／完成实例 | `/api/v1/workflow/instances/...` |
| 质量执行记录 | `GET /api/v1/quality/runs` |
| 真实血缘 | `GET /api/v1/lineage/graph` |
| 查询治理统计 | `GET /api/v1/query/governance/stats` |
| 写操作审计 | `GET /api/v1/audit` |
| 公共能力总览 | `GET /api/v1/foundation/summary` |
| 公共资源检索 | `GET /api/v1/foundation/search?keyword=...` |
| 数据资源 SLA 风险 | `GET /api/v1/foundation/sla-risks` |

## 上线检查

1. 备份 `rtdwh_mgmt`，确认 Flyway 校验通过且版本到达当前发布版本。
2. 设置稳定的 `JWT_SECRET`、`ENCRYPTION_KEY` 和非空数据库密码。
3. 使用独立 Doris 查询账号，并配置 Workload Group、内存与用户并发上限。
4. 确认 Flink、Doris 所有节点都能访问同一个 Paimon Warehouse。
5. 检查管理员、开发者和访客账户的接口权限，确认写操作能产生审计记录。
6. 为 ETL 执行器分配稳定的 `executorId`，并监控 `waiting`、`queued`、`failed` 实例积压。
7. 在生产启用前执行 `mvn test`、`npm run build` 和 Compose 配置检查。

## 下一阶段建议

- 接入实际 ETL Runner，补充实例超时、重试退避、幂等键和失败转人工机制。
- 增加列级敏感标记、脱敏策略及 Doris 行列权限映射。
- 从 Doris Profile／审计日志采集扫描行数、扫描字节和资源组排队耗时。
- 增加统一指标口径、审批流和 SLA 值班／趋势看板；当前治理中心已提供数据资源 SLA 风险清单。
