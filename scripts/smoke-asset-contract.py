#!/usr/bin/env python3
"""Local Docker: real Paimon/Flink -> Doris report, asset relations and schema evolution.
Requires RTDWH_ADMIN_PASSWORD. --keep retains dedicated fixtures for UI inspection;
--cleanup removes only objects recorded in tmp/asset-smoke-state.json.
The sample uses bounded VALUES input, not a claim of CDC or exactly-once replay.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

BASE = os.getenv('RTDWH_API_BASE', 'http://127.0.0.1:8080')
GATEWAY = os.getenv('RTDWH_GATEWAY_BASE', 'http://127.0.0.1:9083')
STATE = Path('tmp/asset-smoke-state.json')
TOKEN = ''
SESSION = None
state = {'tasks': [], 'tables': [], 'reports': [], 'services': [], 'rules': []}


def request(base, method, path, body=None, authenticated=False):
    headers = {'Content-Type': 'application/json'}
    if authenticated:
        headers['Authorization'] = 'Bearer ' + TOKEN
    req = urllib.request.Request(base + path, method=method,
        data=None if body is None else json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=100) as response:
        return json.load(response)


def api(method, path, body=None):
    result = request(BASE, method, path, body, True)
    assert result.get('code') == 0, result.get('message')
    return result.get('data')


def save():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2)); STATE.chmod(0o600)


def gateway(sql):
    operation = request(GATEWAY, 'POST', f'/v1/sessions/{SESSION}/statements',
        {'statement': sql, 'executionConfig': {'execution.runtime-mode': 'batch', 'parallelism.default': '1'}})['operationHandle']
    deadline = time.time() + 90
    while time.time() < deadline:
        status = request(GATEWAY, 'GET', f'/v1/sessions/{SESSION}/operations/{operation}/status')['status']
        if status == 'FINISHED':
            return
        assert status not in ('ERROR', 'CANCELED', 'CLOSED'), 'Gateway operation failed: ' + operation
        time.sleep(.3)
    raise AssertionError('Gateway operation timed out; handle=' + operation)


def initialize_gateway():
    global SESSION
    config = json.loads(subprocess.check_output(['docker', 'inspect', 'rtdwh-backend'], text=True))[0]['Config']['Env']
    env = dict(item.split('=', 1) for item in config if '=' in item)
    literal = lambda value: "'" + value.replace("'", "''") + "'"
    SESSION = request(GATEWAY, 'POST', '/v1/sessions', {})['sessionHandle']
    catalog = env.get('DORIS_CATALOG', 'rtdwh_paimon')
    assert re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', catalog)
    state['catalog'] = catalog
    options = {'type': 'paimon', 'metastore': 'jdbc', 'uri': env['PAIMON_JDBC_URI'],
        'jdbc.user': env['PAIMON_JDBC_USER'], 'jdbc.password': env['PAIMON_JDBC_PASSWORD'],
        'catalog-key': env.get('PAIMON_CATALOG_KEY', 'rtdwh'), 'warehouse': env['PAIMON_WAREHOUSE']}
    gateway('CREATE CATALOG `' + catalog + '` WITH (' + ','.join(literal(k) + '=' + literal(v) for k,v in options.items()) + ')')
    gateway('USE CATALOG `' + catalog + '`')


def sync():
    api('POST', '/dwh/sync-metadata')


def asset(database, name):
    return next(t for t in api('GET', '/dwh/tables?keyword=' + name) if t['paimonDb'] == database and t['paimonTable'] == name)


def history(asset_id):
    return api('GET', '/dwh/assets/' + asset_id + '/schema-revisions')


def context(asset_id):
    return api('GET', '/dwh/assets/' + asset_id + '/context')


def wait_instance(task_id, instance):
    deadline = time.time() + 180
    while time.time() < deadline:
        run = next(r for r in api('GET', '/workflow/instances?taskId=' + str(task_id)) if r['id'] == instance['id'])
        assert run['status'] not in ('failed', 'cancelled'), run.get('errorMessage')
        if run['status'] == 'success' and run.get('deliveryStatus') == 'available':
            return run
        if run.get('deliveryStatus') == 'blocked':
            raise AssertionError('Quality delivery blocked for instance ' + str(run['id']))
        time.sleep(2)
    raise AssertionError('Instance did not finish: ' + str(instance['id']))


def cleanup():
    for task in state['tasks']:
        for run in api('GET', '/workflow/instances?taskId=' + str(task)):
            if run['status'] in ('waiting', 'queued', 'running'):
                api('POST', '/workflow/instances/' + str(run['id']) + '/cancel')
    for kind, path in [('reports', '/reports/'), ('services', '/data-services/'), ('tasks', '/sync-tasks/'), ('rules', '/quality/rules/')]:
        for identifier in list(reversed(state[kind])):
            api('DELETE', path + str(identifier)); state[kind].remove(identifier); save()
    for database, name in list(state['tables']):
        assert database in ('ods', 'dwd', 'ads') and re.fullmatch(r'smoke_asset_\d+(?:_hidden)?', name)
        gateway(f'DROP TABLE IF EXISTS {database}.{name}')
        sql = f"""SET @asset_smoke_id=(SELECT id FROM dwh_table_meta WHERE paimon_db='{database}' AND paimon_table='{name}');
