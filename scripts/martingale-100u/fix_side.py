import io
import os

R = r"D:\workplace\Github\OkexAlpha\reports\martingale-100u"
p = os.path.join(R, "report.md")
t = io.open(p, encoding="utf-8").read()
before = t.count("| short |") + t.count("| long |")
t = t.replace("| short |", "| 空 |").replace("| long |", "| 多 |")
io.open(p, "w", encoding="utf-8", newline="\n").write(t)
print("replaced rows:", before, "chars:", len(t))
