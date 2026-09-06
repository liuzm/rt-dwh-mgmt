#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker-compose.yml"
API_BASE="${RTDWH_API_BASE:-http://127.0.0.1:8080}"
ADMIN_USER="${RTDWH_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${RTDWH_ADMIN_PASSWORD:-}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root123123}"
MYSQL_USER="${MYSQL_USER:-rtdwh_admin}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root123123}"
TIMEOUT_SECONDS="${SMOKE_TIMEOUT_SECONDS:-180}"
TARGET_CATALOG="${DORIS_CATALOG:-rtdwh_paimon}"
TARGET_DATABASE="${DORIS_DATABASE:-ods}"
SOURCE_DATABASE="rtdwh_smoke"
SOURCE_TABLE="cdc_events"
TARGET_TABLE="rtdwh_smoke_events"
RUN_ID="$(date +%s)"
PAYLOAD="smoke-${RUN_ID}"

TOKEN=""
SOURCE_ID=""
TASK_ID=""

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

for command in curl jq docker; do
  command -v "$command" >/dev/null 2>&1 || fail "缺少命令: $command"
done

[[ -n "$ADMIN_PASSWORD" ]] || fail "请通过 RTDWH_ADMIN_PASSWORD 提供平台管理员密码"

COMPOSE=(docker compose -f "$COMPOSE_FILE")
if [[ -f "$ROOT_DIR/deploy/.env" ]]; then
  COMPOSE=(docker compose --env-file "$ROOT_DIR/deploy/.env" -f "$COMPOSE_FILE")
fi

api() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local args=(-fsS --max-time 120 -X "$method" "$API_BASE$path")
  [[ -n "$TOKEN" ]] && args+=(-H "Authorization: Bearer $TOKEN")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  curl "${args[@]}"
}

require_success() {
  local response="$1"
  local label="$2"
  local code
  code="$(jq -r '.code // -1' <<<"$response")"
  [[ "$code" == "0" ]] || fail "$label 失败: $(jq -r '.message // . ' <<<"$response")"
}

cleanup() {
  set +e
  if [[ -n "$TASK_ID" && -n "$TOKEN" ]]; then
    api POST "/sync-tasks/$TASK_ID/stop" >/dev/null 2>&1
    api DELETE "/sync-tasks/$TASK_ID" >/dev/null 2>&1
  fi
  if [[ -n "$SOURCE_ID" && -n "$TOKEN" ]]; then
    api DELETE "/datasources/$SOURCE_ID" >/dev/null 2>&1
  fi
}
trap cleanup EXIT

printf '[1/7] 检查平台和基础组件...\n'
curl -fsS --max-time 10 "$API_BASE/actuator/health" >/dev/null
RUNNING_COUNT="$("${COMPOSE[@]}" ps --services --status running \
  mysql flink-jobmanager flink-taskmanager flink-sql-gateway doris-fe doris-be | wc -l | tr -d ' ')"
[[ "$RUNNING_COUNT" == "6" ]] || fail "MySQL、Flink、SQL Gateway 或 Doris 容器未全部运行"

printf '[2/7] 登录管理平台...\n'
LOGIN_BODY="$(jq -nc --arg username "$ADMIN_USER" --arg password "$ADMIN_PASSWORD" \
  '{username:$username,password:$password}')"
LOGIN_RESPONSE="$(api POST /auth/login "$LOGIN_BODY")"
require_success "$LOGIN_RESPONSE" "登录"
TOKEN="$(jq -r '.data.token // empty' <<<"$LOGIN_RESPONSE")"
[[ -n "$TOKEN" ]] || fail "登录响应未返回 token"

