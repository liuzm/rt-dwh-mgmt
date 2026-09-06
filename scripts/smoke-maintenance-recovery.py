#!/usr/bin/env python3
"""Local V29 recovery test using a fault proxy in front of the real SQL Gateway.
Requires RTDWH_ADMIN_PASSWORD, Docker and the local Compose environment.
--keep retains dedicated UI fixtures and the proxy until tmp/maintenance-smoke-release exists.
--cleanup restores recorded settings and removes only this script's exact fixtures.
No SQL bodies, credentials or full container environments are persisted or printed.
"""
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = 'http://127.0.0.1:8080'
UPSTREAM = 'http://127.0.0.1:9083'
PROXY = 'http://host.docker.internal:19083'
STATE = Path('tmp/maintenance-smoke-state.json')
RELEASE = Path('tmp/maintenance-smoke-release')
TOKEN = ''
state = {'prefix': 'smoke_maint_' + str(int(time.time())), 'tables': [], 'operations': [], 'sessions': [], 'engineOperations': [], 'roles': [], 'users': []}
fault = {'block_status': False, 'fail_delete': False, 'drop_phase': None, 'calls': [], 'dropped': []}
mutex = threading.Lock()
session = None


def save():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n'); STATE.chmod(0o600)


def api(method, path, body=None, token=None, expect=None):
    req = Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                  headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + (TOKEN if token is None else token)})
    try:
        with urlopen(req, timeout=100) as response: value = json.load(response)
    except HTTPError as error:
        value = json.load(error)
        if expect and error.code in expect:
            return {'rejected': error.code, 'message': value.get('message')}
        raise AssertionError(f'{method} {path}: HTTP {error.code}: {value.get("message")}') from None
    assert not expect, 'Expected denial: ' + path
    assert value.get('code') == 0, value.get('message')
    return value.get('data')


def db(sql):
    return subprocess.run(['docker', 'exec', '-i', 'rtdwh-mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B rtdwh_mgmt'], input=sql, text=True, capture_output=True, check=True).stdout.strip()


def gw(method, path, body=None):
    with urlopen(Request(UPSTREAM + path, method=method, data=None if body is None else json.dumps(body).encode(),
                         headers={'Content-Type': 'application/json'}), timeout=100) as response:
        return json.load(response)


def execute(sql):
    op = gw('POST', f'/v1/sessions/{session}/statements', {'statement': sql, 'executionConfig': {'execution.runtime-mode': 'batch', 'parallelism.default': '1'}})['operationHandle']
    for _ in range(300):
        status = gw('GET', f'/v1/sessions/{session}/operations/{op}/status')['status']
        if status == 'FINISHED': return
        assert status not in ('ERROR', 'CANCELED', 'CLOSED'), 'Fixture SQL failed: ' + op
        time.sleep(.3)
    raise AssertionError('Fixture operation timed out: ' + op)


def initialize():
    global session
    env = dict(item.split('=', 1) for item in json.loads(subprocess.check_output(
        ['docker', 'inspect', '--format', '{{json .Config.Env}}', 'rtdwh-backend'], text=True)) if '=' in item)
    session = gw('POST', '/v1/sessions', {})['sessionHandle']
    lit = lambda value: "'" + value.replace("'", "''") + "'"
    options = {'type': 'paimon', 'metastore': 'jdbc', 'uri': env['PAIMON_JDBC_URI'], 'jdbc.user': env['PAIMON_JDBC_USER'],
               'jdbc.password': env['PAIMON_JDBC_PASSWORD'], 'catalog-key': env.get('PAIMON_CATALOG_KEY', 'rtdwh'), 'warehouse': env['PAIMON_WAREHOUSE']}
    execute('CREATE CATALOG rtdwh_paimon WITH (' + ','.join(lit(k) + '=' + lit(v) for k, v in options.items()) + ')')
    execute('USE CATALOG rtdwh_paimon')


