# 周期执行与数据交付契约

适用：Flyway V24 起创建的周期实例。实现进度见 [2.0 实施进度](v2.0-implementation-progress.md)。

## 执行器协议

所有写入接口需要 `task:manage` 和对应任务的数据权限。接口路径相对于后端根地址；前端代理增加 `/api/v1`。

1. `POST /workflow/instances/claim?executorId=worker&taskId=123`：领取可执行实例。`taskId` 可选；省略时在当前用户可见任务内领取。返回 `activeAttemptId` 和 `attemptCount`，无可领取实例时 data 为 null。
2. 读取 `GET /workflow/instances/{id}/definition`，使用实例固定的版本、参数和业务日期构造执行内容。
3. **在调用引擎前**请求 `POST /workflow/instances/{id}/begin-submission?attemptId=456&executorId=worker`。服务端重验当前执行权限、依赖绑定和提交状态，然后持久化提交意图。重复调用不会被当作新的提交许可。
4. 引擎返回 Job 后，调用 `POST /workflow/instances/{id}/external-job`，JSON 为 `{"attemptId":456,"executorId":"worker","externalJobId":"实际 Job ID"}`。不能替换本 attempt 已绑定的另一 Job。
5. 用 `POST /workflow/instances/{id}/heartbeat?attemptId=456&executorId=worker` 续租。
6. 确认引擎终态后，调用 `POST /workflow/instances/{id}/complete`，JSON 为 `{"attemptId":456,"executorId":"worker","success":true}`；失败时传 `success:false` 和 `errorMessage`。成功回调必须已绑定 Job。

回调必须携带当前 attempt 和领取者身份。新实例拒绝缺失字段、旧 attempt、错误执行器和相反终态；相同 attempt 的重复完成返回当前结果，不再次登记产出。数据库行锁内刷新实体，避免请求内先前读取的缓存状态覆盖并发结果。

外部执行器是受授权的控制面参与者，应如实核对引擎状态。接口中的 executorId 是领取所有权标识，不是独立的认证凭证；HTTP 测试执行器的模拟回调不构成实际引擎执行证据。

## 状态与重试

计算状态沿用 `waiting / queued / running / success / failed / cancelled`；交付单独记录 `pending / checking / available / blocked`。

计算成功后只进入 checking。后台交付协调器独立执行冻结质量规则、登记产出，再设置 available 或 blocked。数据库等暂时故障保留计算成功和 checking，等待后续协调；检测异常或规则失败则记录 blocked 及原因。计算失败不会登记成功产出。

每次领取创建一条 `task_run_attempt`，保留执行器、提交意图时间、Job ID、终态和错误。只有能够证明尚未登记提交意图、也没有 Job ID 的失败尝试，才允许重新入队。自动重试使用有上限的退避，手动重试立即入队；下一次领取产生新 attempt，保留旧记录。

提交超时、回包丢失、Job 查不到或提交后的租约到期，均不能证明没有写入。内置执行器保留 running 和 unknown attempt，禁止自动重放。没有 Job ID 的不明提交也不能直接标为已取消。受授权执行器核对实际 Job 后，可按原 attempt 绑定并继续报告；尚未提供专用人工接管界面或提交后安全重放适配器。

`GET /workflow/instances/{id}/attempts` 返回执行历史。原有运行枚举没有新增 unknown，界面的执行尝试与实例错误显示这种不确定性。

## 窗口和依赖

当前支持按日窗口：`[businessDate, businessDate + 1 日)`。补数请求的 startDate 和 endDate 都是包含的业务日期，每日创建一个实例。窗口用于控制面匹配，不会自动给任意 SQL 注入分区过滤，也不代表固定了 Paimon 物理快照。

新增依赖默认使用 `data_available` 并指定 `outputDatasetId`；该产出必须存在于上游发布契约。显式 `execution_success` 仅要求计算成功。旧 `success` 条件保留兼容语义，要求该实例所有声明产出可用。

| 策略 | 创建与放行规则 |
| --- | --- |
| `batch_only`（补数默认） | 递归创建已发布的上游祖先任务，全部使用同一个批次；按日期绑定本批上游实例，不用其他批次替代。 |
| `reuse_available`（调度默认、补数显式选择） | 只创建目标实例，固定创建时的上游发布版本；选择同版本、同窗口的可用产出，保存具体 productionId。 |

每条绑定持久化上游任务、发布版本、业务窗口、策略、条件，以及实际满足条件时的上游实例、productionId 和时间。已经绑定的生产记录不被后来的新记录静默替换。领取和提交前会重新确认依赖仍满足；提交时再次校验原执行身份对上游版本的访问权限。控制依赖没有数据产出 ID。

指定产出依赖只检查所选产出，其他产出 blocked 不必阻止它；上游仍在 checking 时等待检测完成。若上游新发布版本不再声明所选产出，创建实例会拒绝并要求更新依赖。

补数最多 366 个日期、100 个任务、合计 2000 个实例。`parametersJson` 是目标任务参数；`taskParametersJson` 可按上游任务 ID 提供参数 JSON 字符串，缺省使用各发布版本固定的默认值。必填参数、类型和权限在整批创建时校验，失败则回滚整批。

`GET /workflow/instances/{id}/bindings` 返回当前用户可见的绑定记录。

## 交付重检和兼容

`POST /workflow/instances/{id}/recheck-delivery` 只接受计算成功且 blocked 的实例。重新使用原发布版本的规则检测，复用同一个 productionId，并向 `dataset_production_check` 追加证据。`GET /workflow/productions/{productionId}/checks` 返回检测历史。

重检不会把草稿中的门禁开关用于历史实例，也不会把检测时间当作新的产出时间；较旧实例重检不会覆盖较新的 lastInstanceId 或延长数据新鲜度。单实例产出登记和交付状态在同一事务提交，唯一键及行锁防止重复登记。

历史实例迁移后的交付状态为 unknown，历史产出不伪造窗口、attempt 或版本证据。旧在途实例继续保留兼容路径；新执行开始使用 attempt 协议。新式 reuse_available 不复用缺少窗口／版本证据的历史产出。

V27 已支持显式业务窗口规则，产出检查使用冻结规则和实例窗口；全表规则仍可能检查整表，不能替代窗口完整性证明。详见 [质量检测契约](data-quality-capability-plan.md)。物理快照固定、消费强隔离、SLA 事件、人工豁免和提交后安全重放，属于后续验收边界。blocked 不撤回已经写入引擎的数据。
