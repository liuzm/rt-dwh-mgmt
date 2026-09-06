# 本地 Docker 开发环境

本机已启动并完成真实数据链路验证，保留现有 MySQL／Paimon 数据卷和独立的 mysql8、Redis 容器。

## 访问

- 管理平台：http://localhost:18080
- 后端健康检查：http://localhost:8080/actuator/health
- Flink：http://localhost:8081
- Doris FE：http://localhost:8030
- 项目 MySQL：127.0.0.1:13306（避免与已有 3306 冲突）

登录使用本地已有管理员账号；初始化凭证配置在被 Git 忽略的 `deploy/.env`，不要提交此文件。

## 构建并启动

本机需要 Java 17、Maven、Node/npm 和运行中的 Docker Desktop；前端依赖应已安装。仓库根目录执行：

```sh
./scripts/start-local.sh
```

该脚本构建后端和前端，再使用主 Compose 与本地覆盖配置启动。本地镜像复用 Java 17 和 nginx 基础镜像；Flink 镜像下载固定版本连接器，首次构建需要网络。

```sh
docker compose --env-file deploy/.env -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml ps
docker compose --env-file deploy/.env -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml logs --tail=100 rtdwh-backend
```

本地覆盖将 Doris、Flink 和后端的内存限制调整为适合当前 8 GB Docker 环境的配置，关闭未配置的邮件健康探针。此配置用于开发验收，不作为生产容量建议。

## 联调

`scripts/smoke-cdc-paimon-doris.sh` 验证源表增量写入、Flink CDC、Paimon 提交及 Doris 查询。通过环境变量设置 `RTDWH_ADMIN_PASSWORD`；可增加 `SMOKE_VERIFY_RELEASE=true` 和 `SMOKE_VERIFY_MAINTENANCE=true` 验证冻结版本及 Compact 终态。脚本会创建专用测试源表／目标表并清理临时平台任务与数据源，测试表数据保留便于排查。

现有数据库升级前备份：`tmp/backups/pre-v2-local.sql`（包含业务数据，已被 Git 忽略）。不要使用 `docker compose down -v`，这会删除持久卷。

## 发布契约与权限回归

提供 `RTDWH_ADMIN_PASSWORD` 后，可运行：

```sh
python3 scripts/smoke-release-contract.py
python3 scripts/smoke-control-plane.py --gateway-fault
```

第二个脚本仅面向本地 Docker：创建专用测试用户、角色和元数据，短暂暂停 SQL Gateway，验证断连后协调，并通过修改单条测试维护记录的开始时间覆盖超时路径；退出时恢复 Gateway 并清理测试对象。不会删除 Warehouse 数据。

CDC 脚本增加 `SMOKE_VERIFY_RECOVERY=true` 可验证暂停生成 Savepoint、发布新版本后仍从原部署版本恢复，以及恢复后的增量数据可查询。测试过程中会执行专用任务的真实停止／恢复。

## 工作流交付回归（V24）

`scripts/smoke-workflow-delivery.py` 使用专用执行器模拟引擎回调，验证并发领取／完成、精确依赖绑定、冻结门禁重检、补数批次策略和 attempt 隔离。它不是实际引擎写入的证明。先提供 `RTDWH_ADMIN_PASSWORD`，再执行：

```sh
WORKFLOW_RUNNER_ENABLED=false docker compose --env-file deploy/.env -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml up -d --no-deps rtdwh-backend rtdwh-nginx
# 等待 /actuator/health 返回 UP
python3 scripts/smoke-workflow-delivery.py --docker-cleanup
docker compose --env-file deploy/.env -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml up -d --no-deps rtdwh-backend rtdwh-nginx
# 再次等待健康检查通过，验证恢复后的真实 Flink 执行
python3 scripts/smoke-release-contract.py
```

回归时交付协调器和依赖协调器保持启用。即使测试失败，也应执行恢复命令；恢复后的执行器启用状态取决于 `deploy/.env` 的正常配置。

默认清理专用任务；`--docker-cleanup` 额外清理本地模拟产出的资产元数据，不删除物理表或 Warehouse 文件。使用 `--keep` 可保留任务供 UI 检查，之后执行 `python3 scripts/smoke-workflow-delivery.py --cleanup --docker-cleanup`。状态文件默认位于 `/tmp/rtdwh-delivery-fixtures.json`，不含凭证。

本地 V24 升级前备份：`tmp/backups/pre-v24-local.sql`。执行器接入及兼容边界见 [周期执行与交付契约](workflow-execution-contract.md)。

## 资产与 Schema 回归（V25）

已在本地管理库应用 V25；升级前备份为 `tmp/backups/pre-v25-local.sql`。资产及字段身份、兼容性分级、真实分层加工和报表样例见 [资产契约](asset-contract.md)。

```sh
python3 scripts/smoke-asset-contract.py
# 检查页面时可暂留样例，然后按精确测试记录清理
python3 scripts/smoke-asset-contract.py --keep
python3 scripts/smoke-asset-contract.py --cleanup
```

运行需配置 `RTDWH_ADMIN_PASSWORD`，并启用本地内置工作流执行器。扩展后的 `scripts/smoke-control-plane.py` 会创建两个仅访问各自测试表的临时角色和用户，覆盖新资产 UUID 详情、关系及 Schema 历史接口的越权与撤权检查，完成后清理。2026-09-05 经用户明确授权，完整回归已通过，临时对象清理及服务健康检查均已确认。


## V26 普通 View

本地已应用 V26，升级前管理库备份为 `tmp/backups/pre-v26-local.sql`（0600，已忽略）。页面入口为“数仓管理 → 资产目录 → 新建 View”。View 保存到 Doris `internal.rtdwh_views`；首次创建草稿时初始化该数据库。普通 View 不执行 Paimon 表维护。

接口、SQL 支持范围和待核对状态说明见 [普通 View 发布契约](managed-view-contract.md)。通过安全环境变量提供管理员密码后运行 `python3 scripts/smoke-managed-views.py --keep`；页面核验后运行相同脚本的 `--cleanup`。测试对象 ID 记录在忽略目录 `tmp/view-smoke-state.json`，不记录凭证。


## 窗口质量验收（V27）

能力说明见 [数据质量规划](data-quality-capability-plan.md)。最新管理库迁移为 V27，迁移前备份 `tmp/backups/pre-v27-local.sql`（0600）。

使用已有 `RTDWH_ADMIN_PASSWORD` 环境变量运行 `python3 scripts/smoke-quality-windows.py`。脚本创建独立 Paimon 表、真实有界写入任务、质量规则、托管 View 与两个范围用户，验证窗口与门禁后清理；`--keep` 保留 UI 验收样例，`--cleanup` 按 `tmp/quality-smoke-state.json` 清理。状态文件不保存凭证，失败时保留清单以便清理。

手动规则默认选择前一日，可指定其他日期；定时业务日期使用 `QUALITY_SCHEDULE_TIMEZONE`（默认 Asia/Shanghai），避免容器 UTC 与业务日期错位。
