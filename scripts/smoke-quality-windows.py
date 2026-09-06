#!/usr/bin/env python3
"""Local Docker quality windows/evidence/gate regression. Requires RTDWH_ADMIN_PASSWORD.
--keep retains exact fixtures for UI QA; --cleanup removes only the recorded fixtures.
Real bounded Paimon writes and Doris reads; no production data is modified.
"""
import json, os, secrets, subprocess, sys, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError
BASE = 'http://127.0.0.1:8080'
GW = 'http://127.0.0.1:9083'
STATE = Path('tmp/quality-smoke-state.json')
admin = ''
session = None
state = {'tables': [], 'rules': [], 'tasks': [], 'users': [], 'roles': [], 'views': []}


def api(method, path, body=None, token=None, reject=False):
    req = Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                  headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + (admin if token is None else token)})
    try:
        with urlopen(req, timeout=120) as response:
            result = json.load(response)
    except HTTPError as error:
        result = json.load(error)
        if reject and error.code in (400, 403, 409):
            return None
        raise RuntimeError(f'{method} {path}: {error.code} {result.get("message")}') from None
    assert not reject, 'Unexpectedly accepted: ' + path
    assert result.get('code') == 0, result.get('message')
    return result.get('data')


def db(sql):
    return subprocess.run(['docker', 'exec', '-i', 'rtdwh-mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B rtdwh_mgmt'],
        input=sql, text=True, capture_output=True, check=True).stdout.strip()


def doris(sql):
    subprocess.run(['docker', 'exec', '-i', 'rtdwh-mysql', 'mysql', '-hrtdwh-doris-fe', '-P9030', '-uroot'],
                   input=sql, text=True, capture_output=True, check=True)


def gw(method, path, body=None):
    with urlopen(Request(GW + path, method=method, data=None if body is None else json.dumps(body).encode(),
                         headers={'Content-Type': 'application/json'}), timeout=120) as response:
        return json.load(response)


def execute(sql):
    handle = gw('POST', f'/v1/sessions/{session}/statements', {'statement': sql,
                'executionConfig': {'execution.runtime-mode': 'batch', 'parallelism.default': '1'}})['operationHandle']
    deadline = time.time() + 120
    while time.time() < deadline:
        status = gw('GET', f'/v1/sessions/{session}/operations/{handle}/status')['status']
        if status == 'FINISHED':
            return
        assert status not in ('ERROR', 'CANCELED', 'CLOSED'), 'Gateway failed: ' + handle
        time.sleep(.3)
    raise AssertionError('Gateway timeout: ' + handle)


def initialize():
    global session
    config = json.loads(subprocess.check_output(['docker', 'inspect', 'rtdwh-backend'], text=True))[0]['Config']['Env']
    env = dict(item.split('=', 1) for item in config if '=' in item)
    session = gw('POST', '/v1/sessions', {})['sessionHandle']
    lit = lambda value: "'" + value.replace("'", "''") + "'"
    opts = {'type': 'paimon', 'metastore': 'jdbc', 'uri': env['PAIMON_JDBC_URI'],
            'jdbc.user': env['PAIMON_JDBC_USER'], 'jdbc.password': env['PAIMON_JDBC_PASSWORD'],
            'catalog-key': env.get('PAIMON_CATALOG_KEY', 'rtdwh'), 'warehouse': env['PAIMON_WAREHOUSE']}
    execute('CREATE CATALOG rtdwh_paimon WITH (' + ','.join(lit(k) + '=' + lit(v) for k, v in opts.items()) + ')')
    execute('USE CATALOG rtdwh_paimon')


def save():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2)); STATE.chmod(0o600)


def rule(body):
    result = api('POST', '/quality/rules', body)
    state['rules'].append(result['id']); save()
    return result


def check(identifier, date=None, token=None):
    suffix = '' if date is None else '?businessDate=' + date
    summary = api('POST', f'/quality/rules/{identifier}/run' + suffix, token=token)
    run = next(r for r in api('GET', f'/quality/runs?ruleId={identifier}', token=token) if r['batchId'] == summary['batchId'])
    return summary, run


