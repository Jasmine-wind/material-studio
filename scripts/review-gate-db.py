"""Real MySQL fault regression. ONLY run on disposable Compose acceptance data.

Uses the existing mysql container/admin, never changes credentials or production code.
Temporarily rejects tasks UPDATE; always drops its own trigger. No GET drives settlement.
"""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
API = os.environ.get('REGATE_API_URL', 'http://localhost:8080/api')
TRIGGER = 'regate_transient_write_outage'


def sql(statement):
    result = subprocess.run([
        'docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot -N material_studio -e "$1"',
        'sh', statement,
    ], cwd=ROOT, capture_output=True, text=True, check=True, encoding='utf-8', timeout=15)
    return result.stdout.strip()


def api(route, body, content_type='application/json'):
    request = urllib.request.Request(API + route, data=body, headers={
        'X-Demo-User': 'alice', 'Content-Type': content_type,
    })
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.status, json.load(response)


def eventually(read, predicate, timeout=12):
    deadline = time.monotonic() + timeout
    while True:
        value = read()
        if predicate(value):
            return value
        if time.monotonic() > deadline:
            raise AssertionError(f'Timed out: {value}')
        time.sleep(0.05)


def row(task_id):
    return json.loads(sql(f"""SELECT JSON_OBJECT('id',id,'status',status,'stage',stage,
        'failedStage',failed_stage,'errorMessage',error_message,'finishedAt',finished_at,
        'resultKey',result_storage_key) FROM tasks WHERE id='{task_id}'"""))


def scenario(fail_stage):
    boundary = 'regate-' + uuid.uuid4().hex
    image = (ROOT / 'frontend/public/demo/sample-1.png').read_bytes()
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="regate.png"\r\n'
            'Content-Type: image/png\r\n\r\n').encode() + image + f'\r\n--{boundary}--\r\n'.encode()
    status, asset = api('/assets', body, f'multipart/form-data; boundary={boundary}')
    assert status == 201
    status, accepted = api('/tasks', json.dumps({
        'templateId': 'lunar', 'assetIds': [asset['id']],
        'simulation': {'delayMs': 500, 'failStage': fail_stage},
    }).encode())
    assert status == 202
    task_id = accepted['id']
    assert str(uuid.UUID(task_id)) == task_id
    eventually(lambda: row(task_id), lambda r: r['stage'] == 'CHECK_MATERIAL')
    sql(f"CREATE TRIGGER {TRIGGER} BEFORE UPDATE ON tasks FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='regate transient database write outage'")
    try:
        time.sleep(1.8)  # Both the original stage/failure write and a settlement retry encounter the outage.
        before_recovery = row(task_id)
        assert before_recovery['status'] == 'RUNNING', before_recovery
    finally:
        sql(f'DROP TRIGGER IF EXISTS {TRIGGER}')
    assert sql('SELECT 1') == '1'
    # Only direct SQL reads: the Java process must settle without any GET or restart.
    terminal = eventually(lambda: row(task_id), lambda r: r['status'] == 'FAILED')
    assert terminal['id'] == task_id and terminal['finishedAt'], terminal
    assert terminal['stage'] == 'CHECK_MATERIAL' and terminal['resultKey'] is None, terminal
    expected_stage = fail_stage or 'GENERATE'
    assert terminal['failedStage'] == expected_stage, terminal
    expected_reason = '演示：CHECK_MATERIAL 阶段指定失败' if fail_stage else 'regate transient database write outage'
    assert expected_reason in terminal['errorMessage'], terminal
    # Observe well past the remaining nominal stages; no later production or terminal reversal.
    time.sleep(1.6)
    assert row(task_id) == terminal
    print(json.dumps({'scenario': fail_stage or 'stage UPDATE failure', 'beforeRecovery': before_recovery,
                      'afterRecovery': terminal, 'noGetDuringSettlement': True}, ensure_ascii=False), flush=True)
    return terminal


if __name__ == '__main__':
    records = [scenario(None), scenario('CHECK_MATERIAL')]
    output = ROOT / 'artifacts/review-gate-db.json'
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS: original failure stage/reason retained; FAILED after recovery without GET/restart.')