class Proxy(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def request_body(self):
        if self.headers.get('Transfer-Encoding', '').lower() != 'chunked':
            return self.rfile.read(int(self.headers.get('Content-Length', 0)))
        chunks = []
        while True:
            size = int(self.rfile.readline().split(b';', 1)[0].strip(), 16)
            if size == 0:
                while self.rfile.readline() not in (b'\r\n', b'\n', b''): pass
                return b''.join(chunks)
            chunks.append(self.rfile.read(size))
            assert self.rfile.read(2) == b'\r\n'
    def dispatch(self):
        raw = self.request_body()
        phase = None
        if self.command == 'POST' and self.path.endswith('/statements'):
            statement = json.loads(raw).get('statement', '').lstrip().upper()
            phase = 'CALL' if statement.startswith('CALL ') else 'CATALOG' if statement.startswith('CREATE CATALOG ') else 'USE'
        with mutex:
            fault['calls'].append((self.command, self.path, phase))
            reject = fault['block_status'] and self.path.endswith('/status') or fault['fail_delete'] and self.command == 'DELETE'
        if reject:
            code, body = 503, b'{"errors":["Injected local connectivity failure"]}'
        else:
            req = Request(UPSTREAM + self.path, method=self.command, data=raw if raw else None, headers={'Content-Type': 'application/json'})
            try:
                with urlopen(req, timeout=100) as response: code, body = response.status, response.read()
            except HTTPError as error: code, body = error.code, error.read()
            with mutex:
                if self.command == 'POST' and code == 200:
                    if self.path == '/v1/sessions': state['sessions'].append(json.loads(body)['sessionHandle']); save()
                    elif phase:
                        state['engineOperations'].append({'phase': phase, 'session': self.path.split('/')[3], 'operation': json.loads(body)['operationHandle']}); save()
                if phase and fault['drop_phase'] == phase and code == 200:
                    fault['drop_phase'] = None
                    fault['dropped'].append({'phase': phase, 'session': self.path.split('/')[3], 'operation': json.loads(body)['operationHandle']})
                    self.connection.shutdown(socket.SHUT_RDWR); self.connection.close(); return
        self.send_response(code); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body))); self.end_headers()
        self.wfile.write(body)
    do_GET = do_POST = do_DELETE = dispatch


def detail(op): return api('GET', f'/dwh/maintenance/{op}')


def wait(op, condition, timeout=100):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        current = detail(op)
        if condition(current['operation']): return current
        time.sleep(.4)
    raise AssertionError('Maintenance did not reach expected state: ' + json.dumps(current['operation'], ensure_ascii=False))


def recover(op, action, **extra):
    for _ in range(10):
        current = detail(op)['operation']
        try:
            return api('POST', f'/dwh/maintenance/{op}/recovery', {'expectedRevision': current['revision'], 'action': action, 'reason': '专用本地恢复验收', **extra})
        except AssertionError as error:
            if '状态已变化' not in str(error): raise
    raise AssertionError('Recovery revision never stabilized')


def start(table_id):
    result = api('POST', f'/dwh/tables/{table_id}/compact')
    op = int(result['operationId']); state['operations'].append(op); save(); return op


def restart():
    subprocess.run(['docker', 'restart', 'rtdwh-backend'], check=True, stdout=subprocess.DEVNULL)
    for _ in range(60):
        try:
            with urlopen(BASE + '/actuator/health', timeout=2) as response:
                if json.load(response)['status'] == 'UP': return
        except Exception: pass
        time.sleep(1)
    raise AssertionError('Backend did not recover')


def config(**changes): return {**state['settings'], **changes}


def cleanup():
    if 'settings' in state: api('PUT', '/settings/flink-cluster', state['settings'])
    # Observe every potentially mutating test CALL before removing the associated session.
    for item in state.get('engineOperations', []):
        if item['phase'] != 'CALL': continue
        for _ in range(120):
            try: status = gw('GET', f'/v1/sessions/{item["session"]}/operations/{item["operation"]}/status')['status']
            except HTTPError: break  # Session already closed by the verified terminal coordinator.
            if status in ('FINISHED', 'ERROR', 'CANCELED', 'CLOSED'): break
            time.sleep(.5)
        else: raise AssertionError('Test CALL is still active; retain fixtures for later cleanup')
    for sid in state['sessions']:
        assert re.fullmatch(r'[A-Za-z0-9_-]{1,64}', sid)
        try: gw('DELETE', '/v1/sessions/' + sid)
        except HTTPError: pass
    # UI review may exceed the Gateway idle timeout. Use a fresh fixture-only session for DROP.
    if session:
        try: gw('DELETE', '/v1/sessions/' + session)
        except HTTPError: pass
    initialize()
    for name in state['tables']:
        assert re.fullmatch(r'smoke_maint_\d+_[ab]', name)
        execute('DROP TABLE IF EXISTS ods.' + name)
        tid = db(f"SELECT id FROM dwh_table_meta WHERE paimon_db='ods' AND paimon_table='{name}'")
        if tid:
            tid = int(tid)
            db(f'DELETE FROM maintenance_recovery_event WHERE maintenance_id IN (SELECT id FROM table_maintenance_log WHERE table_meta_id={tid}); '
               f'DELETE FROM table_maintenance_log WHERE table_meta_id={tid}; DELETE FROM maintenance_coordination_lock WHERE table_meta_id={tid}; '
               f'DELETE FROM dwh_column_meta WHERE table_meta_id={tid}; DELETE FROM dwh_schema_history WHERE table_meta_id={tid}; DELETE FROM dwh_table_meta WHERE id={tid};')
    for uid in state['users']:
        db(f'DELETE FROM sys_user_role WHERE user_id={int(uid)}; DELETE FROM sys_user WHERE id={int(uid)};')
    for rid in state['roles']: api('DELETE', '/admin/roles/' + str(rid))
    STATE.unlink(missing_ok=True); RELEASE.unlink(missing_ok=True)
    print('PASS: original runtime settings restored; exact recovery fixtures cleaned', flush=True)


