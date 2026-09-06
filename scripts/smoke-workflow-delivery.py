#!/usr/bin/env python3
"""Local V2-03 HTTP regression; requires RTDWH_ADMIN_PASSWORD.

Run with WORKFLOW_RUNNER_ENABLED=false in the backend. The dedicated executor
simulates engine callbacks; this is not a real Flink execution test. Dependency
and delivery reconcilers stay enabled. Afterwards restore the internal runner
and run smoke-release-contract.py for actual Flink execution.

--keep retains fixtures for UI inspection; --cleanup removes those task fixtures.
API deletion retains historical run/audit records according to existing policy.
--docker-cleanup also removes placeholder asset metadata from local rtdwh-mysql.
"""
import concurrent.futures
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = os.getenv('RTDWH_API_BASE', 'http://127.0.0.1:8080')
STATE = pathlib.Path(os.getenv('RTDWH_DELIVERY_STATE', '/tmp/rtdwh-delivery-fixtures.json'))
TOKEN = ''
EXECUTOR = 'smoke-delivery'
DATE = '2026-09-05'
fixtures = {'tasks': [], 'tables': []}


def api(method, path, body=None, reject=False):
    request = urllib.request.Request(BASE + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN})
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        if reject and error.code in (400, 403, 409):
            return None
        raise
    if reject:
        assert result['code'] != 0, 'Invalid operation was accepted: ' + path
        return None
    assert result['code'] == 0, result.get('message')
    return result.get('data')


def save_state():
    STATE.write_text(json.dumps(fixtures))


def task(label):
    created = api('POST', '/sync-tasks', {'taskName': prefix + '-' + label, 'taskType': 'etl',
        'executionMode': 'scheduled', 'scenarioCode': 'scheduled_sql_output', 'parallelism': 1,
        'syncStrategy': 'full_then_incremental',
        'flinkSql': "CREATE TEMPORARY TABLE delivery_sink (n INT) WITH ('connector'='blackhole'); INSERT INTO delivery_sink SELECT 1"})
    fixtures['tasks'].append(created['id']); save_state()
    publish(created['id'])
    return created['id']


def publish(task_id):
    return api('POST', f'/workflow/tasks/{task_id}/publish', {'changeSummary': 'V2-03 isolated HTTP fixture'})


def backfill(task_id, policy='batch_only'):
    return api('POST', f'/workflow/tasks/{task_id}/backfill', {'startDate': DATE, 'endDate': DATE, 'bindingPolicy': policy})


def claim(task_id):
    return api('POST', f'/workflow/instances/claim?executorId={EXECUTOR}&taskId={task_id}')


def path(run):
    return f"/workflow/instances/{run['id']}"


def begin(run, reject=False):
    return api('POST', path(run) + f"/begin-submission?attemptId={run['activeAttemptId']}&executorId={EXECUTOR}", reject=reject)


def attach(run, reject=False):
    return api('POST', path(run) + '/external-job', {'attemptId': run['activeAttemptId'],
        'executorId': EXECUTOR, 'externalJobId': format(run['id'], '032x')}, reject=reject)


def complete(run, success=True, reject=False):
    return api('POST', path(run) + '/complete', {'attemptId': run['activeAttemptId'], 'executorId': EXECUTOR,
        'success': success, 'errorMessage': None if success else 'isolated callback fixture'}, reject=reject)


def observed(run):
    return next(item for item in api('GET', f"/workflow/instances?taskId={run['taskId']}") if item['id'] == run['id'])


def wait_for(run, predicate, label):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        value = observed(run)
        if predicate(value):
            return value
        time.sleep(1)
    raise AssertionError(label + ': ' + json.dumps(value))


def finish(run):
    begin(run); attach(run); complete(run)


