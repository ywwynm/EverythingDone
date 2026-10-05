"""从完整导出清单生成报告，不把结构误差当成像素一致率。"""
from pathlib import Path
import html,json

HERE=Path(__file__).resolve().parents[1]


def main():
    out=HERE/'videos';m=json.loads((out/'shared-field-manifest.json').read_text('utf-8'))
    names={n:json.loads((HERE/'assets'/n/'scene.json').read_text('utf-8'))['title'] for n in dict.fromkeys(v['scene'] for v in m['videos'])}
    rows=[];improvements=[]
    for n in names:
        q=m['metrics'].get(n,{})
        title=html.escape(names[n])
        link=f'<a href="shared-field.html#shared-field-{n}-comparison-0.5x.mp4">{title}</a>'
        if not q:
            rows.append(f'<tr><td>{link}</td><td colspan="6">背景真值及片尾不完整，仅作可观测区目视对照</td></tr>');continue
        before=q['before'];after=q['candidate'];change=100*(1-after['rgb_4']/before['rgb_4']);improvements.append(change)
        rows.append(f'<tr><td>{link}</td><td>{q["frames"]}</td><td>{before["rgb_4"]:.3f}</td><td>{after["rgb_4"]:.3f}</td><td>{change:.1f}%</td><td>{before["rgb_1"]:.2f} → {after["rgb_1"]:.2f}</td><td>{before["grain"]:.2f} → {after["grain"]:.2f}</td></tr>')
    body='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>共同模型 · 测量与适用范围</title><style>body{background:#0e141e;color:#e0e8f0;font:17px/1.7 system-ui;max-width:1200px;margin:32px auto;padding:0 20px}a{color:#7eddd9}table{border-collapse:collapse;width:100%;font-size:15px}td,th{text-align:left;padding:12px;border-bottom:1px solid #384352}h1{font-size:27px}h2{font-size:21px}li{margin:8px 0}.note{color:#b9c6d4}.scroll{overflow-x:auto}</style>