def main():
    global TOKEN, state
    parser = argparse.ArgumentParser(); parser.add_argument('--keep', action='store_true'); parser.add_argument('--cleanup', action='store_true'); args = parser.parse_args()
    TOKEN = api('POST', '/auth/login', {'username': 'admin', 'password': os.environ['RTDWH_ADMIN_PASSWORD']}, token='')['token']
    if args.cleanup:
        state = json.loads(STATE.read_text())
        try: cleanup()
        finally:
            if session:
                try: gw('DELETE', '/v1/sessions/' + session)
                except HTTPError: pass
        return
    assert not STATE.exists(), 'Existing recovery fixtures: use --cleanup first'
    state['settings'] = api('GET', '/settings/flink-cluster'); save()
    server = ThreadingHTTPServer(('127.0.0.1', 19083), Proxy)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        initialize()
        for suffix in ('a', 'b'):
            name = state['prefix'] + '_' + suffix
            execute(f"CREATE TABLE ods.{name} (id BIGINT, amount BIGINT, PRIMARY KEY(id) NOT ENFORCED) WITH ('bucket'='1')")
            state['tables'].append(name); save(); execute(f'INSERT INTO ods.{name} VALUES (1,10),(2,20)')
        api('POST', '/dwh/sync-metadata')
        assets = {item['paimonTable']: item for item in api('GET', '/dwh/tables?keyword=' + state['prefix'])}
        a, b = [assets[name]['id'] for name in state['tables']]
        api('PUT', '/settings/flink-cluster', config(sqlGatewayUrl=PROXY, sqlGatewayEnabled=True))
        # Concurrent user requests contend for the same DB guard, independent of scheduler calls.
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(api, 'POST', f'/dwh/tables/{a}/compact') for _ in range(2)]
        accepted = []
        for future in futures:
            try: accepted.append(future.result())
            except AssertionError as error: assert 'HTTP 409' in str(error), str(error)
        assert len(accepted) == 1
        op = int(accepted[0]['operationId']); state['operations'].append(op); save()
        wait(op, lambda value: value['executionPhase'] == 'CALL' and value.get('operationId'))
        fault['block_status'] = True; fault['fail_delete'] = True
        original = wait(op, lambda value: value['status'] == 'unknown')['operation']; state['sessions'].append(original['sessionId']); save()
        # Actual setting change + process restart; old operation must still use the live original proxy.
        api('PUT', '/settings/flink-cluster', config(sqlGatewayUrl='http://host.docker.internal:19084', restApiUrl='http://host.docker.internal:19085', sqlGatewayEnabled=False))
        restart(); fault['block_status'] = False
        current = wait(op, lambda value: value['status'] == 'success' and value['cleanupStatus'] == 'pending')['operation']
        assert current['gatewayUrl'] == PROXY and current['operationId'] == original['operationId']
        assert current['flinkUrl'] == state['settings']['restApiUrl']
        first_cleanup = current['cleanupAttempts']; assert first_cleanup >= 1
        restart(); fault['fail_delete'] = False
        cleaned = wait(op, lambda value: value['cleanupStatus'] == 'done')['operation']
        assert cleaned['cleanupAttempts'] > first_cleanup and cleaned['finishedAt'] == current['finishedAt']
        print('PASS: real Compact, concurrent exclusion, old endpoint after config change/restart, durable cleanup retry', flush=True)
        api('PUT', '/settings/flink-cluster', config(sqlGatewayUrl=PROXY, sqlGatewayEnabled=True))

        # Lose an accepted CATALOG response, cancel only the proven pre-CALL preparation.
        fault['drop_phase'] = 'CATALOG'; before_calls = len(fault['calls']); cancelled = start(b)
        missing = wait(cancelled, lambda value: value['status'] == 'unknown' and not value.get('operationId'))['operation']
        assert fault['dropped'][-1]['phase'] == 'CATALOG'; state['sessions'].append(missing['sessionId']); save()
        recover(cancelled, 'observe')
        assert sum(call[2] == 'CATALOG' for call in fault['calls'][before_calls:]) == 1
        result = recover(cancelled, 'cancel_preparation')['operation']; assert result['status'] == 'failed' and result['cleanupStatus'] == 'done'

        # Lose a real CALL acknowledgement. Original operation finishes at Gateway, platform preserves unknown.
        fault['drop_phase'] = 'CALL'; before_calls = len(fault['calls']); unknown = start(b)
        lost = wait(unknown, lambda value: value['executionPhase'] == 'CALL' and value['status'] == 'unknown' and not value.get('operationId'))['operation']
        dropped = fault['dropped'][-1]; assert dropped['phase'] == 'CALL'
        state['sessions'].append(lost['sessionId']); save()
        restart(); recover(unknown, 'observe'); recover(unknown, 'note')
        assert sum(call[2] == 'CALL' for call in fault['calls'][before_calls:]) == 1, 'POST was replayed'
        current = detail(unknown)['operation']; assert current['status'] == 'unknown' and not current.get('finishedAt')
        api('POST', f'/dwh/maintenance/{unknown}/recovery', {'expectedRevision': current['revision'], 'action': 'cancel_preparation', 'reason': '不应允许'}, expect=(409,))
        api('POST', f'/dwh/tables/{b}/compact', expect=(409,))
        # Verify the real dropped operation is terminal before fixture cleanup, without changing platform evidence.
        for _ in range(120):
            observed = gw('GET', f'/v1/sessions/{dropped["session"]}/operations/{dropped["operation"]}/status')['status']
            if observed in ('FINISHED', 'ERROR', 'CANCELED'): break
            time.sleep(.5)
        assert observed == 'FINISHED'
        print('PASS: accepted response loss is not replayed; preparation cancellation is bounded; unknown CALL remains blocked', flush=True)

        # Historical target authorization is separate from current mutable metadata.
        permissions = [item['id'] for item in api('GET', '/admin/permissions') if item['permCode'] in ('dwh:view', 'dwh:manage')]
        tokens = []
        for name in state['tables']:
            role = api('POST', '/admin/roles', {'roleCode': name, 'roleName': name, 'permissionIds': permissions,
                'dataScopes': [{'catalogPattern': 'rtdwh_paimon', 'databasePattern': 'ods', 'tablePattern': name}]})
            state['roles'].append(role['id']); save()
            import secrets
            password = secrets.token_urlsafe(24)
            user = api('POST', '/admin/users', {'username': name, 'password': password, 'roleIds': [role['id']]})
            state['users'].append(user['id']); save()
            tokens.append(api('POST', '/auth/login', {'username': name, 'password': password}, token='')['token'])
        api('GET', f'/dwh/maintenance/{op}', token=tokens[0]); api('GET', f'/dwh/maintenance/{unknown}', token=tokens[0], expect=(403,))
        api('POST', f'/dwh/maintenance/{unknown}/recovery', {'expectedRevision': current['revision'], 'action': 'note', 'reason': '越权测试'}, token=tokens[0], expect=(403,))
        names = [row['id'] for row in api('GET', '/dwh/maintenance/logs', token=tokens[0])]; assert op in names and unknown not in names
        assert detail(unknown)['operation']['tableName'] == state['tables'][1]
        print('PASS: recovery detail, timeline, mutations and lists obey frozen target permissions', flush=True)
        api('PUT', '/settings/flink-cluster', state['settings'])
        for name in state['tables']:
            result = api('POST', '/query/execute', {'sql': f'SELECT SUM(amount) AS amount FROM {name}', 'catalog': 'rtdwh_paimon', 'database': 'ods'})
            assert result['status'] == 'success' and result['rows'] == [[30]], result.get('errorMsg')
        state['uiOperationId'] = unknown; save()
        report = {'verified': ['real_paimon_compact', 'two_concurrent_requests_one_accepted', 'old_endpoint_after_runtime_change', 'backend_restart',
            'cleanup_failure_and_retry_after_restart', 'accepted_catalog_response_lost', 'accepted_call_response_lost', 'no_post_replay',
            'pre_call_cancellation_only', 'frozen_target_permissions', 'data_sum_unchanged'], 'manualJobPositiveEvidence': 'unit tests only',
            'faultMethod': 'local forwarding proxy drops accepted responses; actual backend container restarts', 'ui': 'pending'}
        Path('docs/validation/v29-maintenance-recovery-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        if args.keep:
            print(f'UI READY: maintenance #{unknown}; release fixtures with tmp/maintenance-smoke-release', flush=True)
            while not RELEASE.exists(): time.sleep(1)
        cleanup()
    finally:
        # Failures retain evidence/fixture ids for explicit --cleanup, but never leave edited runtime endpoints active.
        fault['block_status'] = False; fault['fail_delete'] = False
        if 'settings' in state: api('PUT', '/settings/flink-cluster', state['settings'])
        if session:
            try: gw('DELETE', '/v1/sessions/' + session)
            except HTTPError: pass
        server.shutdown()


if __name__ == '__main__': main()
