# 资产身份与 Schema 观测契约

V2-04 在现有 Paimon 资产目录上增加稳定身份、结构修订和生产消费入口。部署仍对应一套平台 Catalog／Warehouse；普通 View 的定义与发布属于 V2-05。

## 身份与发现

- `assetId` 是平台逻辑表登记的 UUID，旧数字表 ID 和 URL 继续可用。物理定位保存 Catalog、Database、Table；新产出声明及 production 保存对应的 assetId。
- `unverified` 表示只有登记／声明，尚无当前物理观测；`observed` 表示本轮 Catalog 发现；`missing` 表示曾发现的表当前不在完整 Catalog 列表中。暂时缺失保留身份、字段注释和 Schema 修订，不删除历史。
- Schema 单独标记 `unknown`、`observed`、`stale`。读不到 Warehouse Schema 时保留原字段，不把旧字段冒充新观测；Catalog 查询失败不推断表已删除。
- 主键表与追加表由实际 Schema 主键判定。主键表的当前状态和快照不等于永久变更日志；表类型也不证明保留期限。
- **身份边界**：当前 UUID 标识逻辑表登记，不是引擎物理对象的 incarnation ID。同名表重新出现会复用登记；跨表重命名不自动合并两个登记，删除后同名重建也尚不能自动证明物理连续性。后续显式重绑定流程应处理这一点。

## 字段与结构修订

同步读取 Paimon 的引擎字段 ID。字段重命名且 ID 连续时，平台字段 ID、业务注释与源字段关联保持不变。同名但引擎字段 ID 不同视为替换，不继承旧注释；旧元数据尚无引擎 ID 时，可先按名称接入。

首次完整读取产生 baseline，不推断部署前的历史。标准化字段结构、主键和分区键形成 SHA-256 指纹；结构未变不新增修订，业务注释不触发 Schema 变更。每次变化记录前后结构、差异、分级、来源和观测时间，最多返回最近 100 次。

| 分级 | 当前规则 |
| --- | --- |
| compatible | 新增可空字段；整数或 VARCHAR 扩宽等已识别类型变化 |
| risk | 默认值变化、放宽可空性、未知／嵌套类型变化、字段顺序或引擎标识变化 |
| breaking | 删除／重命名字段、类型缩窄、新增非空字段、收紧可空性、主键或分区键变化 |

这些等级是观测提示，不是完整兼容性证明，也不会执行、阻止或撤销引擎 DDL。用户需结合生产消费关系评估变更。物理表重建、列级血缘、多副本同步冲突恢复、复杂类型的精细兼容矩阵仍需扩展。

## 关系依据与数据权限

新资产详情的关系接口区分以下证据：

- 当前可读发布版本的产出契约、CDC 表映射或 SQL AST；未发布草稿不会替换发布关系。
- 当前可读报表与数据服务定义的 SQL AST；这两类关系不宣称已经实现线上版本隔离。
- 真实任务产出、对应发布版本、实例与质量检测记录；历史未记录的版本／assetId 不做补造。

SQL 解析只覆盖 SELECT、INSERT 和简单 USE 指令；一段 SQL 包含不支持的语句则放弃该段 SQL 的关系推断，不使用正则猜测。发布产出与映射仍能独立提供生产证据。未展开 View，也不保证完整下游影响范围。已有独立血缘图仍使用旧解析路径，不能把新接口的 AST 能力等同于全平台血缘升级。

入口 `/dwh/assets/{assetId}` 及其 `/schema-revisions`、`/context` 子接口均检查该资产的 Catalog 数据范围。任务、报表、服务分别要求对应模块权限和对象权限，历史产出还需通过对应发布版本的可读检查。

## 周期 SQL 的平台会话

内置执行器在通过发布运行配置和当前执行权限校验后，为本次会话初始化 JDBC Paimon Catalog、默认 ODS 数据库及 batch 模式。SQL 内明确的 USE／SET 可继续覆盖会话默认值。凭证在提交时读取配置，仅进入临时提交对象，不写回草稿或发布快照。一个实例仍只能提交一个 Flink Job；未知提交不会自动重放。

## 本地真实样例

脚本 `scripts/smoke-asset-contract.py` 使用专用 `smoke_asset_<时间戳>` 表及任务：

1. 有限 VALUES 输入写入 ODS；DWD 将金额乘 2；ADS 汇总，通过已发布的空值率质量规则。
2. 下游补数按 batch_only 创建三层实例，各数据依赖固定对应产出；三次 Flink 作业成功，交付 available。
3. Doris 报表读到金额 60；资产关系展示发布生产／消费任务、报表／服务入口、上下游资产与质量批次。
4. 修改草稿不改变发布关系；真实 ALTER 增列、字段重命名和删列后，检查分级及字段注释身份；重复同步不生成新修订。
5. 将测试表临时改名，验证原登记 missing 与历史保留；改回原名重新 observed。

此样例以有限输入验证分层加工和消费，并非新增 CDC 认证；MySQL CDC／保存点恢复证据见开发进度。DDL 语法依据 [Paimon 2.0 SQL Alter 文档](https://paimon.apache.org/docs/2.0/flink/sql-alter/)，最终以本地引擎实测为准。

```sh
# 使用当前本地管理员密码；不要把密码写进脚本或版本库
python3 scripts/smoke-asset-contract.py
# 暂留专用对象做界面验收，完成后按记录清理
python3 scripts/smoke-asset-contract.py --keep
python3 scripts/smoke-asset-contract.py --cleanup
```

脚本从 `RTDWH_ADMIN_PASSWORD` 读取登录密码。Gateway 建 Catalog 时仅读取本地后端容器的配置，不打印凭证。专用对象记在忽略目录 `tmp/asset-smoke-state.json`；异常时保留记录供诊断，修复后先执行 `--cleanup`。清理只按记录的测试 ID 和受限名称进行，不删除共用数据库或 Warehouse。

## 浏览器构建验证

本轮从资产进入即席查询时，浏览器暴露 Monaco 0.56 的 `actions_Action2` 未定义错误；修复为当前 Webpack 工具链关闭模块合并，保留原压缩与代码拆分。配置语义见 [Webpack concatenateModules](https://webpack.js.org/configuration/optimization/#optimizationconcatenatemodules)。这是当前工具链的兼容处理，会改变前端产物大小，后续升级打包器时应重新验证是否可移除。

生产构建后须实际打开即席查询，载入资产 SQL、执行并核对结果，再返回资产检查页签；仅 TypeScript 和构建成功不足以证明 Monaco 能运行。
