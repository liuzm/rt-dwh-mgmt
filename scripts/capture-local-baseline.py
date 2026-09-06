#!/usr/bin/env python3
"""Read-only local acceptance inventory. Writes no passwords, tokens or container Env."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import time
from urllib.request import urlopen

COMPOSE = ['docker', 'compose', '--env-file', 'deploy/.env', '-f', 'deploy/docker-compose.yml', '-f', 'deploy/docker-compose.local.yml']


def command(args):
    return subprocess.check_output(args, text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default='tmp/local-acceptance-baseline.json')
    args = parser.parse_args()
    data = {'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'gitHead': command(['git', 'rev-parse', 'HEAD']), 'workingTreeDirty': bool(command(['git', 'status', '--porcelain'])),
            'mode': 'single-host Compose; read-only inventory and sequential idle HTTP samples', 'containers': []}
    for cid in command(COMPOSE + ['ps', '-q']).splitlines():
        raw = command(['docker', 'inspect', '--format',
                       '{"name":{{json .Name}},"imageId":{{json .Image}},"imageTag":{{json .Config.Image}},"state":{{json .State.Status}},"mounts":{{json .Mounts}}}', cid])
        info = json.loads(raw)
        info['repoDigests'] = json.loads(command(['docker', 'image', 'inspect', info['imageId'], '--format', '{{json .RepoDigests}}']))
        # Docker Desktop may report the same host bind with a /host_mnt prefix.
        # Keep raw evidence and a normalized hash without publishing host paths.
        info['mounts'] = [{'type': mount['Type'], 'target': mount['Destination'],
                           'sourceHash': hashlib.sha256(mount['Source'].encode()).hexdigest(),
                           'hostSourceHash': hashlib.sha256((mount['Source'].removeprefix('/host_mnt')
                                if mount['Type'] == 'bind' else mount['Source']).encode()).hexdigest()}
                          for mount in info['mounts'] if mount['Destination'] in ['/data/paimon', '/tmp/flink-savepoints']]
        data['containers'].append(info)
    data['connectors'] = command(['docker', 'exec', 'rtdwh-flink-sql-gateway', 'sh', '-c',
                                  'sha256sum /opt/flink/lib/*cdc*.jar /opt/flink/lib/*paimon*.jar /opt/flink/lib/mysql-connector*.jar']).splitlines()
    stats = command(['docker', 'exec', 'rtdwh-mysql', 'sh', '-c',
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B rtdwh_mgmt -e "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1; SELECT COUNT(*) FROM sync_task; SELECT COUNT(*) FROM dwh_table_meta; SELECT COUNT(*) FROM quality_rule; SELECT COUNT(*) FROM data_service_definition;"'])
    data['databaseCounts'] = dict(zip(['migrationVersion', 'tasks', 'assets', 'qualityRules', 'dataServices'], map(int, stats.splitlines())))
    for name, url in [('health', 'http://127.0.0.1:8080/actuator/health'), ('frontend', 'http://127.0.0.1:18080/')]:
        timings = []
        for _ in range(20):
            start = time.monotonic()
            with urlopen(url, timeout=10) as response:
                body = response.read()
                if name == 'health': assert json.loads(body)['status'] == 'UP'
            timings.append(round((time.monotonic() - start) * 1000, 2))
        data[name] = {'samples': len(timings), 'concurrency': 1, 'medianMs': statistics.median(timings),
                      'p95Ms': sorted(timings)[18], 'maxMs': max(timings)}
    path = Path(args.output)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    print(f'Baseline: {len(data["containers"])} containers, migration V{data["databaseCounts"]["migrationVersion"]}; {path}')


if __name__ == '__main__':
    main()
