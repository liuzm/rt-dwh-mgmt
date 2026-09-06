# 普通 Doris View 发布契约（V2-05 首个子包）

更新：2026-09-05。运行基线保持 Doris 4.1.3、Paimon 2.0.0、Flink 2.2.1。

## 发布与资产

普通 View 在 `internal.rtdwh_views` 中创建，SELECT 可引用平台 Paimon Catalog 或已发布的托管 View。View 不存储查询结果；用途与 SQL 形式参见 [Doris CREATE VIEW](https://doris.apache.org/docs/4.x/sql-manual/sql-statements/table-and-view/view/CREATE-VIEW/)。本地 `SHOW FRONTENDS` 报告 `doris-4.1.3-rc02-7126cf65d96`，已实际验证跨 Catalog 创建、ALTER VIEW、嵌套查询、报表及数据服务调用。MySQL 协议 `SELECT VERSION()` 返回兼容版本，不能作为 Doris 产品版本证据。

- V26 扩展原资产目录：`catalog_name + paimon_db + paimon_table` 构成物理地址唯一键，沿用 UUID 路由；`paimon_db/paimon_table` 是兼容字段名，也承载 View 的库名和名称。
- Paimon 的原有按库表查找只匹配 Paimon 类型。元数据同步不会把 internal View 标记为 Paimon 表。View 没有 Paimon 快照、文件、Compact 或过期清理操作。
- `managed_view` 保存草稿、当前发布指针、待处理版本、操作状态和乐观锁版本。`managed_view_version` 冻结 SQL、直接依赖、依赖字段与输出列，记录发布人、时间、DDL 状态与核验后的 Doris 定义。
- 新建只保存草稿；发布前校验完整 SQL、依赖登记状态、当前权限及 Doris 可见字段。发布时重新校验，不能通过过期的编辑版本覆盖别人修改。
- 首次 CREATE 不使用 IF NOT EXISTS 或替换语义。同名未托管对象拒绝覆盖。后续 ALTER 必须与上次保存的 `SHOW CREATE TABLE` View 定义完全一致。
- 发布意图在独立事务提交后才执行 DDL；成功回包后再次读取 View 和输出列，核验通过才切换发布指针。DDL 异常或回包不明记为 `unknown`；进程中断可能留下 `applying`。两者均阻止查询、修改和重发，不能当成已失败后直接重试。
- 比较 JSON 契约使用结构内容，兼容 MySQL 对 JSON 空格和键顺序的规范化。历史 SQL、依赖和输出字段不被草稿修改。

当前安全边界：已发布 View 的**输出列及直接依赖集合固定**。保持这些契约的 SQL 调整可发布新版本；改变列名、类型、可空性、列顺序或依赖集合时，预览明确标记不可发布，发布接口再次拒绝。需要使用新 View 名称迁移消费者。输出契约变更的完整影响评估和受控迁移尚待后续子包。

## SQL 与权限

View 定义支持经 MySQL AST 完整解析的单条 SELECT 子集，包括关联、聚合、子查询、UNION；依赖必须写为三段标识符。当前不支持定义内 CTE、表函数、动态表来源、锁定查询、SELECT INTO、多个语句或 DDL/DML。标识符限字母、数字、下划线。不能把该解析器用于任意 Doris 或 Flink 方言认证。

即席 SELECT/EXPLAIN、报表定义及执行、数据服务定义/发布和调用走独立的 Doris 校验入口：

1. 核验顶层对象的数据范围。
2. 识别实际 Doris 对象；未托管 View 或托管命名空间内的未登记对象拒绝查询。
3. 根据冻结定义递归核验每层 View 和基础表权限，同时检查发布状态、SQL/依赖记录一致性、引擎定义和依赖字段。
4. 缺失定义、循环、超过 32 层或一次展开超过 256 个节点、字段变化、外部替换或无法完整解析时拒绝；每个 View 的直接依赖最多 64 个。

管理员可访问所有数据范围，但仍须通过 View 定义完整性和有效性核验。撤销基础表权限后，即使顶层及中间 View 权限仍保留，已有登录令牌的查询、报表读取、数据服务发布和应用调用也会被阻断。应用调用沿用服务创建者的执行身份并重验其权限。

查询工作台仍只读，不开放 CREATE/ALTER/DROP。Flink 任务发布保留原有 SQL/DDL 范围校验入口，不套用 Doris SELECT 子集。

有效性基于**当前 Doris 可见元数据**，受外部 Catalog 刷新延迟影响。管理平台不能拦截 DBA 直接修改 Doris 与检查后执行之间的外部竞态。生产部署须将 `internal.rtdwh_views` 作为平台管理的命名空间。当前没有周期失效扫描、物理数据库切换认证或模糊 DDL 结果的自动恢复/人工接管页面；管理员应保留未知记录核对，禁止直接清空状态后重发。

## 接口与页面

均位于现有认证体系内。读取需 `dwh:view`，写操作需 `dwh:manage`，同时校验具体资产和依赖范围。

| 接口 | 作用 |
| --- | --- |
| POST `/dwh/views` | `{name, sql, description}` 创建草稿 |
| GET `/dwh/views/{assetId}` | 草稿、发布指针、冻结版本历史 |
| PUT `/dwh/views/{assetId}` | `{sql, description, expectedVersion}` 更新草稿 |
| POST `/dwh/views/{assetId}/preview` | Doris 字段、直接依赖、发布可行性 |
| POST `/dwh/views/{assetId}/publish` | `{expectedVersion}` 再次校验并执行受控 DDL |
| GET `/dwh/views/{assetId}/health` | 递归核验当前有效性 |

入口：资产目录 → 新建 View → 保存草稿 → 校验并预览发布 → 发布到 Doris。详情提供版本查看、当前有效性、下游使用和查询 View。查询入口生成三段 SELECT，保留 Paimon 默认会话库。资产关系追加已发布 View 的直接上游/下游证据；公共检索按实际 Catalog 过滤。旧血缘图仍仅处理 Paimon 表及其持久化边，不能把 internal View 按 Paimon 范围展示；传递影响图尚未提供。查询编辑器原 Catalog 树仍以平台 Paimon Catalog 为主，View 通过资产目录进入或直接写三段名称。

## 本地复现

先用当前 Docker Compose 启动环境，并通过安全环境变量提供 `RTDWH_ADMIN_PASSWORD`，不要把密码写入命令历史。

```sh
python3 scripts/smoke-managed-views.py --keep
# 页面验收后清理该文件记录的精确测试对象
python3 scripts/smoke-managed-views.py --cleanup
```

脚本使用有限 VALUES 写入两个专用 Paimon 表，创建两个只获精确样例范围的角色/用户、View、嵌套 View、报表和数据 API，验证草稿隔离、版本不可变、兼容发布、契约变更拒绝、递归撤权、依赖临时缺失、未托管 View 和工作台 DDL 拦截。遇到失败将对象 ID 写入忽略目录的 0600 状态文件供精确清理；不保存密码、令牌或应用密钥。测试用户的查询记录因外键一并清理，平台正常用户的历史不受影响。

角色范围部分撤销的真实验收覆盖了旧数据范围删除与新范围插入顺序：先 flush 删除，再插入保留模式，避免唯一键冲突，整个修改仍为一个事务。

2026-09-05 验收结果：最终部署版本的上述脚本全部通过并完成清理；浏览器创建草稿、发布、版本查看、依赖关系、真实查询及返回页签验证通过。后端当前源文件对应的 service/controller 测试共 210 项，失败/错误/跳过均为 0；TypeScript 检查及前端生产构建通过。
