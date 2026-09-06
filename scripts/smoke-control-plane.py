#!/usr/bin/env python3
"""Local Docker only: disposable data-scope fixtures and maintenance fault injection.
Requires RTDWH_ADMIN_PASSWORD. --gateway-fault briefly pauses the local SQL Gateway.
"""
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.request
import urllib.error

BASE = os.getenv('RTDWH_API_BASE', 'http://127.0.0.1:8080')
PREFIX = 'smoke_guard_' + str(int(time.time()))
ADMIN = ''
users, roles, tables, tasks, services = [], [], [], [], []


def api(method, path, body=None, token=None, denied=False):
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + (ADMIN if token is None else token)})
    try:
        with urllib.request.urlopen(req, timeout=100) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        if denied and error.code in (400, 403):
            body = json.load(error)
            assert error.code == 403 or any(word in body.get('message', '') for word in ('无权', '权限', '访问')), 'Request failed for a reason other than access denial'
            return None
        raise
    if denied:
        assert result.get('code') == 403, 'Cross-scope request was not denied'
        return None
    assert result.get('code') == 0, result.get('message')
    return result['data']


def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', 'rtdwh-mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B rtdwh_mgmt'], input=statement, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def wait_log(table_id, log_id, states, timeout=90):
    until = time.time() + timeout
    while time.time() < until:
        entries = api('GET', '/dwh/maintenance/logs?tableMetaId=' + str(table_id))
        entry = next(item for item in entries if str(item['id']) == str(log_id))
        if entry['status'] in states:
            return entry
        time.sleep(1)
    raise AssertionError('Maintenance did not reach ' + str(states))


