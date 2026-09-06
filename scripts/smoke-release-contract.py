#!/usr/bin/env python3
"""Local HTTP regression for immutable task parameters and schedule revisions.
Requires RTDWH_ADMIN_PASSWORD. Creates and cleans up one dedicated task.
"""
import json
import os
import time
import urllib.request
import urllib.error

BASE = os.getenv('RTDWH_API_BASE', 'http://127.0.0.1:8080')
TOKEN = ''
TASK = None


def api(method, path, body=None, rejected=False):
    request = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                     method=method, headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + TOKEN})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        if rejected and error.code in (400, 403, 409):
            return None
        raise
    if rejected:
        assert result.get('code') != 0, 'Invalid operation unexpectedly accepted'
        return None
    assert result.get('code') == 0, result.get('message')
    return result.get('data')


try:
    TOKEN = api('POST', '/auth/login', {'username': os.getenv('RTDWH_ADMIN_USER', 'admin'),
                                       'password': os.environ['RTDWH_ADMIN_PASSWORD']})['token']
    sql = "CREATE TEMPORARY TABLE contract_sink (region STRING, dt STRING, n BIGINT) WITH ('connector'='blackhole'); INSERT INTO contract_sink SELECT ${region}, '${bizdate}', CAST(${n} AS BIGINT)"
    task = api('POST', '/sync-tasks', {'taskName': 'smoke-contract-' + str(int(time.time())), 'taskType': 'etl',
            'executionMode': 'scheduled', 'scenarioCode': 'scheduled_sql_output', 'flinkSql': sql,
            'syncStrategy': 'full_then_incremental', 'parallelism': 1})
    TASK = task['id']
    path = '/workflow/tasks/' + str(TASK)
    schema = [{'name': 'region', 'type': 'string', 'required': True}, {'name': 'n', 'type': 'integer', 'defaultValue': 10}]
    api('PUT', path + '/parameters', {'parameterSchemaJson': json.dumps(schema)})
    published = api('POST', path + '/publish', {'changeSummary': 'HTTP parameter contract regression'})
    snapshot = json.loads(published['snapshotJson'])
    assert snapshot['runtimeConfigHash'] and 'credentialRefs' in snapshot['runtimeConfigJson']
    assert json.loads(snapshot['parameterSchemaJson']) == schema
    for parameters in ({}, {'region': 'east', 'n': '1; DROP TABLE t'}, {'region': 'east', 'unknown': 1}):
        api('POST', path + '/backfill', {'startDate': '2026-09-05', 'endDate': '2026-09-05', 'parametersJson': json.dumps(parameters)}, rejected=True)
    print('PASS: published types reject missing, unknown and invalid parameters', flush=True)

    for hour in (2, 4):
        api('PUT', path + '/schedule', {'cronExpression': f'0 0 {hour} * * *', 'timezone': 'Asia/Shanghai',
            'businessDateOffset': -1, 'parametersJson': '{"region":"east"}', 'enabled': False})
    revisions = api('GET', path + '/schedule/revisions')
    assert [revision['revisionNo'] for revision in revisions] == [2, 1]
    assert revisions[1]['cronExpression'] == '0 0 2 * * *'
    print('PASS: editing schedules retains both immutable revisions', flush=True)

    schema[1]['defaultValue'] = 99
    api('PUT', path + '/parameters', {'parameterSchemaJson': json.dumps(schema)})
    run = api('POST', path + '/backfill', {'startDate': '2026-09-05', 'endDate': '2026-09-05', 'parametersJson': '{"region":"east"}'})[0]
    assert run['definitionVersionId'] == published['id']
    assert json.loads(run['parametersJson'])['n'] == 10
    print('PASS: unpublished draft defaults do not affect new instances', flush=True)

    deadline = time.time() + 150
    while time.time() < deadline:
        runs = api('GET', '/workflow/instances?taskId=' + str(TASK))
        observed = next(item for item in runs if item['id'] == run['id'])
        if observed['status'] in ('success', 'failed', 'cancelled'):
            assert observed['status'] == 'success', observed.get('errorMessage')
            if observed.get('deliveryStatus') == 'available':
                break
        time.sleep(2)
    else:
        raise AssertionError('Flink workflow did not reach success')
    checks = api('GET', path + '/access-checks')
    assert any(check['instanceId'] == run['id'] and check['allowed'] for check in checks)
    print('PASS: Flink finished bound instance and permission recheck was persisted', flush=True)

    # Only one due trigger is needed. Disable the schedule as soon as the bound instance is observed.
    schedule = api('PUT', path + '/schedule', {'cronExpression': '0/15 * * * * *', 'timezone': 'UTC',
        'businessDateOffset': 0, 'parametersJson': '{"region":"east"}', 'enabled': True})
    deadline = time.time() + 65
    while time.time() < deadline:
        runs = api('GET', '/workflow/instances?taskId=' + str(TASK))
        scheduled = [item for item in runs if item['triggerType'] == 'schedule']
        if scheduled:
            assert all(item['scheduleRevisionId'] == schedule['activeRevisionId'] and item['scheduledAt'] for item in scheduled)
            break
        time.sleep(1)
    else:
        raise AssertionError('Schedule did not trigger')
    api('DELETE', path + '/schedule')
    assert api('GET', path + '/schedule/revisions')[0]['action'] == 'delete'
    print('PASS: scheduled instances retain trigger revision after schedule deletion', flush=True)
finally:
    if TASK is not None:
        try:
            api('DELETE', '/workflow/tasks/' + str(TASK) + '/schedule')
            for instance in api('GET', '/workflow/instances?taskId=' + str(TASK)):
                if instance['status'] in ('waiting', 'queued', 'running'):
                    api('POST', '/workflow/instances/' + str(instance['id']) + '/cancel')
            api('DELETE', '/sync-tasks/' + str(TASK))
        except Exception as error:
            print('Cleanup needs attention:', type(error).__name__, 'task', TASK, flush=True)
