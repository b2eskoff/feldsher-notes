"""Reproducible offline search index. Source GRLS snapshot stays unmodified.
Group only exact normalized INNs; combinations remain separate. No clinical
indication or therapeutic dose is inferred from registration data.
"""
from pathlib import Path
import gzip, sqlite3, tempfile, hashlib, re, json
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/reference'
def norm(s):
    return re.sub(r'\s+', ' ', re.sub(r'[^\w]+', ' ', s.lower().replace('ё','е'))).strip()
def compact(s):
    # Keep the entire registered form and strength, only discard secondary packaging.
    primary = s.strip().split(' - ')[0].strip()
    if 'субстанц' in primary.lower() or not primary or primary == '~': return None
    primary = re.sub(r',\s*\d+\s*шт\.?$', '', primary)
    if 'ампул' in s.lower(): primary += ' · ампула'
    elif 'флакон' in s.lower(): primary += ' · флакон'
    return primary
with tempfile.TemporaryDirectory() as temp:
    raw=Path(temp)/'grls.db'; raw.write_bytes(gzip.decompress((ASSETS/'grls_2026_09_18.db.gz').read_bytes()))
    src=sqlite3.connect(raw); src.row_factory=sqlite3.Row
    grouped={}
    for r in src.execute('select * from drugs order by id'):
        forms=[compact(s) for s in r['form'].split(';')];forms=[f for f in forms if f]
        if not forms: continue
        name=r['inn'].strip()
        key=norm(name) if name not in ('','~','-') else 'product '+str(r['id'])
        g=grouped.setdefault(key,dict(id='med-'+hashlib.sha256(key.encode()).hexdigest()[:20],title=name if name not in ('','~','-') else r['trade'],names=set(),forms={},groups=set(),legacy=[]))
        g['names'].add(r['trade']);g['legacy'].append(r['id'])
        if r['group_name'] not in ('','~','-'):g['groups'].add(r['group_name'])
        for f in forms:g['forms'].setdefault(f,set()).add(r['trade'])
    out=ASSETS/'medicines_v1.db';out.unlink(missing_ok=True);db=sqlite3.connect(out)
    db.executescript('''CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE medicines(id TEXT PRIMARY KEY,title TEXT NOT NULL,title_search TEXT NOT NULL,names TEXT NOT NULL,search TEXT NOT NULL,forms TEXT NOT NULL,groups_text TEXT NOT NULL);
CREATE TABLE legacy(id INTEGER PRIMARY KEY,medicine_id TEXT NOT NULL);
CREATE INDEX medicine_title ON medicines(title_search);
''')
    for key,g in grouped.items():
        names=sorted(g['names'],key=str.lower)
        forms=[{'label':f,'brands':sorted(br,key=str.lower)} for f,br in sorted(g['forms'].items(),key=lambda x:(0 if 'ампула' in x[0] else 1 if 'таблетк' in x[0] else 2,len(x[0]),x[0]))]
        db.execute('insert into medicines values(?,?,?,?,?,?,?)',(g['id'],g['title'],norm(g['title']),json.dumps(names,ensure_ascii=False),norm(' | '.join(names)),json.dumps(forms,ensure_ascii=False),' · '.join(sorted(g['groups']))))
        db.executemany('insert into legacy values(?,?)',[(i,g['id']) for i in g['legacy']])
    db.executemany('insert into meta values(?,?)',[('version','1'),('count',str(len(grouped))),('source','ГРЛС · предоставленная выгрузка 18.09.2026')]);db.commit();db.execute('vacuum');db.close()
    print(f'{len(grouped)} medicine groups → {out.name} ({out.stat().st_size} bytes)')
