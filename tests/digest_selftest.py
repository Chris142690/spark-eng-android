import os, sqlite3, subprocess, sys, tempfile
D = tempfile.mkdtemp(prefix="digest-")
a = os.path.join(D, "a.db"); b = os.path.join(D, "b.db"); c = os.path.join(D, "c.db"); e = os.path.join(D, "e.db")
mk = """CREATE TABLE card(w TEXT PRIMARY KEY, state INT, due INT, s REAL, d REAL, reps INT, lapses INT, last INT)"""
def make(p, rows, order=None):
    con = sqlite3.connect(p)
    con.execute("PRAGMA user_version=14")
    con.execute(mk)
    for r in rows:
        con.execute("INSERT INTO card VALUES(?,?,?,?,?,?,?,?)", r)
    con.commit(); con.close()

R = [("apple", 2, 1000, 2.75, 5.0, 1, 0, 900), ("say", 2, 1000, 13.8206, 5.0, 1, 0, 900)]
make(a, R)
make(b, list(reversed(R)))                                          # 行序不同
make(c, [("apple", 2, 1000, 2.75, 5.0, 1, 0, 901), ("say", 2, 1000, 13.8206, 5.0, 1, 0, 900)])  # last 变
# 极小差异：S 第 12 位小数变动，rounded 模式看不出来，exact 模式必须抓出来
tiny = [("apple", 2, 1000, 2.75, 5.0, 1, 0, 900), ("say", 2, 1000, 13.8206 + 1e-12, 5.0, 1, 0, 900)]
make(e, tiny)

ENV = dict(os.environ, PYTHONIOENCODING="utf-8")

def run(*args):
    r = subprocess.run([sys.executable, r"D:\手机\re\ccg\tests\stable_digest.py"] + list(args),
                       capture_output=True, env=ENV)
    return r.returncode, r.stdout.decode("utf-8", "replace").strip()

c1, o1 = run("--db", a, "--compare", b)
print("行序不同 exact -> 期望 same(0):", c1, "|", o1.splitlines()[-1])
c2, o2 = run("--db", a, "--compare", c)
print("last 改 1ms exact -> 期望 DIFF(2):", c2, "|", o2.splitlines()[-1])
c3, o3 = run("--db", a, "--compare", e)
print("S 变 1e-12 exact -> 期望 DIFF(2):", c3, "|", o3.splitlines()[-1])
c4, o4 = run("--db", a, "--compare", e, "--rounded")
print("S 变 1e-12 rounded -> 期望 same(0) 且声明粗比对:", c4, "|", o4.splitlines()[0] + " / " + o4.splitlines()[-1])
c5, o5 = run("--db", a)
print("单库指纹 ->", c5, "|", [l for l in o5.splitlines() if l.startswith("card")])

ok = (c1 == 0 and c2 == 2 and c3 == 2 and c4 == 0 and c5 == 0)
print("自测结论:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
