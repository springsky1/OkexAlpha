import io
import os

R = r"D:\workplace\Github\OkexAlpha\reports\martingale-100u"
p = os.path.join(R, "report.md")
t = io.open(p, encoding="utf-8").read()
for k, f in [("TBL_SPECS", "tbl_specs.md"), ("TBL_SELECT", "tbl_select.md"), ("TBL_ORDERS", "tbl_orders.md"),
             ("TBL_ALLOC", "tbl_alloc.md"), ("TBL_MONTHLY", "tbl_monthly.md")]:
    body = io.open(os.path.join(R, f), encoding="utf-8").read().strip()
    t = t.replace("{{" + k + "}}", body)
t = t.replace("未模拟停机、下单被拒、强平引擎插针、API 限频。these 在极端行情会放大亏损。",
              "未模拟停机、下单被拒、强平引擎插针、API 限频；这些在极端行情会显著放大亏损。")
io.open(p, "w", encoding="utf-8", newline="\n").write(t)
print("left placeholders:", t.count("{{"))
print("lines:", t.count("\n"), "chars:", len(t))