DELETE FROM dwh_column_meta WHERE table_meta_id=@asset_smoke_id;
DELETE FROM dwh_data_lineage WHERE source_table_id=@asset_smoke_id OR target_table_id=@asset_smoke_id;
DELETE FROM dwh_schema_history WHERE table_meta_id=@asset_smoke_id;
DELETE FROM table_maintenance_log WHERE table_meta_id=@asset_smoke_id;
DELETE FROM dwh_table_meta WHERE id=@asset_smoke_id;"""
        subprocess.run(['docker', 'exec', '-i', 'rtdwh-mysql', 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot rtdwh_mgmt'], input=sql, text=True, check=True, capture_output=True)
        state['tables'].remove([database, name]); save()
    STATE.unlink(missing_ok=True)


try:
    TOKEN = api('POST', '/auth/login', {'username': 'admin', 'password': os.environ['RTDWH_ADMIN_PASSWORD']})['token']
    if '--cleanup' in sys.argv:
        state = json.loads(STATE.read_text()); initialize_gateway(); cleanup()
        print('PASS: dedicated asset fixtures cleaned', flush=True)
    else:
        assert not STATE.exists(), 'Previous asset fixture state exists; use --cleanup first'
        initialize_gateway()
        name = 'smoke_asset_' + str(int(time.time()))
        for database in ('ods', 'dwd', 'ads'):
            gateway(f'CREATE DATABASE IF NOT EXISTS {database}')
            state['tables'].append([database, name]); save()
            gateway(f"CREATE TABLE {database}.{name} (id BIGINT, amount BIGINT, label STRING, PRIMARY KEY (id) NOT ENFORCED) WITH ('bucket'='1')")
        sync()
        assets = {db: asset(db, name) for db in ('ods', 'dwd', 'ads')}
        assert all(a['assetType'] == 'paimon_primary_key_table' and a['discoveryStatus'] == 'observed' for a in assets.values())
        initial_ids = {db: a['assetId'] for db, a in assets.items()}
        rule = api('POST', '/quality/rules', {'ruleName': name, 'ruleType': 'null_rate', 'layer': 'ads',
            'targetTable': 'ads.' + name, 'targetColumn': 'id', 'threshold': 0, 'enabled': True})
        state['rules'].append(rule['id']); save()
        tasks, output_ids = {}, {}
        sqls = {'ods': f"INSERT INTO ods.{name} VALUES (1,10,'east'),(2,20,'west')",
            'dwd': f'INSERT INTO dwd.{name} SELECT id, amount * 2, label FROM ods.{name}',
            'ads': f"INSERT INTO ads.{name} SELECT CAST(1 AS BIGINT), SUM(amount), 'total' FROM dwd.{name}"}
        previous = None
        for database in ('ods', 'dwd', 'ads'):
            task = api('POST', '/sync-tasks', {'taskName': name + '_' + database, 'taskType': 'etl',
                'executionMode': 'scheduled', 'scenarioCode': 'scheduled_sql_output', 'flinkSql': sqls[database],
                'syncStrategy': 'full_then_incremental', 'parallelism': 1})
            tasks[database] = task; state['tasks'].append(task['id']); save()
            path = '/workflow/tasks/' + str(task['id'])
            output = api('PUT', path + '/outputs', [{'catalogName': state['catalog'], 'databaseName': database,
                'tableName': name, 'layer': database, 'qualityGateEnabled': database == 'ads'}])[0]
            assert output['assetId'] == initial_ids[database]
            output_ids[database] = output['id']
            if previous:
                api('POST', path + '/publish', {'changeSummary': 'Initial asset output contract'})
                api('POST', '/workflow/dependencies', {'upstreamTaskId': tasks[previous]['id'], 'downstreamTaskId': task['id'],
                    'conditionType': 'data_available', 'outputDatasetId': output_ids[previous]})
            published = api('POST', path + '/publish', {'changeSummary': 'Real asset contract sample'})
            assert json.loads(published['snapshotJson'])['flinkSql'] == sqls[database]
            previous = database
        runs = api('POST', '/workflow/tasks/' + str(tasks['ads']['id']) + '/backfill',
            {'startDate': '2026-09-05', 'endDate': '2026-09-05', 'bindingPolicy': 'batch_only'})
        for database in ('ods', 'dwd', 'ads'):
            run = next(r for r in runs if r['taskId'] == tasks[database]['id'])
            wait_instance(tasks[database]['id'], run)
        print('PASS: real ODS -> DWD -> ADS Flink jobs, bound deliveries and frozen quality gate', flush=True)
        sync()
        query = f'SELECT amount FROM {state["catalog"]}.ads.{name}'
        report = api('POST', '/reports', {'reportName': name, 'reportType': 'table', 'sqlQuery': query, 'filterConfig': '[]', 'isPublished': False})
        state['reports'].append(report['id']); save()
        report['isPublished'] = True; api('PUT', '/reports/' + str(report['id']), report)
        data = api('GET', '/reports/' + str(report['id']) + '/data')
        assert data['rows'] == [[60]], data['rows']
        service = api('POST', '/data-services', {'serviceCode': name, 'serviceName': name, 'sqlTemplate': query,
            'parameterConfig': '[]', 'catalogName': state['catalog'], 'databaseName': 'ads'})
        state['services'].append(service['id']); save()
        middle = context(initial_ids['dwd'])
        assert {(u['id'],u['relation']) for u in middle['usages']} >= {(tasks['dwd']['id'],'producer'),(tasks['ads']['id'],'consumer')}
        assert {a['direction'] for a in middle['relatedAssets']} == {'upstream','downstream'}
        end = context(initial_ids['ads'])
        assert {u['kind'] for u in end['usages']} == {'task','report','service'}
        production = end['productions'][0]
        assert production['assetId'] == initial_ids['ads'] and production['status'] == 'available'
        checks = api('GET', '/workflow/productions/' + str(production['id']) + '/checks')
        assert checks and checks[0]['qualityBatchId']
        draft = api('GET', '/sync-tasks/' + str(tasks['ads']['id'])); draft['flinkSql'] = 'SELECT 1'
        api('PUT', '/sync-tasks/' + str(draft['id']), {k: draft[k] for k in ('taskName','description','flinkSql','parallelism','checkpointIntervalMs','tableMappings')})
        assert context(initial_ids['dwd'])['usages'] == middle['usages']
        print('PASS: Doris report returns 60; producer/consumer/quality links use published evidence despite draft edits', flush=True)
        target = assets['ods']; target_id = target['assetId']; table_id = target['id']
        columns = api('GET', f'/dwh/tables/{table_id}/columns')
        label = next(c for c in columns if c['columnName'] == 'label')
        api('PUT', '/dwh/columns/' + str(label['id']) + '/comment', {'comment': 'asset smoke business annotation'})
        baseline = len(history(target_id))
        gateway(f'ALTER TABLE ods.{name} ADD (extra STRING)'); sync()
        assert len(history(target_id)) == baseline + 1 and history(target_id)[0]['severity'] == 'compatible'
        gateway(f'ALTER TABLE ods.{name} RENAME label TO region'); sync()
        renamed = next(c for c in api('GET', f'/dwh/tables/{table_id}/columns') if c['columnName'] == 'region')
        assert renamed['id'] == label['id'] and renamed['engineFieldId'] == label['engineFieldId']
        assert renamed['businessComment'] == 'asset smoke business annotation'
        assert history(target_id)[0]['severity'] == 'breaking'
        gateway(f'ALTER TABLE ods.{name} DROP (extra)'); sync()
        assert history(target_id)[0]['severity'] == 'breaking'
        revision_count = len(history(target_id)); sync(); assert len(history(target_id)) == revision_count
        hidden = name + '_hidden'; state['tables'].append(['ods', hidden]); save()
        gateway(f'ALTER TABLE ods.{name} RENAME TO {hidden}'); sync()
        missing = api('GET', '/dwh/assets/' + target_id)
        assert missing['discoveryStatus'] == 'missing' and missing['id'] == table_id
        assert len(history(target_id)) == revision_count
        gateway(f'ALTER TABLE ods.{hidden} RENAME TO {name}'); sync()
        restored = asset('ods', name)
        assert restored['assetId'] == target_id and restored['discoveryStatus'] == 'observed'
        print('PASS: real add/rename/drop, annotation identity, no-op deduplication and missing/reappearance retention', flush=True)
        state['assetIds'] = initial_ids; save()
        if '--keep' in sys.argv:
            print('UI fixture:', '/dwh/assets/' + target_id + '?tab=schema-history', flush=True)
        else:
            cleanup(); print('PASS: dedicated asset fixtures cleaned', flush=True)
finally:
    if SESSION:
        request(GATEWAY, 'DELETE', '/v1/sessions/' + SESSION)
