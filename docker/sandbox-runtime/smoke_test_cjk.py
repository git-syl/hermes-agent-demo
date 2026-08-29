import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
import warnings

warnings.simplefilter('error', UserWarning)

_AVAILABLE = {f.name for f in fm.fontManager.ttflist}
picked = None
for _c in ['Noto Sans CJK JP', 'Noto Sans CJK SC', 'WenQuanYi Zen Hei',
           'Source Han Sans CN', 'PingFang SC', 'Microsoft YaHei', 'SimHei']:
    if _c in _AVAILABLE:
        plt.rcParams['font.sans-serif'] = [_c, 'DejaVu Sans']
        picked = _c
        break
plt.rcParams['axes.unicode_minus'] = False
print('picked font ->', picked)

fig, ax = plt.subplots()
ax.bar(['甲', '乙', '丙'], [3, 7, 5])
ax.set_title('演示柱状图 -1')
ax.set_xlabel('类别')
ax.set_ylabel('数量')
fig.savefig('/tmp/x.png')
print('OK: no missing-glyph warnings')
