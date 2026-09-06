#!/usr/bin/env python3
"""Real HTTP/Doris publication regression; constants intentionally isolate API contracts.
--prepare-legacy runs on V27 before upgrading. Then run normally on V28 (or --keep for UI).
--cleanup removes only recorded fixtures. Credentials are never saved or printed.
Paimon/View permission coverage lives in smoke-managed-views.py.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import threading
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = 'http://127.0.0.1:8080'
STATE = Path('tmp/api-publication-smoke-state.json')
TOKEN = ''
state = {'prefix': 'smoke_api_' + str(int(time.time())), 'services': [], 'apps': [], 'traces': []}
state_lock = threading.Lock()


def api(method, path, body=None, headers=None, expect=None):
    request = Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                      headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN, **(headers or {})})
    try:
        with urlopen(request, timeout=60) as response:
            result = json.load(response)
    except HTTPError as error:
        result = json.load(error)
        if expect and error.code == expect[0] and expect[1] in result.get('message', ''):
            return None
        raise AssertionError(f'{method} {path}: HTTP {error.code}: {result.get("message")}') from None
    assert not expect, 'Expected rejection: ' + path
    assert result.get('code') == 0, result.get('message')
    return result.get('data')


def db(sql):
    return subprocess.run(['docker', 'exec', '-i', 'rtdwh-mysql', 'sh', '-c',
                           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B rtdwh_mgmt'],
                          input=sql, text=True, capture_output=True, check=True).stdout.strip()


def save():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n'); STATE.chmod(0o600)


def definition(code, number=11):
    return {'serviceCode': code, 'serviceName': code, 'description': 'API 发布契约本地验收',
            'sqlTemplate': 'SELECT CAST({{n}} AS BIGINT) AS amount',
            'parameterConfig': json.dumps({'parameters': [{'name': 'n', 'type': 'number', 'defaultValue': number}]}),
            'catalogName': 'rtdwh_paimon', 'databaseName': 'ods', 'maxRows': 100,
            'timeoutSeconds': 15, 'rateLimitPerMinute': 500}


def create(code, number=11):
    value = api('POST', '/data-services', definition(code, number)); state['services'].append(value['id']); save()
    return value


def credentials(service_id):
    app = api('POST', '/data-services/apps', {'appName': state['prefix']})
    state['apps'].append(app['id']); save()
    api('POST', f'/data-services/apps/{app["id"]}/grants', {'serviceId': service_id})
    return {'X-App-Key': app['appKey'], 'X-App-Secret': app['appSecret']}


def invoke(code, headers, number=None):
    response = api('POST', '/open/data/' + code, {} if number is None else {'n': number}, headers=headers)
    with state_lock:
        if response.get('requestId'): state['traces'].append(response['requestId'])
    return response


def timings(code, headers):
    values = []
    for _ in range(20):
        start = time.monotonic(); invoke(code, headers); values.append(round((time.monotonic() - start) * 1000, 2))
    return {'samples': 20, 'concurrency': 1, 'rows': 1, 'medianMs': statistics.median(values),
            'p95Ms': sorted(values)[18], 'maxMs': max(values), 'query': 'parameterized BIGINT constant through real Doris'}


def cleanup():
    has_versions = int(db("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='rtdwh_mgmt' AND table_name='data_service_version'")) > 0
    for sid in state['services']:
        code = db(f'SELECT service_code FROM data_service_definition WHERE id={int(sid)}')
        assert not code or code.startswith(state['prefix']), 'Fixture identity mismatch'
        db(f'DELETE FROM data_service_invocation_log WHERE service_id={int(sid)}; DELETE FROM data_service_grant WHERE service_id={int(sid)};')
        if has_versions: db(f'DELETE FROM data_service_version WHERE service_id={int(sid)};')
        db(f'DELETE FROM data_service_definition WHERE id={int(sid)};')
    for aid in state['apps']: db(f'DELETE FROM data_service_app WHERE id={int(aid)};')
    for trace in state['traces']:
        assert re.fullmatch(r'[A-Za-z0-9_-]{1,128}', trace)
        db(f"DELETE FROM query_history WHERE trace_id='{trace}';")
    STATE.unlink(missing_ok=True)
    print('PASS: exact API fixtures cleaned', flush=True)


def main():
    global TOKEN, state
    parser = argparse.ArgumentParser()
    parser.add_argument('--prepare-legacy', action='store_true')
    parser.add_argument('--keep', action='store_true')
    parser.add_argument('--cleanup', action='store_true')
    args = parser.parse_args()
    TOKEN = api('POST', '/auth/login', {'username': 'admin', 'password': os.environ['RTDWH_ADMIN_PASSWORD']})['token']
    if args.cleanup:
        state = json.loads(STATE.read_text()); cleanup(); return
    if args.prepare_legacy:
        assert not STATE.exists(), 'Existing fixtures; clean them first'
        assert db('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1') == '27'
        code = state['prefix'] + '_legacy'; service = create(code)
        api('POST', f'/data-services/{service["id"]}/publish')
        service = api('PUT', f'/data-services/{service["id"]}', definition(code, 22))
        headers = credentials(service['id'])
        assert invoke(code, headers)['rows'] == [[22]]
        state['legacy'] = {'id': service['id'], 'code': code, 'apiVersion': service['apiVersion'], 'appId': state['apps'][-1]}
        state['before'] = timings(code, headers); save()
        print('PASS: V27 live definition prepared and measured; ready for migration', flush=True)
        return
    if STATE.exists(): state = json.loads(STATE.read_text())
    assert int(db('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1')) >= 28
    legacy = state.get('legacy')
    if legacy:
        service = api('GET', f'/data-services/{legacy["id"]}')
        version = api('GET', f'/data-services/{legacy["id"]}/published')
        captured = next(v for v in api('GET', f'/data-services/{legacy["id"]}/versions') if v['origin'] == 'legacy_capture')
        assert captured['publishedBy'] is None and captured['resultColumnsJson'] is None
        assert captured['versionNo'] == legacy['apiVersion']
        assert json.loads(captured['parameterConfig'])['parameters'][0]['defaultValue'] == 22
        app = api('POST', f'/data-services/apps/{legacy["appId"]}/rotate-secret')
        headers = {'X-App-Key': app['appKey'], 'X-App-Secret': app['appSecret']}
        if version['origin'] == 'legacy_capture':
            assert invoke(legacy['code'], headers)['rows'] == [[22]]
            state['after'] = timings(legacy['code'], headers)
            assert state['after']['p95Ms'] <= max(1000, state['before']['p95Ms'] * 2), 'Sequential API p95 regression'
            update = definition(legacy['code'], 33); update['expectedRevision'] = service['revision']
            service = api('PUT', f'/data-services/{service["id"]}', update)
            assert invoke(legacy['code'], headers)['rows'] == [[22]]
            api('POST', f'/data-services/{service["id"]}/publish', {'expectedRevision': service['revision']})
        else:
            assert state.get('after'), 'Missing initial migration invocation evidence'
        assert invoke(legacy['code'], headers)['rows'] == [[33]]
        save()
        print('PASS: legacy migration retains live behavior; edited draft remains isolated; republish verified', flush=True)
    code = state['prefix'] + '_new_' + str(len(state['services'])); service = create(code); path = f'/data-services/{service["id"]}'
    headers = credentials(service['id'])
    preview = api('POST', path + '/preview', {'expectedRevision': service['revision']})
    assert preview['publishable'] and preview['resultColumns'][0]['name'] == 'amount'
    assert not api('GET', path + '/versions'), 'Preview created a release'
    service = api('POST', path + '/publish', {'expectedRevision': service['revision']})
    first = api('GET', path + '/published'); assert invoke(code, headers)['rows'] == [[11]]
    stale = service['revision']; changed = definition(code, 22); changed['expectedRevision'] = stale
    service = api('PUT', path, changed)
    api('PUT', path, changed, expect=(409, '已被修改'))
    api('POST', path + '/publish', {'expectedRevision': stale}, expect=(409, '已被修改'))
    assert invoke(code, headers)['rows'] == [[11]]
    # Real concurrent transactions: one revision may be published only once.
    def competing_publish(_):
        try: return api('POST', path + '/publish', {'expectedRevision': service['revision']})
        except AssertionError as error:
            assert 'HTTP 409' in str(error), str(error)
            return None
    with ThreadPoolExecutor(max_workers=4) as executor: published = list(executor.map(competing_publish, range(4)))
    assert sum(value is not None for value in published) == 1, 'Concurrent publication was not serialized'
    service = api('GET', path); assert service['apiVersion'] == 2
    assert invoke(code, headers)['rows'] == [[22]]
    second = api('GET', path + '/published')
    assert api('GET', path + '/versions')[-1] == first, 'Published evidence changed'
    # Invalid query/parameter/output contracts must not replace the online pointer.
    broken = definition(code, 99); broken['sqlTemplate'] = 'SELECT absent_column FROM rtdwh_paimon.ods.ods_query_history'; broken['parameterConfig'] = '[]'
    broken['expectedRevision'] = service['revision']; service = api('PUT', path, broken)
    api('POST', path + '/publish', {'expectedRevision': service['revision']}, expect=(400, '契约校验失败'))
    assert api('GET', path + '/published')['id'] == second['id']; assert invoke(code, headers)['rows'] == [[22]]
    broken = definition(code, 99); broken['sqlTemplate'] = 'SELECT CAST({{n}} AS BIGINT) AS changed_name'; broken['expectedRevision'] = service['revision']
    service = api('PUT', path, broken)
    assert not api('POST', path + '/preview', {'expectedRevision': service['revision']})['publishable']
    api('POST', path + '/publish', {'expectedRevision': service['revision']}, expect=(409, '输出列'))
    service = api('POST', path + f'/rollback/{first["id"]}', {'expectedRevision': service['revision']})
    rolled = api('GET', path + '/published')
    assert rolled['origin'] == 'rollback' and rolled['sourceVersionId'] == first['id'] and rolled['versionNo'] == 3
    assert 'changed_name' in service['sqlTemplate'] and invoke(code, headers)['rows'] == [[11]]
    logs = api('GET', '/data-services/logs?limit=200')
    used = [log for log in logs if log['serviceId'] == service['id'] and log['status'] == 'success']
    assert {log['apiVersion'] for log in used} == {1, 2, 3}
    assert all(log['versionId'] and log['executionUserId'] for log in used)
    service = api('POST', path + '/publish?published=false', {'expectedRevision': service['revision']})
    api('POST', '/open/data/' + code, {}, headers=headers, expect=(400, '未发布'))
    service = api('POST', path + f'/rollback/{first["id"]}', {'expectedRevision': service['revision']})
    assert invoke(code, headers)['rows'] == [[11]]
    # Published configuration continues to work even though the saved draft is incompatible.
    for task_type, scenario in [('materialized', 'materialized_table'), ('etl', 'materialized_table'), ('cdc_sync', 'kafka_realtime_ingest')]:
        api('POST', '/sync-tasks', {'taskName': code, 'taskType': task_type, 'scenarioCode': scenario,
                                 'syncStrategy': 'full_then_incremental', 'flinkSql': 'SELECT 1'}, expect=(400, '尚未'))
    save()
    report = {'before': state.get('before'), 'after': state.get('after'),
              'verified': ['legacy_capture' if legacy else 'fresh_v28', 'draft_isolation', 'concurrent_publish_4',
                           'stale_revision', 'failed_publish', 'incompatible_columns', 'rollback_keeps_draft',
                           'version_logs', 'offline', 'capability_api_guard']}
    output = Path('docs/validation/v28-api-publication-smoke.json'); output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('PASS: real Doris publication, revision concurrency, failed preflight, rollback, logs and capability gates', flush=True)
    if args.keep: print('UI fixtures retained at /query/data-service', flush=True)
    else: cleanup()


if __name__ == '__main__':
    try: main()
    except Exception:
        if not STATE.exists() or json.loads(STATE.read_text()).get('prefix') == state['prefix']: save()
        raise
