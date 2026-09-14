"""验证新建 Activity 入场期间按返回键，再次新建可正常打开。"""
import json
import time
import device_ui as ui

results = []
for index in range(3):
    tree = ui.wait_for('fab_create')
    pid = ui.adb('shell', 'pidof', ui.PACKAGE).strip().decode()
    ui.adb('shell', 'input', 'tap', *ui.center(ui.find(tree, 'resource-id', 'fab_create')))
    start = time.perf_counter()
    for _ in range(30):
        top = ui.adb('shell', 'dumpsys', 'activity', 'activities').decode(errors='replace')
        if any('topResumedActivity' in row and 'DetailActivity' in row for row in top.splitlines()):
            break
        time.sleep(.05)
    else:
        raise RuntimeError('DetailActivity did not resume')
    delay = time.perf_counter() - start
    ui.adb('shell', 'input', 'keyevent', 4)
    ui.wait_for('fab_create')
    after_pid = ui.adb('shell', 'pidof', ui.PACKAGE).strip().decode()
    assert after_pid == pid, 'App restarted during interruption'
    results.append({'iteration':index, 'resumedAfterSeconds':delay, 'pid':pid, 'returned':True})
tree = ui.wait_for('fab_create')
ui.adb('shell', 'input', 'tap', *ui.center(ui.find(tree, 'resource-id', 'fab_create')))
tree = ui.wait_for('et_title')
ui.capture('interruption-final-open')
ui.adb('shell', 'input', 'tap', *ui.center(ui.find(tree, 'resource-id', 'ib_back')))
ui.wait_for('fab_create')
(ui.ROOT / 'interruption.json').write_text(json.dumps(results, indent=2))
print(json.dumps(results))