def cleanup():
    for task_id in reversed(fixtures['tasks']):
        for run in api('GET', f'/workflow/instances?taskId={task_id}'):
            if run['status'] == 'running' and run['executorId'] == EXECUTOR:
                complete(run, False)
            elif run['status'] in ('waiting', 'queued'):
                api('POST', path(run) + '/cancel')
        api('DELETE', f'/sync-tasks/{task_id}')
    if '--docker-cleanup' in sys.argv and fixtures['tables']:
        from urllib.parse import urlparse
        assert urlparse(BASE).hostname in ('localhost', '127.0.0.1'), 'Docker cleanup is local-only'
        assert all(re.fullmatch(r'delivery_(available|blocked)_[0-9]+', name) for name in fixtures['tables'])
        names = ','.join("'" + name + "'" for name in fixtures['tables'])
        sql = "DELETE FROM dwh_table_meta WHERE paimon_db='ads' AND paimon_table IN (" + names + ");"
        subprocess.run(['docker', 'exec', '-i', 'rtdwh-mysql', 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot rtdwh_mgmt'], input=sql, text=True, check=True, stdout=subprocess.DEVNULL)


TOKEN = api('POST', '/auth/login', {'username': os.getenv('RTDWH_ADMIN_USER', 'admin'),
    'password': os.environ['RTDWH_ADMIN_PASSWORD']})['token']
if '--cleanup' in sys.argv:
    fixtures = json.loads(STATE.read_text()); cleanup(); print('PASS: dedicated HTTP tasks removed'); sys.exit(0)

prefix = 'smoke-delivery-' + str(int(time.time()))
try:
    up = task('upstream'); data = task('data-child'); control = task('control-child'); available = task('available-child')
    tables = ['delivery_available_' + str(up), 'delivery_blocked_' + str(up)]
    fixtures['tables'] = tables; save_state()
    definitions = [{'catalogName': 'rtdwh_paimon', 'databaseName': 'ads', 'tableName': name,
        'layer': 'ads', 'slaMinutes': 60, 'qualityGateEnabled': index == 1} for index, name in enumerate(tables)]
    outputs = api('PUT', f'/workflow/tasks/{up}/outputs', definitions)
    version = publish(up)
    for child, condition, output in ((data, 'data_available', outputs[1]['id']),
                                    (control, 'execution_success', None), (available, 'data_available', outputs[0]['id'])):
        api('POST', '/workflow/dependencies', {'upstreamTaskId': up, 'downstreamTaskId': child,
            'conditionType': condition, 'outputDatasetId': output})
        publish(child)

    original = backfill(up)[0]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        claimed = list(pool.map(lambda _: claim(up), range(6)))
    assert len([run for run in claimed if run is not None]) == 1
    original = next(run for run in claimed if run is not None)
    assert original['attemptCount'] == 1 and original['windowEnd'] in ('2026-09-06', [2026, 9, 6])
    attach(original, reject=True); begin(original); begin(original, reject=True); attach(original)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: complete(original), range(8)))
    assert all(run['status'] == 'success' for run in results)
    assert len({json.dumps(run['finishedAt']) for run in results}) == 1, 'Repeated callback changed completion time'
    wait_for(original, lambda run: run['deliveryStatus'] == 'blocked', 'delivery gate did not block')
    produced = [api('GET', f"/workflow/outputs/{output['id']}/productions") for output in outputs]
    assert all(len(rows) == 1 for rows in produced)
    assert [rows[0]['status'] for rows in produced] == ['available', 'blocked']
    assert all(len(api('GET', f"/workflow/productions/{rows[0]['id']}/checks")) == 1 for rows in produced)
    print('PASS: concurrent claim is exclusive; 8 completion callbacks produce exactly one delivery/check per output', flush=True)

    waiting = backfill(data, 'reuse_available')[0]
    controlled = backfill(control, 'reuse_available')[0]
    reusable = backfill(available, 'reuse_available')[0]
    wait_for(controlled, lambda run: run['status'] == 'queued', 'control dependency not released')
    wait_for(reusable, lambda run: run['status'] == 'queued', 'selected available output not released')
    assert observed(waiting)['status'] == 'waiting'
    binding = api('GET', path(reusable) + '/bindings')[0]
    assert binding['productionId'] == produced[0][0]['id'] and binding['upstreamVersionId'] == version['id']
    finish(claim(control)); finish(claim(available))
    print('PASS: blocked data edge waits; explicit control and selected available-output edges release with recorded binding', flush=True)

    batch = backfill(available)
    assert len(batch) == 2 and len({run['batchId'] for run in batch}) == 1
    batch_up = next(run for run in batch if run['taskId'] == up)
    batch_child = next(run for run in batch if run['taskId'] == available)
    binding = api('GET', path(batch_child) + '/bindings')[0]
    assert binding['upstreamInstanceId'] == batch_up['id'] and binding['productionId'] is None
    assert claim(available) is None
    finish(claim(up))
    wait_for(batch_child, lambda run: run['status'] == 'queued', 'batch child not released')
    binding = api('GET', path(batch_child) + '/bindings')[0]
    assert binding['upstreamInstanceId'] == batch_up['id'] and binding['productionId'] != produced[0][0]['id']
    finish(claim(available))
    print('PASS: default backfill creates upstreams and ignores old successful deliveries from another batch', flush=True)

    # Draft/new release changes cannot weaken the original run's frozen gate.
    definitions[1]['qualityGateEnabled'] = False
    api('PUT', f'/workflow/tasks/{up}/outputs', definitions); new_version = publish(up)
    api('POST', path(original) + '/recheck-delivery')
    wait_for(original, lambda run: run['deliveryStatus'] == 'blocked', 'frozen gate changed on recheck')
    checks = api('GET', f"/workflow/productions/{produced[1][0]['id']}/checks")
    assert len(checks) == 2 and all(item['status'] == 'blocked' for item in checks)
    new_reuse = backfill(available, 'reuse_available')[0]
    binding = api('GET', path(new_reuse) + '/bindings')[0]
    assert binding['upstreamVersionId'] == new_version['id'] and binding['productionId'] is None
    assert claim(available) is None
    print('PASS: rechecks retain old frozen gate and evidence; reuse never substitutes another published version', flush=True)

    retry = backfill(up)[0]; first = claim(up); assert retry['id'] == first['id']
    complete(first, False); api('POST', path(first) + '/retry'); second = claim(up)
    assert second['activeAttemptId'] != first['activeAttemptId'] and second['attemptCount'] == 2
    complete(first, reject=True)
    api('POST', path(first) + f"/heartbeat?attemptId={first['activeAttemptId']}&executorId={EXECUTOR}", reject=True)
    begin(second)
    api('POST', path(second) + '/cancel', reject=True)
    attach(second); complete(second, False)
    api('POST', path(second) + '/retry', reject=True)
    history = api('GET', path(second) + '/attempts')
    assert len(history) == 2 and history[0]['externalJobId'] and history[1]['externalJobId'] is None
    print('PASS: pre-submit retry gets a new attempt; stale callbacks fail; submitted writes cannot replay or disappear', flush=True)
finally:
    if '--keep' not in sys.argv:
        cleanup()
    else:
        print('Fixture state retained for UI review:', STATE, flush=True)