printf '[3/7] 准备 MySQL CDC 源表和复制权限...\n'
SOURCE_SQL="CREATE DATABASE IF NOT EXISTS ${SOURCE_DATABASE};
CREATE TABLE IF NOT EXISTS ${SOURCE_DATABASE}.${SOURCE_TABLE} (
  id BIGINT PRIMARY KEY,
  payload VARCHAR(255) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON ${SOURCE_DATABASE}.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;"
"${COMPOSE[@]}" exec -T mysql mysql -uroot "-p${MYSQL_ROOT_PASSWORD}" -e "$SOURCE_SQL"

printf '[4/7] 创建临时业务数据源配置...\n'
SOURCE_BODY="$(jq -nc \
  --arg name "smoke-mysql-${RUN_ID}" --arg database "$SOURCE_DATABASE" \
  --arg username "$MYSQL_USER" --arg password "$MYSQL_PASSWORD" \
  '{configName:$name,dbType:"mysql",host:"mysql",port:3306,database:$database,username:$username,passwordEncrypted:$password,extraParams:"{}"}')"
SOURCE_RESPONSE="$(api POST /datasources "$SOURCE_BODY")"
require_success "$SOURCE_RESPONSE" "创建 MySQL 数据源"
SOURCE_ID="$(jq -r '.data.id' <<<"$SOURCE_RESPONSE")"

printf '[5/7] 创建并启动 CDC 任务...\n'
MAPPINGS="$(jq -nc --arg source "$SOURCE_TABLE" --arg db "$TARGET_DATABASE" --arg target "$TARGET_TABLE" \
  '[{sourceTable:$source,targetDb:$db,targetTable:$target,syncMode:"full+incremental"}]')"
TASK_BODY="$(jq -nc \
  --arg name "smoke-cdc-${RUN_ID}" --argjson sourceId "$SOURCE_ID" \
  --arg mappings "$MAPPINGS" \
  '{taskName:$name,description:"CDC -> Paimon -> Doris smoke test",taskType:"cdc_sync",scenarioCode:"table_realtime_sync",executionMode:"continuous",sourceConfigId:$sourceId,flinkSql:"-- generated when task starts",syncStrategy:"full_then_incremental",tableMappings:$mappings,parallelism:1,checkpointIntervalMs:5000}')"
TASK_RESPONSE="$(api POST /sync-tasks "$TASK_BODY")"
require_success "$TASK_RESPONSE" "创建 CDC 任务"
TASK_ID="$(jq -r '.data.id' <<<"$TASK_RESPONSE")"
if [[ "${SMOKE_VERIFY_RELEASE:-false}" == "true" ]]; then
  RELEASE_RESPONSE="$(api POST "/sync-tasks/$TASK_ID/publish" '{"changeSummary":"2.0 发布契约验收"}')"
  require_success "$RELEASE_RESPONSE" "发布 CDC 版本"
  RELEASE_ID="$(jq -r '.data.id' <<<"$RELEASE_RESPONSE")"
  jq -e '.data.snapshotJson | fromjson | .flinkSql | contains("__RTDWH_SOURCE_CREDENTIAL__")' <<<"$RELEASE_RESPONSE" >/dev/null || fail '发布快照缺少受控凭证引用'
  EDIT_RESPONSE="$(api PUT "/sync-tasks/$TASK_ID" '{"parallelism":2}')"
  require_success "$EDIT_RESPONSE" "修改未发布草稿"
fi
START_RESPONSE="$(api POST "/sync-tasks/$TASK_ID/start")"
require_success "$START_RESPONSE" "启动 CDC 任务"
START_STATUS="$(jq -r '.data.status // empty' <<<"$START_RESPONSE")"
[[ "$START_STATUS" == "running" ]] || fail "CDC 任务未进入 running: $(jq -r '.data.lastErrorMsg // .message' <<<"$START_RESPONSE")"

if [[ "${SMOKE_VERIFY_RELEASE:-false}" == "true" ]]; then
  DEPLOYMENTS="$(api GET "/sync-tasks/$TASK_ID/deployments")"
  jq -e --argjson version "$RELEASE_ID" '.data[0] | .definitionVersionId == $version and .desiredParallelism == 1 and (.flinkJobId | length > 0)' <<<"$DEPLOYMENTS" >/dev/null || fail '部署未固定发布版本或使用了未发布并行度'
  printf 'PASS: 持续部署固定发布版本，未发布草稿不影响执行\n'
fi

printf '[6/7] 写入增量事件并等待 Paimon 提交...\n'
INSERT_SQL="INSERT INTO ${SOURCE_DATABASE}.${SOURCE_TABLE}(id,payload) VALUES (${RUN_ID},'${PAYLOAD}') ON DUPLICATE KEY UPDATE payload=VALUES(payload),updated_at=CURRENT_TIMESTAMP(3);"
"${COMPOSE[@]}" exec -T mysql mysql -u"$MYSQL_USER" "-p${MYSQL_PASSWORD}" -e "$INSERT_SQL"

printf '[7/7] 通过 Doris 查询 Paimon 快照...\n'
DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < DEADLINE )); do
  QUERY_BODY="$(jq -nc \
    --arg sql "SELECT id, payload FROM ${TARGET_TABLE} WHERE id = ${RUN_ID}" \
    --arg catalog "$TARGET_CATALOG" --arg database "$TARGET_DATABASE" \
    '{sql:$sql,maxRows:10,timeoutSeconds:15,catalog:$catalog,database:$database}')"
  QUERY_RESPONSE="$(api POST /query/execute "$QUERY_BODY" || true)"
  if [[ -n "$QUERY_RESPONSE" ]] \
      && [[ "$(jq -r '.code // -1' <<<"$QUERY_RESPONSE" 2>/dev/null)" == "0" ]] \
      && [[ "$(jq -r '.data.status // empty' <<<"$QUERY_RESPONSE")" == "success" ]] \
      && jq -e --arg payload "$PAYLOAD" '.data.rows[]? | .[1] == $payload' <<<"$QUERY_RESPONSE" >/dev/null; then
    printf 'PASS: MySQL CDC -> Paimon -> Doris 链路验证成功，id=%s, payload=%s\n' "$RUN_ID" "$PAYLOAD"
    if [[ "${SMOKE_VERIFY_RECOVERY:-false}" == "true" ]]; then
      ORIGINAL_DEPLOYMENT="$(api GET "/sync-tasks/$TASK_ID/deployments")"
      ORIGINAL_VERSION="$(jq -r '.data[0].definitionVersionId' <<<"$ORIGINAL_DEPLOYMENT")"
      ORIGINAL_JOB="$(jq -r '.data[0].flinkJobId' <<<"$ORIGINAL_DEPLOYMENT")"
      PAUSE_RESPONSE="$(api POST "/sync-tasks/$TASK_ID/pause")"
      require_success "$PAUSE_RESPONSE" "停止并保存状态"
      PAUSE_DEADLINE=$((SECONDS + 120))
      while (( SECONDS < PAUSE_DEADLINE )); do
        CURRENT="$(api GET "/sync-tasks/$TASK_ID")"
        CURRENT_STATUS="$(jq -r '.data.status' <<<"$CURRENT")"
        [[ "$CURRENT_STATUS" != "failed" ]] || fail '保存点生成失败'
        [[ "$CURRENT_STATUS" != "paused" ]] || break
        sleep 2
      done
      [[ "$CURRENT_STATUS" == "paused" ]] || fail '未在时限内取得保存点'
      NEW_RELEASE="$(api POST "/sync-tasks/$TASK_ID/publish" '{"changeSummary":"恢复测试：发布新版本后仍恢复原部署"}')"
      require_success "$NEW_RELEASE" "发布另一版本"
      RESUME_RESPONSE="$(api POST "/sync-tasks/$TASK_ID/resume")"
      require_success "$RESUME_RESPONSE" "从保存点恢复"
      [[ "$(jq -r '.data.status' <<<"$RESUME_RESPONSE")" == "running" ]] || fail '恢复未进入运行状态'
      RECOVERED="$(api GET "/sync-tasks/$TASK_ID/deployments")"
      jq -e --argjson version "$ORIGINAL_VERSION" --arg oldJob "$ORIGINAL_JOB" '.data[0] | .actionType == "resume" and .definitionVersionId == $version and .flinkJobId != $oldJob and (.restorePath | length > 0)' <<<"$RECOVERED" >/dev/null || fail '恢复未绑定原部署版本及保存点'
      RECOVERED_PAYLOAD="recovered-${RUN_ID}"
      "${COMPOSE[@]}" exec -T mysql mysql -u"$MYSQL_USER" "-p${MYSQL_PASSWORD}" -e "UPDATE ${SOURCE_DATABASE}.${SOURCE_TABLE} SET payload='${RECOVERED_PAYLOAD}',updated_at=CURRENT_TIMESTAMP(3) WHERE id=${RUN_ID};"
      RECOVERY_DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
      RECOVERY_VISIBLE=false
      while (( SECONDS < RECOVERY_DEADLINE )); do
        QUERY_RESPONSE="$(api POST /query/execute "$QUERY_BODY" || true)"
        if jq -e --arg payload "$RECOVERED_PAYLOAD" '.data.rows[]? | .[1] == $payload' <<<"$QUERY_RESPONSE" >/dev/null 2>&1; then RECOVERY_VISIBLE=true; break; fi
        sleep 3
      done
      [[ "$RECOVERY_VISIBLE" == true ]] || fail '恢复后增量数据未到达 Doris'
      printf 'PASS: Savepoint 恢复沿用原部署版本，恢复后增量数据可见\n'
    fi
    if [[ "${SMOKE_VERIFY_MAINTENANCE:-false}" == "true" ]]; then
      SYNC_RESPONSE="$(api POST /dwh/sync-metadata)"
      require_success "$SYNC_RESPONSE" "同步测试资产"
      TABLES="$(api GET "/dwh/tables?keyword=${TARGET_TABLE}")"
      TABLE_ID="$(jq -r --arg name "$TARGET_TABLE" '.data[] | select(.paimonTable==$name) | .id' <<<"$TABLES" | head -1)"
      [[ -n "$TABLE_ID" ]] || fail '测试资产未登记'
      MAINTENANCE="$(api POST "/dwh/tables/$TABLE_ID/compact?compactStrategy=minor")"
      require_success "$MAINTENANCE" "提交测试表 Compact"
      OPERATION_ID="$(jq -r '.data.operationId' <<<"$MAINTENANCE")"
      MAINTENANCE_DEADLINE=$((SECONDS + 120))
      while (( SECONDS < MAINTENANCE_DEADLINE )); do
        LOGS="$(api GET "/dwh/maintenance/logs?tableMetaId=$TABLE_ID")"
        OP_STATUS="$(jq -r --arg id "$OPERATION_ID" '.data[] | select((.id|tostring)==$id) | .status' <<<"$LOGS")"
        if [[ "$OP_STATUS" == "success" ]]; then
          printf 'PASS: 测试表 Compact 已确认执行终态\n'
          exit 0
        fi
        [[ "$OP_STATUS" != "failed" ]] || fail '测试表 Compact 执行失败，请检查维护日志'
        sleep 3
      done
      fail '测试表 Compact 未在时限内取得真实终态'
    fi
    exit 0
  fi
  sleep 5
done

fail "等待 ${TIMEOUT_SECONDS}s 后 Doris 仍未查询到增量事件"
