"""只把本轮明确命名的测试记事移入可恢复的回收站。"""
import json
import re
import argparse
import device_ui as ui
import device_performance as perf

def tap(attr,value,expect=None):
    tree=ui.dump();ui.adb('shell','input','tap',*ui.center(ui.find(tree,attr,value)))
    return ui.wait_for(expect) if expect else None

TEST_TITLE=r'(?:CodexAnimation|ClaudeBorderTest)[a-zA-Z0-9_-]+'

def trash(titles):
    assert titles and len(titles)==len(set(titles)) and all(re.fullmatch(TEST_TITLE,t) for t in titles)
    tree=ui.dump();x,y=ui.center(ui.find(tree,'text',titles[0]))
    ui.adb('shell','input','swipe',x,y,x,y,700)
    for index,title in enumerate(titles):
        if index: tap('text',title)
        tree=ui.dump()
        assert any(re.fullmatch(str(index+1)+r'\s*/\s*\d+',n.get('text','')) for n in tree.iter('node')), '选择数量不符'
    delete_labels=['Delete selected things','删除所选记事']
    if any(n.get('content-desc') in delete_labels for n in tree.iter('node')):
        perf.tap_label('content-desc',delete_labels)
    else:
        perf.tap_label('content-desc',['More options','更多选项'])
        perf.tap_label('text',delete_labels)
    tree=ui.dump();body=' '.join(n.get('text','') for n in tree.iter('node'))
    expected_count=(str(len(titles))+' in total' in body or '共'+str(len(titles))+'件' in body)
    recoverable=('restored from the recycle bin' in body.lower() or '可在回收站恢复' in body)
    assert expected_count and recoverable, body
    perf.tap_label('text',['CONFIRM','确定'],'fab_create')
    tree=ui.dump()
    assert not any(n.get('text') in titles for n in tree.iter('node'))

def status(current,index):
    perf.tap_label('content-desc',['Open Navigation Drawer','打开导航抽屉'])
    tree=ui.dump()
    labels={'Underway':['Underway','正在进行'],'Finished':['Finished','已完成']}
    node=next(n for n in tree.iter('node') if n.get('text') in labels[current] and not n.get('resource-id'))
    parents={c:n for n in tree.iter() for c in n}
    for _ in range(3):node=parents[node]
    choices=[n for n in node if n.get('class')=='android.widget.FrameLayout' and n.get('clickable')=='true']
    assert len(choices)==3
    ui.adb('shell','input','tap',*ui.center(choices[index]))
    ui.adb('shell','input','keyevent',4)
    ui.wait_for('fab_create')

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--underway',nargs='+',required=True)
    p.add_argument('--finished',nargs='*',default=[])
    p.add_argument('--expected-count',required=True)
    a=p.parse_args()
    perf.control({})
    trash(a.underway)
    if a.finished:
        status('Underway',1)
        trash(a.finished)
        status('Finished',0)
    tree=ui.dump('cleanup-final')
    result={'underwayCount':ui.find(tree,'resource-id','tv_header_subtitle').get('text'),
            'trashed':a.underway+a.finished}
    (ui.ROOT/'cleanup.json').write_text(json.dumps(result),encoding='utf-8')
    assert result['underwayCount']==a.expected_count,result
    print(json.dumps(result))