def wait_delivery(task_id, instance_id, expected):
    deadline = time.time() + 180
    while time.time() < deadline:
        item = next(r for r in api('GET', f'/workflow/instances?taskId={task_id}') if r['id'] == instance_id)
        assert item['status'] not in ('failed', 'cancelled'), item.get('errorMessage')
        if item['status'] == 'success' and item.get('deliveryStatus') in ('available', 'blocked'):
            assert item['deliveryStatus'] == expected, item.get('errorMessage')
            return item
        time.sleep(1)
    raise AssertionError('Delivery timeout: ' + str(instance_id))


def cleanup():
    for view in state.get('views', []):
        assert view['name'].startswith('smoke_quality_') and view['name'].replace('_', '').isalnum()
        doris('DROP VIEW IF EXISTS internal.rtdwh_views.' + view['name'])
        vid, aid = int(view['id']), int(view['tableId'])
        db(f'DELETE FROM managed_view_version WHERE view_id={vid}; DELETE FROM managed_view WHERE id={vid}; DELETE FROM dwh_table_meta WHERE id={aid};')
    for task in state['tasks']:
        api('DELETE', '/sync-tasks/' + str(task))
    for identifier in state['rules']:
        # A test intentionally deletes one rule; exact-id database cleanup also removes its retained evidence.
        db(f'DELETE FROM quality_alert WHERE rule_id={int(identifier)}; DELETE FROM quality_check_run WHERE rule_id={int(identifier)}; DELETE FROM quality_rule WHERE id={int(identifier)};')
    for name in state['tables']:
        assert name.startswith('smoke_quality_') and name.replace('_', '').isalnum()
        execute('DROP TABLE IF EXISTS ods.' + name)
        asset_id = db("SELECT id FROM dwh_table_meta WHERE catalog_name='rtdwh_paimon' AND paimon_db='ods' AND paimon_table='" + name + "';")
        if asset_id:
            aid = int(asset_id)
            db(f'DELETE FROM dwh_column_meta WHERE table_meta_id={aid}; DELETE FROM dwh_schema_history WHERE table_meta_id={aid}; DELETE FROM dwh_table_meta WHERE id={aid};')
    for uid in state['users']:
        db(f'DELETE FROM query_history WHERE user_id={int(uid)}; DELETE FROM sys_user_role WHERE user_id={int(uid)}; DELETE FROM sys_user WHERE id={int(uid)};')
    for rid in state['roles']:
        api('DELETE', '/admin/roles/' + str(rid))
    STATE.unlink(missing_ok=True)