<h1>共同模型：直接与华为原片比较</h1><p><a href="shared-field.html">返回视频</a> · <a href="shared-field-manifest.json">完整测量清单</a></p>
<p>十二段华为参考共用同一份释放、输运和表面交接规则，差异输入为尺寸、方向、起点集合和种子。三栏依次为原片、BEFORE、本轮共同模型。</p>
<h2>本轮改动</h2><p>修正引导流减弱、附加卷动却保持原强度的比例失衡，再共同校准运动和材质。十二段使用同一个几何系数，没有改动随机相位、起点或时钟来错开换向。约束使用 120 Hz 的固定材料路径，并同时考虑显眼材料的潜在贡献及相邻原片画面的变化。运行时不读取参考帧、轨迹样本或逐素材控制图。原版钢铁侠仍直接对照华为原片。下方保留各段改善和退步，运动诊断量不等于视觉相似度。</p>
<h2>完整动画测量</h2><p>以下为有效动画区域内全部可观测独立帧的平均值，按画面顺序对齐到统一进度。σ4 为经过 4 像素高斯低通后的 RGB 绝对差，数值越小越好，用于比较形状、位置和明暗。σ1 保留更多细节；颗粒差比较局部高频能量。这些指标都不等于像素一致率，也不能证明颗粒轨迹正确。</p>
<p>SUMMARY</p><div class="scroll"><table><tr><th>参考</th><th>独立帧</th><th>调整前 σ4</th><th>本轮 σ4</th><th>误差降幅</th><th>σ1 前→后</th><th>颗粒差 前→后</th></tr>ROWS</table></div>
<h2>仍有差异</h2><ul><li>内部反复摆动已经减少，仍有局部反向运动；细分颗粒的个别短区间也没有同步改善。向上钢铁侠及新参考中后段的云团覆盖、灰细颗粒、亮弧与局部形状仍有可见差异，尚未达到逐像素一致。</li><li>这十二段素材已参与校准，不是独立留出序列。此前未通过的留出试验仍保留在实验记录中；训练集改善不能证明未知布局的泛化。</li><li>原片的真实触点、随机种子和准确设备时间未知。手持拍摄包含配准及压缩误差，片内视频内容也可能继续变化。新 2／3 的背景和尾段不完整，不计全背景误差。</li><li>这是桌面研究候选。小型共享条件函数仍需提炼或压缩，才能满足最终移动端的实现约束。</li></ul>
<p class="note">导出时间：CREATED。每段提供独立导出的 1 倍、0.5 倍完整画面文件；按页面选项查看本轮提供的局部与差异视频。</p></html>'''
    count=sum(q.get('frames',0) for q in m['metrics'].values())
    summary=f'{len(names)} 段中有 {len(improvements)} 段可计算完整误差，共 {count} 个独立帧；其中 {sum(x>0 for x in improvements)} 段的平均 σ4 下降。各段等权平均误差降幅为 {sum(improvements)/max(1,len(improvements)):.1f}%。'
    motion_path=out/'shared-motion-checks.json'
    material_path=out/'shared-material-checks.json'
    observable_path=out/'shared-observable-grain.json'
    if observable_path.is_file():
        observable=json.loads(observable_path.read_text('utf-8'))
        if observable.get('hash')==m['hash']:
            cells=[]
            for name,row in observable['scenes'].items():
                if name in names:cells.append('<tr><td>'+html.escape(names[name])+f'</td><td>{row["frames"]}</td><td>{row["before"]:.3f} → {row["candidate"]:.3f}</td></tr>')
            content='<h2>未知背景序列的可观测纹理</h2><p>钢铁侠内部起点与蜘蛛侠红色手套序列的片尾仍残留材料，无法获得可信全背景。这里只比较控件及小幅外扩区的局部高频纹理差，剔除了本轮用于校准的观测帧，不把重建背景当作真值。此量也包含源图纹理、壁纸和拍摄噪声，不能解释为纯粒子密度或完整像素相似度。</p><div class="scroll"><table><tr><th>参考</th><th>独立帧</th><th>局部高频差 前→后</th></tr>'+''.join(cells)+'</table></div>'
            body=body.replace('<h2>仍有差异</h2>',content+'<h2>仍有差异</h2>')
    if material_path.is_file():
        material=json.loads(material_path.read_text('utf-8'))
        if material.get('hash')==m['hash']:
            lines=[]
            for name,q in material['scenes'].items():
                if name not in names:continue
                before=q['before']['repeated_change_mean'];after=q['candidate']['repeated_change_mean']
                cells=''.join(f'<td>{100*b:.2f}% → {100*a:.2f}%</td>' for b,a in zip(before,after))
                lines.append('<tr><td>'+html.escape(names[name])+'</td>'+cells+'</tr>')
            content='<h2>同一材料沿途的材质变化</h2><p>独立抽取每段最多 2048 份实际材料，按运行时三维纹理采样覆盖、反射与色度控制量。统计可见区间总变化量超出两倍取值范围的部分，以该控制量允许幅度归一化后取平均；用于发现重复起伏，数值越小表示这种重复更少。它不是最终像素亮度、不是华为相似度，也不能证明所有颗粒完全无波动。</p><div class="scroll"><table><tr><th>参考</th><th>覆盖控制</th><th>反射控制</th><th>色度控制</th></tr>'+''.join(lines)+'</table></div>'
            body=body.replace('<h2>完整动画测量</h2>',content+'<h2>完整动画测量</h2>')
    if motion_path.is_file():
        motion=json.loads(motion_path.read_text('utf-8'))
        if motion.get('hash')==m['hash']:
            mr=[]
            for name,value in motion['scenes'].items():
                if name not in names:continue
                before,after=value['before'],value['candidate']
                cells=[f'{100*before["lateral_excess_quantiles"][i]:.2f}% → {100*after["lateral_excess_quantiles"][i]:.2f}%' for i in [0,1]]
                cells.extend([f'{before["lateral_turns_mean"]:.2f} → {after["lateral_turns_mean"]:.2f}',f'{100*before["maximum_synchronized_turn_fraction"]:.1f}% → {100*after["maximum_synchronized_turn_fraction"]:.1f}%'])
                mr.append('<tr><td>'+html.escape(names[name])+'</td>'+''.join('<td>'+v+'</td>' for v in cells)+'</tr>')
            content='<h2>同一批材料的连续运动</h2><p>进度 0.30～0.70 中持续可见的固定母片，比较横向总路程减去净位移的中位值及 90% 分位（控件短边百分比）、平均换向次数、约 50 毫秒内同步换向的最大占比。分位数为 90% 的材料不超过的数值，越小表示多余横向路程越少。只诊断模型，不是华为粒子身份真值，也不是相似度分数。</p><div class="scroll"><table><tr><th>参考</th><th>往返中位值</th><th>往返 90% 分位</th><th>平均侧向换向</th><th>同步换向占比</th></tr>'+''.join(mr)+'</table></div>'
            if motion.get('weighted'):
                weighted=[]
                for name,q in motion['weighted'].items():
                    if name not in names:continue
                    b,a=q['before'],q['candidate']
                    weighted.append('<tr><td>'+html.escape(names[name])+f'</td><td>{100*b["contrast_peak"]:.1f}% → {100*a["contrast_peak"]:.1f}%</td><td>{100*b["contrast_excess_q90"]:.2f}% → {100*a["contrast_excess_q90"]:.2f}%</td></tr>')
                content+='<h2>显眼材料的共同换向</h2><p>同一中段区间，按真实 GPU 材料投影面积、透明度及背景对比加权；这是未计遮挡的潜在画面贡献，不是原片粒子运动。与计数量同时保留，避免大量弱颗粒掩盖少量显眼材料的运动。</p><div class="scroll"><table><tr><th>参考</th><th>加权同步峰值</th><th>加权往返 90% 分位</th></tr>'+''.join(weighted)+'</table></div>'
            if motion.get('fine'):
                fine=[]
                for name,q in motion['fine'].items():
                    if name not in names:continue
                    cells=[]
                    for b,a in zip(q['before']['short_windows'],q['candidate']['short_windows']):
                        cells.append(f'<td>{100*b["lateral_excess_quantiles"][1]:.2f}% → {100*a["lateral_excess_quantiles"][1]:.2f}%</td>' if min(b['stable_materials'],a['stable_materials'])>=32 else '<td>固定样本不足</td>')
                    fine.append('<tr><td>'+html.escape(names[name])+'</td>'+''.join(cells)+'</tr>')
                content+='<h2>实际细分颗粒复核</h2><p>母片检查不能代替实际子片。以下为三个短区间中持续可见子片的横向额外路程 90% 分位，按控件短边归一化。少于 32 个固定样本的区间不报告分位数，完整原始记录保留逆风采样等其它检查。</p><div class="scroll"><table><tr><th>参考</th><th>0.20～0.45</th><th>0.35～0.60</th><th>0.50～0.75</th></tr>'+''.join(fine)+'</table></div>'
            body=body.replace('<h2>完整动画测量</h2>',content+'<h2>完整动画测量</h2>')
    cloud_path=HERE/'analysis/match-cache/shared-candidate/cloud-evolution.json'
    cloud_data=json.loads(cloud_path.read_text('utf-8')) if cloud_path.is_file() else {}
    if cloud_data.get('hash')==m['hash'] and len(cloud_data.get('scenes',{}))==len(improvements):
        cloud=cloud_data['scenes'];cr=[]
        for n,q in cloud.items():
            before=q['summary']['before'];after=q['summary']['candidate']
            cells=''.join(f'<td>{before[k]:.3f} → {after[k]:.3f}</td>' for k in ['cloud_map','centroid','front','back','width'])
            cr.append(f'<tr><td>{html.escape(names[n])}</td>{cells}</tr>')
        improved=sum(q['summary']['candidate']['cloud_map']<q['summary']['before']['cloud_map'] for q in cloud.values())
        geometry=[(q['summary']['before'][k],q['summary']['candidate'][k]) for q in cloud.values() for k in ['centroid','front','back','width']]
        count=sum(a<b for b,a in geometry)
        content=f'''<h2>云团的连续变化</h2><p>每隔三个独立画面测量一次可见粒子分布。下表均为与华为原片的误差，依次列出调整前与本轮；云团占用为分布图绝对差，其余以控件短边归一化。前、后沿为飞逝方向投影的 95%／5% 分位数，展宽为垂直方向的 90% 范围。{len(cloud)} 段中 {improved} 段的云团分布差下降；{len(geometry)} 项几何误差中 {count} 项下降，其余需继续检查，不能说各项都改善。</p><p>这些量描述可见云团，不是单颗粒轨迹。逐帧 PIV 曾在模型真实位移标定中失败，故不纳入验收或改善结论。</p><div class="scroll"><table><tr><th>参考</th><th>云团分布</th><th>质心</th><th>前沿</th><th>后沿</th><th>展宽</th></tr>'''+''.join(cr)+'</table></div>'
        body=body.replace('<h2>仍有差异</h2>',content+'<h2>仍有差异</h2>')
    (out/'shared-field-report.html').write_text(body.replace('ROWS',''.join(rows)).replace('CREATED',html.escape(m['created'])).replace('BEFORE',html.escape(m.get('before_title','本轮调整前'))).replace('SUMMARY',summary),'utf-8')
    print('报告已生成',len(rows),'段，',len(m['videos']),'个视频；',summary)


if __name__=='__main__':main()
