# RT-DWH Management Platform

实时数仓管理平台 — 面向中小公司的 Flink CDC + Paimon 湖仓一体化运维方案。

## 项目定位

一个跑起来就能用的实时数仓管理系统。不需要大数据团队，不需要专职运维，一个工程师就能搞定：

- **数据怎么进来** — CDC 实时同步 MySQL/PostgreSQL 到 Paimon
- **数据怎么管** — ODS/DWD/DWS/ADS 分层元数据自动同步、表维护（Compact / Expire）
- **数据怎么查** — 即席查询 + 报表看板，分析师自助取数
- **数据怎么保证不出事** — 质量规则引擎 + 多通道告警（钉钉 / 企微 / 邛件）
- **任务怎么管** — Flink Job 全生命周期管理，提交/暂停/恢复/停止，状态实时监控

## 技术栈

| 层 | 选型 | 版本 |
|---|------|------|
| 后端 | Spring Boot + JPA + Security + Quartz | 3.3 / Java 17 |
| 前端 | Umi 4 + Ant Design Pro v6 + ProComponents | @umijs/max 4.4 |
| 实时引擎 | Apache Flink (CDC) | 1.19+ |
| 湖仓 | Apache Paimon | latest |
| 元数据库 | MySQL 8.0 + Druid 连接池 | 8.0 / 1.2.23 |
| 部署 | Docker Compose + K8s Helm Chart | Compose v3.8 |
| 监控 | Prometheus + Grafana (optional) | 2.52 / 11.0 |

## 项目结构

```
rt-dwh-mgmt/
├── backend/                    # Spring Boot 后端
│   ├── src/main/java/com/rtdwh/
│   │   ├── config/             # Security, Quartz, JWT, DataInitializer
│   │   ├── controller/         # 10 REST Controllers
│   │   ├── dto/                # Request/Response DTOs
│   │   ├── entity/             # 15 JPA Entities
│   │   ├── exception/          # GlobalExceptionHandler
│   │   ├── job/                # Quartz 定时任务 (Flink 状态监控)
│   │   ├── repository/         # 15 Spring Data Repositories
│   │   ├── security/           # JWT Filter + UserDetailsService
│   │   ├── service/            # 14 业务 Services
│   │   └── util/               # 加密/安全上下文工具
│   ├── src/test/java/          # 4 单元测试类
│   ├── Dockerfile
│   └── pom.xml
├── frontend/                   # Ant Design Pro 前端
│   ├── src/
│   │   ├── api/                # API 定义
│   │   ├── pages/              # 16 页面组件
│   │   │   ├── Dashboard/      # 数据总览
│   │   │   ├── SyncTask/       # 同步任务 (List/Create/Detail)
│   │   │   ├── Datasource/     # 数据源配置
│   │   │   ├── DwhTable/       # 表管理 (List/Detail)
│   │   │   ├── Lineage/        # 数据血缘
│   │   │   ├── Maintenance/    # 表维护
│   │   │   ├── Quality/        # 质量监控
│   │   │   ├── AdhocQuery/     # 即席查询
│   │   │   ├── Report/         # 报表看板
│   │   │   ├── Alert/          # 告警管理
│   │   │   ├── Settings/       # 系统设置
│   │   │   └── User/Login      # 登录
│   │   ├── access.ts           # 权限控制
│   │   ├── app.tsx             # 运行时配置 (全局拦截器)
│   │   └── locales/            # 国际化 (zh-CN / en-US)
│   ├── .umirc.ts               # Umi 路由 + Layout 配置
│   ├── Dockerfile
│   └── package.json
├── deploy/                     # 部署配置
│   ├── docker-compose.yml      # Docker Compose (7 容器)
│   ├── nginx.conf              # Nginx 反代配置
│   ├── prometheus.yml          # Prometheus 数据采集
│   ├── helm/rtdwh-mgmt/        # K8s Helm Chart
│   └── .env.example            # 环境变量模板
├── scripts/
│   └── init-mysql.sql          # MySQL 初始化脚本
└── docs/
    ├── api/                    # API 设计文档
    ├── er-diagram/             # ER 图
    ├── page-prototype/         # 页面原型
    └── diagrams/               # 架构图 + 工作流图
        ├── arch-part1-capability.svg    # 系统能力总览
        ├── arch-part2-technical.svg    # 技术架构详图
        ├── arch-part3-deployment.svg   # 部署拓扑图
        └── daily-workflow.svg          # 运维一日工作流
```

## 核心功能

### 同步任务管理
- 多数据源支持（MySQL / PostgreSQL）
- CDC SQL 自动生成 + 表映射配置
- Flink Job 提交 / 停止 / 暂停 / 恢复 / Savepoint
- 实时状态监控（Checkpoint、Lag、背压指标）

