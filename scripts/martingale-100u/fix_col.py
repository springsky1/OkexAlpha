import io
import os

R = r"D:\workplace\Github\OkexAlpha\reports\martingale-100u"


def drop_last_cell(line):
    if not line.rstrip().endswith("|"):
        return line
    s = line.rstrip()
    body = s[:-1]  # drop trailing pipe
    i = body.rfind("|")
    if i < 0:
        return line
    return body[:i] + "|"


# tbl_select.md: remove the duplicated ROI% column (last) and its header
p1 = os.path.join(R, "tbl_select.md")
lines = io.open(p1, encoding="utf-8").read().split("\n")
out = []
for ln in lines:
    if ln.startswith("|") and ln.rstrip().endswith("|"):
        ln = drop_last_cell(ln)
    out.append(ln)
io.open(p1, "w", encoding="utf-8", newline="\n").write("\n".join(out))

# report.md: same table block
p2 = os.path.join(R, "report.md")
lines = io.open(p2, encoding="utf-8").read().split("\n")
out = []
inside = False
for ln in lines:
    if ln.startswith("| # | 交易对 | 方向 | 杠杆 | 层数 |"):
        inside = True
    elif inside and not ln.startswith("|"):
        inside = False
    if inside and ln.startswith("|"):
        ln = drop_last_cell(ln)
    out.append(ln)
io.open(p2, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("done")
