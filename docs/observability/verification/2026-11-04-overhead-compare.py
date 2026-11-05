# compare.py <dir> <label-on> <label-off>: per-service p95, mean CPU and CPU-seconds deltas from measure-/run- files.
import sys,json
def load(l):
    d={}
    for line in open(f"{S}/measure-{l}.txt"):
        p=line.split()
        if len(p)==3: d.setdefault(p[0],{})[p[1]]=float(p[2])
    c=json.load(open(f'{S}/run-{l}.json')); return d,c
S=sys.argv[1]; on,off=sys.argv[2],sys.argv[3]
a,ca=load(on); b,cb=load(off)
print(f'{"service":15} {"p95 on":>8} {"p95 off":>8} {"d p95":>7} {"cpu on":>8} {"cpu off":>8} {"d cpu":>7} {"cpus on":>8} {"cpus off":>8} {"d cpus":>7}')
for j in ['config-svc','eureka-svc','ledger-svc','conversion-svc','gateway-svc']:
    def r(m): 
        x,y=a[m].get(j),b[m].get(j)
        return x,y,(f'{(x/y-1)*100:+.1f}%' if x==x and y and y==y else 'n/a')
    p=r('p95ms'); c=r('cpu_usage_mean'); s=r('cpu_seconds')
    print(f'{j:15} {p[0]:8.2f} {p[1]:8.2f} {p[2]:>7} {c[0]:8.4f} {c[1]:8.4f} {c[2]:>7} {s[0]:8.2f} {s[1]:8.2f} {s[2]:>7}')
ta=sum(a['cpu_seconds'].values()); tb=sum(b['cpu_seconds'].values())
print(f'fleet cpu_seconds {ta:.1f} vs {tb:.1f} ({(ta/tb-1)*100:+.1f}%); client p95 all {ca["clientP95ms"]["all"]} vs {cb["clientP95ms"]["all"]} ms ({(ca["clientP95ms"]["all"]/cb["clientP95ms"]["all"]-1)*100:+.1f}%)')