### 数仓元数据管理
- Paimon 表元数据自动同步（JDBC Metastore）
- ODS/DWD/DWS/ADS 分层标注
- 数据血缘追踪
- 表维护操作（Compact / Expire Snapshots / Orphan Files 清理）

### 数据质量引擎
- 规则模板：空值率、唯一性、范围、行数、自定义 SQL
- 自动执行检查 → 生成报告 → 超阈值触发告警
- 按分层 / 规则类型 / 启用状态筛选

### 告警通知
- 钉钉 / 企业微信 Webhook + 邛件通知
- 任务失败即时告警，质量超标规则触发
- 告警确认 / 解决状态管理

### 即席查询 & 报表
- SQL 编辑器 + 执行历史
- 查询结果导出 Excel
- 报表模板 + 定时刷新看板

### 系统运维
- 健康检查（Paimon Metastore 连通性 + DataSource 连通性）
- 系统配置管理（Flink URL、告警通道等）
- JWT 鉴权 + RBAC 权限（ADMIN / OPERATOR / VIEWER）

## 快速开始

### 前置条件
- Java 17+
- Node.js 18+ / npm
- Docker & Docker Compose
- MySQL 8.0（或使用 Docker 容器）
- Flink 1.19+ 集群（或使用 Docker 容器）

### Docker Compose 一键启动

```bash
# 1. 克隆项目
git clone <repo-url> rt-dwh-mgmt
cd rt-dwh-mgmt

# 2. 配置环境变量
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，填入密码、Webhook 地址等

# 3. 启动全部服务
cd deploy
docker-compose up -d

# 4. 访问
# 前端: http://localhost
# 后端 API: http://localhost/api
# Flink UI: http://localhost:8081
```

### 本地开发

```bash
# 后端
cd backend
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev
```

后端默认端口 `8080`，前端 dev server `8000`，Nginx 反代会将前端请求转发到后端。

### K8s 部署

```bash
cd deploy/helm
helm install rtdwh-mgmt ./rtdwh-mgmt -n rtdwh --create-namespace
```

## 前端全局拦截器说明

后端所有接口返回统一格式 `ApiResponse { code: 0, data: ..., message: "success" }`。

前端 `app.tsx` 配置了全局 `responseInterceptors`，自动解包 `ApiResponse.data`：

- `code === 0` → 返回 `data` 字段，前端直接拿到业务数据
- `code !== 0` → 抛出 Error，全局错误处理

分页接口返回 Spring Data `Page` 结构：`{ content: [...], totalElements, number, size }`，前端用 `data.content` 作为 Table dataSource。

## 环境变量

| 变量 | 说明 | 默认值 |
|-----|------|-------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | 无（必须设置） |
| `MYSQL_USER` / `MYSQL_PASSWORD` | 业务库用户 | rtdwh_admin |
| `DB_PASSWORD` / `DB_USERNAME` | 后端连接 MySQL | 同上 |
| `FLINK_REST_URL` | Flink JobManager REST 地址 | http://flink-jobmanager:8081 |
| `JWT_SECRET` | JWT 签名密钥 | 无（必须设置） |
| `ENCRYPTION_KEY` | 数据源密码加密密钥 | 无（必须设置） |
| `DINGTALK_WEBHOOK` | 钉钉告警 Webhook | 可选 |
| `WECOM_WEBHOOK` | 企微告警 Webhook | 可选 |
| `ALERT_EMAIL_RECIPIENTS` | 告警邛件接收人 | 可选 |
| `INIT_USERS_ENABLED` | 启用首次用户初始化 | `false` |
| `INIT_ADMIN_PASSWORD` | 首次创建 admin 的密码 | 启用初始化时必填 |
| `INIT_DEV_PASSWORD` / `INIT_GUEST_PASSWORD` | 首次创建 dev01/guest 的密码 | 启用初始化时必填 |

首次部署如果登录提示“用户不存在: admin”，请在 `.env` 中设置 `INIT_USERS_ENABLED=true` 及三个初始化密码，启动一次后再将开关恢复为 `false`。初始化只会创建不存在的用户，不会覆盖已有用户密码。

## 测试

```bash
# 后端单元测试
cd backend
mvn test

# 前端类型检查
cd frontend
npx tsc --noEmit
```

## 架构文档

项目附带 3 篇架构文章 配套架构图：

| 文章 | 配图 |
|------|------|
| 上篇：系统能力总览 | `docs/diagrams/arch-part1-capability.svg` |
| 中篇：技术架构详图 | `docs/diagrams/arch-part2-technical.svg` |
| 下篇：部署拓扑 & 生产 Checklist | `docs/diagrams/arch-part3-deployment.svg` |

运维一日工作流逻辑图：`docs/diagrams/daily-workflow.svg`

## License

MIT