try:
    ADMIN = api('POST', '/auth/login', {'username': 'admin', 'password': os.environ['RTDWH_ADMIN_PASSWORD']}, token='')['token']
    permissions = api('GET', '/admin/permissions')
    permission_ids = [item['id'] for item in permissions if item['permCode'] in
        ['task:view', 'task:manage', 'dwh:view', 'dwh:manage', 'lineage:view', 'data-service:view', 'data-service:manage']]
    fixtures = []
    for suffix in ['a', 'b']:
        name = PREFIX + '_' + suffix
        table_id = int(sql("INSERT INTO dwh_table_meta (asset_id,paimon_db,paimon_table,layer,sensitivity_level,lifecycle_status,version,created_at,updated_at) "
                           f"VALUES (UUID(),'ods','{name}','ods','internal','active',0,NOW(),NOW()); SELECT LAST_INSERT_ID();"))
        tables.append(table_id)
        role_request = {'roleCode': 'guard_' + suffix + '_' + str(int(time.time())), 'roleName': name,
                        'permissionIds': permission_ids, 'dataScopes': [{'catalogPattern': 'rtdwh_paimon', 'databasePattern': 'ods', 'tablePattern': name}]}
        role = api('POST', '/admin/roles', role_request); roles.append(role['id'])
        password = secrets.token_urlsafe(24)
        user = api('POST', '/admin/users', {'username': name, 'password': password, 'roleIds': [role['id']]}); users.append(user['id'])
        token = api('POST', '/auth/login', {'username': name, 'password': password}, token='')['token']
        task = api('POST', '/sync-tasks', {'taskName': name, 'taskType': 'etl', 'executionMode': 'scheduled',
                   'scenarioCode': 'scheduled_sql_output', 'flinkSql': 'SELECT * FROM rtdwh_paimon.ods.' + name,
                   'syncStrategy': 'full_then_incremental'})
        tasks.append(task['id'])
        service = api('POST', '/data-services', {'serviceCode': name, 'serviceName': name, 'sqlTemplate': 'SELECT * FROM ' + name,
                      'parameterConfig': '[]', 'catalogName': 'rtdwh_paimon', 'databaseName': 'ods'})
        services.append(service['id'])
        fixtures.append((name, table_id, task['id'], service['id'], role['id'], role_request, token))
    for current, other in [(fixtures[0], fixtures[1]), (fixtures[1], fixtures[0])]:
        name, table_id, task_id, service_id, _, _, token = current
        api('GET', '/dwh/tables/' + str(table_id), token=token)
        api('GET', '/sync-tasks/' + str(task_id), token=token)
        own_asset = api('GET', '/dwh/tables/' + str(table_id), token=token)['assetId']
        other_asset = api('GET', '/dwh/tables/' + str(other[1]))['assetId']
        for suffix in ['', '/context', '/schema-revisions']:
            api('GET', '/dwh/assets/' + own_asset + suffix, token=token)
            api('GET', '/dwh/assets/' + other_asset + suffix, token=token, denied=True)
        for path in ['/dwh/tables/' + str(other[1]), '/dwh/tables/' + str(other[1]) + '/columns',
                     '/dwh/tables/' + str(other[1]) + '/snapshots', '/sync-tasks/' + str(other[2])]:
            api('GET', path, token=token, denied=True)
        api('POST', '/dwh/tables/' + str(other[1]) + '/compact', token=token, denied=True)
        api('POST', '/data-services/' + str(other[3]) + '/publish', {'expectedRevision': 0}, token=token, denied=True)
        api('DELETE', '/data-services/' + str(other[3]), token=token, denied=True)
        for path in ['/dwh/tables', '/lineage/graph', '/data-services']:
            result = json.dumps(api('GET', path, token=token))
            assert other[0] not in result, 'Metadata leaked across data scopes'
        assert any(item['id'] == service_id for item in api('GET', '/data-services', token=token))
    print('PASS: mutually exclusive roles cannot read or mutate each other\'s assets, tasks, lineage or services', flush=True)
    name, table_id, task_id, _, role_id, role_request, token = fixtures[0]
    role_request['dataScopes'][0]['tablePattern'] = PREFIX + '_revoked'
    api('PUT', '/admin/roles/' + str(role_id), role_request)
    api('GET', '/sync-tasks/' + str(task_id), token=token, denied=True)
    api('GET', '/dwh/tables/' + str(table_id), token=token, denied=True)
    revoked_asset = api('GET', '/dwh/tables/' + str(table_id))['assetId']
    for suffix in ['', '/context', '/schema-revisions']:
        api('GET', '/dwh/assets/' + revoked_asset + suffix, token=token, denied=True)
    print('PASS: data scope revocation applies to the existing login token', flush=True)

    # The metadata fixture intentionally has no physical Paimon table.
    missing = api('POST', '/dwh/tables/' + str(table_id) + '/compact')['operationId']
    failed = wait_log(table_id, missing, {'failed', 'success'})
    assert failed['status'] == 'failed', 'CALL on a missing table falsely succeeded'
    for _ in range(90):
        recovery = api('GET', '/dwh/maintenance/' + str(missing))['operation']
        if recovery['cleanupStatus'] == 'done': break
        time.sleep(1)
    assert recovery['cleanupStatus'] == 'done', 'Failed CALL session was not cleaned'
    print('PASS: real Paimon CALL failure is recorded as failed', flush=True)

    if '--gateway-fault' in sys.argv:
        real = next(item for item in api('GET', '/dwh/tables?keyword=rtdwh_smoke_events') if item['paimonTable'] == 'rtdwh_smoke_events')
        operation = api('POST', '/dwh/tables/' + str(real['id']) + '/compact')['operationId']
        assert str(operation).isdigit()
        try:
            subprocess.run(['docker', 'pause', 'rtdwh-flink-sql-gateway'], check=True, stdout=subprocess.DEVNULL)
            unknown = wait_log(real['id'], operation, {'unknown'}, timeout=70)
            assert unknown['operationId'] and unknown['sessionId']
            # Inject elapsed time for this one test operation; do not wait 30 minutes or change global settings.
            sql(f"UPDATE table_maintenance_log SET started_at=DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE id={int(operation)}")
        finally:
            subprocess.run(['docker', 'unpause', 'rtdwh-flink-sql-gateway'], check=True, stdout=subprocess.DEVNULL)
        terminal = wait_log(real['id'], operation, {'success', 'failed', 'timed_out'}, timeout=100)
        if terminal['status'] == 'timed_out':
            print('PASS: elapsed-time injection records timed_out and continues reconciliation', flush=True)
            terminal = wait_log(real['id'], operation, {'success', 'failed'}, timeout=100)
        assert terminal['status'] == 'success'
        print('PASS: Gateway interruption retains handles and reconciles the original operation to success', flush=True)
finally:
    for service_id in services:
        api('DELETE', '/data-services/' + str(service_id))
    for task_id in tasks:
        api('DELETE', '/sync-tasks/' + str(task_id))
    for user_id in users:
        sql(f"DELETE FROM sys_user_role WHERE user_id={int(user_id)}; DELETE FROM sys_user WHERE id={int(user_id)} AND username LIKE '{PREFIX}%';")
    for role_id in roles:
        api('DELETE', '/admin/roles/' + str(role_id))
    for table_id in tables:
        sql(f"DELETE FROM maintenance_recovery_event WHERE maintenance_id IN (SELECT id FROM table_maintenance_log WHERE table_meta_id={int(table_id)}); "
            f"DELETE FROM table_maintenance_log WHERE table_meta_id={int(table_id)}; DELETE FROM maintenance_coordination_lock WHERE table_meta_id={int(table_id)}; "
            f"DELETE FROM dwh_table_meta WHERE id={int(table_id)} AND paimon_table LIKE '{PREFIX}%';")