try:
    admin = api('POST', '/auth/login', {'username': 'admin', 'password': os.environ['RTDWH_ADMIN_PASSWORD']}, token='')['token']
    initialize()
    if '--cleanup' in sys.argv:
        state = json.loads(STATE.read_text()); cleanup(); print('PASS: exact quality fixtures cleaned'); sys.exit(0)
    if STATE.exists():
        raise RuntimeError('Existing fixture state; use --cleanup first')
    name = 'smoke_quality_' + str(int(time.time()))
    for table in [name, name + '_other']:
        execute(f"CREATE TABLE ods.{table} (id BIGINT, event_time TIMESTAMP(3), amount BIGINT, PRIMARY KEY(id) NOT ENFORCED) WITH ('bucket'='1')")
        state['tables'].append(table); save()
    execute(f"INSERT INTO ods.{name} VALUES (1,TIMESTAMP '2026-09-04 00:00:00',10),(2,TIMESTAMP '2026-09-04 23:59:59.999',CAST(NULL AS BIGINT)),(3,TIMESTAMP '2026-09-05 00:00:00',-1),(4,TIMESTAMP '2026-09-03 23:59:59.999',-1),(5,CAST(NULL AS TIMESTAMP(3)),-1)")
    doris('REFRESH CATALOG rtdwh_paimon'); api('POST', '/dwh/sync-metadata')
    perms = api('GET', '/admin/permissions')
    ids = [p['id'] for p in perms if p['permCode'] in ('quality:view', 'quality:manage')]
    tokens = []
    for table in state['tables']:
        role = api('POST', '/admin/roles', {'roleCode': table, 'roleName': table, 'permissionIds': ids,
            'dataScopes': [{'catalogPattern': 'rtdwh_paimon', 'databasePattern': 'ods', 'tablePattern': table}]})
        state['roles'].append(role['id']); save()
        password = secrets.token_urlsafe(24)
        user = api('POST', '/admin/users', {'username': table, 'password': password, 'roleIds': [role['id']]})
        state['users'].append(user['id']); save()
        tokens.append(api('POST', '/auth/login', {'username': table, 'password': password}, token='')['token'])
    body = {'ruleName': name + ' 业务窗口有效值', 'ruleType': 'range_check', 'layer': 'ods',
            'targetTable': 'rtdwh_paimon.ods.' + name, 'targetColumn': 'amount', 'expression': 'amount >= 0',
            'threshold': 0, 'enabled': True, 'checkScope': 'business_window', 'timeColumn': 'event_time', 'emptyPolicy': 'fail'}
    before = db('SELECT COUNT(*) FROM quality_check_run')
    preview = api('POST', '/quality/preview?businessDate=2026-09-04', body, tokens[0])
    assert '2026-09-05 00:00:00.000000' in preview['checkSql'] and 'checked_rows' in preview['checkSql']
    assert db('SELECT COUNT(*) FROM quality_check_run') == before
    api('POST', '/quality/preview?businessDate=2026-09-04', body, tokens[1], reject=True)
    api('POST', '/quality/preview?businessDate=2026-09-04', {**body, 'timeColumn': 'dt;DROP'}, tokens[0], reject=True)
    main = rule(body)
    api('POST', f'/quality/rules/{main["id"]}/run?businessDate=2026-09-04', token=tokens[1], reject=True)
    assert check(main['id'])[0]['errorCount'] == 1
    _, first = check(main['id'], '2026-09-04', tokens[0])
    assert (first['status'], first['checkedRows'], first['violationRows'], first['actualValue']) == ('failed', 2, 1, .5), first
    _, second = check(main['id'], '2026-09-05')
    assert (second['status'], second['checkedRows'], second['violationRows']) == ('failed', 1, 1), second
    execute(f"INSERT INTO ods.{name} VALUES (2,TIMESTAMP '2026-09-04 23:59:59.999',20)")
    assert check(main['id'], '2026-09-04')[0]['passed'] == 1
    alerts = [a for a in api('GET', '/quality/alerts') if a['ruleId'] == main['id']]
    assert any(a['scopeKey'] == first['scopeKey'] and a['resolutionReason'] == 'recovered' for a in alerts)
    assert any(a['scopeKey'] == second['scopeKey'] and not a['resolved'] for a in alerts)
    _, empty = check(main['id'], '2026-09-06')
    assert empty['status'] == 'failed' and empty['checkedRows'] == 0 and '没有数据' in empty['errorMessage']
    allow = rule({**body, 'ruleName': name + ' 允许空数据', 'emptyPolicy': 'allow'})
    assert check(allow['id'], '2026-09-06')[0]['passed'] == 1
    api('POST', f'/quality/rules/{allow["id"]}/toggle', {'enabled': False})
    print('PASS: readonly SQL preview, exact window bounds, NULL invalid, row evidence, empty policy and per-window recovery', flush=True)

    # Publish actual bounded write with window gate; subsequently change the draft to full-table.
    task = api('POST', '/sync-tasks', {'taskName': name, 'taskType': 'etl', 'executionMode': 'scheduled',
        'scenarioCode': 'scheduled_sql_output', 'flinkSql': f"INSERT INTO ods.{name} VALUES (1,TIMESTAMP '2026-09-04 00:00:00',10)",
        'syncStrategy': 'full_then_incremental', 'parallelism': 1})
    state['tasks'].append(task['id']); save()
    path = '/workflow/tasks/' + str(task['id'])
    output = api('PUT', path + '/outputs', [{'catalogName': 'rtdwh_paimon', 'databaseName': 'ods', 'tableName': name, 'layer': 'ods', 'qualityGateEnabled': True}])[0]
    api('POST', path + '/publish', {'changeSummary': '冻结业务窗口质量验收'})
    api('PUT', f'/quality/rules/{main["id"]}', {**body, 'checkScope': 'full_table'})
    assert check(main['id'])[0]['failed'] == 1, 'Full-table should see bad rows outside Sep 4'
    for date, expected in [('2026-09-04', 'available'), ('2026-09-05', 'blocked')]:
        instance = api('POST', path + '/backfill', {'startDate': date, 'endDate': date})[0]
        wait_delivery(task['id'], instance['id'], expected)
        production = next(p for p in api('GET', f'/workflow/outputs/{output["id"]}/productions') if p['instanceId'] == instance['id'])
        evidence = next(r for r in api('GET', f'/quality/runs?ruleId={main["id"]}') if r['batchId'] == production['qualityBatchId'])
        assert evidence['scopeKey'].startswith('business_window:' + date), evidence
        assert evidence['ruleVersion'] == main['version'], evidence
        if expected == 'blocked':
            execute(f"INSERT INTO ods.{name} VALUES (3,TIMESTAMP '2026-09-05 00:00:00',30)")
            api('POST', f'/workflow/instances/{instance["id"]}/recheck-delivery')
            wait_delivery(task['id'], instance['id'], 'available')
            history = api('GET', f'/workflow/productions/{production["id"]}/checks')
            assert len(history) == 2 and {h['status'] for h in history} == {'blocked', 'available'}, history
    print('PASS: real Flink write, immutable window gate despite draft edits, blocked delivery and same-production recheck', flush=True)

    # Retargeting current rules must not transfer historical results or alerts to another scope.
    api('PUT', f'/quality/rules/{main["id"]}', {**body, 'targetTable': 'rtdwh_paimon.ods.' + name + '_other'})
    assert not api('GET', f'/quality/runs?ruleId={main["id"]}', token=tokens[1])
    assert not [a for a in api('GET', '/quality/alerts', token=tokens[1]) if a['ruleId'] == main['id']]
    assert api('GET', f'/quality/runs?ruleId={main["id"]}', token=tokens[0])
    api('DELETE', f'/quality/rules/{main["id"]}')
    assert api('GET', f'/quality/runs?ruleId={main["id"]}', token=tokens[0])
    # View aggregate/history access follows transitive base-table permissions.
    view_name = name + '_v'
    view = api('POST', '/dwh/views', {'name': view_name,
        'sql': f'SELECT id, event_time, amount FROM rtdwh_paimon.ods.{name}', 'description': 'Quality scope regression'})
    state['views'].append({'name': view_name, 'id': view['definition']['id'], 'tableId': view['asset']['id']}); save()
    api('POST', '/dwh/views/' + view['asset']['assetId'] + '/publish', {'expectedVersion': view['definition']['version']})
    base_scope = {'catalogPattern': 'rtdwh_paimon', 'databasePattern': 'ods', 'tablePattern': name}
    view_scope = {'catalogPattern': 'internal', 'databasePattern': 'rtdwh_views', 'tablePattern': view_name}
    role_body = {'roleCode': name, 'roleName': name, 'permissionIds': ids, 'dataScopes': [base_scope, view_scope]}
    api('PUT', '/admin/roles/' + str(state['roles'][0]), role_body)
    view_rule = rule({**body, 'ruleName': name + ' View 权限验证', 'targetTable': 'internal.rtdwh_views.' + view_name})
    assert check(view_rule['id'], '2026-09-04', tokens[0])[0]['passed'] == 1
    api('PUT', '/admin/roles/' + str(state['roles'][0]), {**role_body, 'dataScopes': [view_scope]})
    api('POST', f'/quality/rules/{view_rule["id"]}/run?businessDate=2026-09-04', token=tokens[0], reject=True)
    assert not api('GET', f'/quality/runs?ruleId={view_rule["id"]}', token=tokens[0])
    api('PUT', '/admin/roles/' + str(state['roles'][0]), role_body)
    print('PASS: quality on managed View; base permission revocation blocks execution and historical metrics', flush=True)
    demo = rule({**body, 'ruleName': '业务窗口金额有效性 · 本地验收'})
    check(demo['id'], '2026-09-04')
    state['demoRule'] = demo['id']; state['demoTable'] = name; save()
    print('PASS: scoped preview/run denial, historical permission after retarget and deletion', flush=True)
    if '--keep' in sys.argv:
        print('Fixtures retained for UI review; cleanup with --cleanup', flush=True)
    else:
        cleanup(); print('PASS: exact quality fixtures cleaned', flush=True)
finally:
    if session:
        try:
            gw('DELETE', '/v1/sessions/' + session)
        except Exception:
            pass
