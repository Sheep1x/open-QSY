# -*- coding: utf-8 -*-
import io, re, json, datetime, difflib
from collections import defaultdict, Counter

ANS_RE = re.compile(r"^(?:【?答案】?|参考答案|正确答案)\s*[:：]?\s*(.+)$")
NOTE_PAT = re.compile(r"(题型说明|试卷|满分|每套|适配|题库|考试说明|已在上轮|不再重复|补全整套|回复【|规则提示)")
SECTION_RE = re.compile(r"^([一二三四五六七八九十]+、|【第\d+套|第\d+套)")
JUDGE_RE = re.compile(r"[（(]\s*(√|×|✓|✗|正确|错误|对|错)\s*(?:[，,；;]\s*([^)）]*?))?\s*[)）]\s*[。\.]?\s*$")
FILLER=set("，,。、的为性是之其了与和当中当进行可按应以及")

def split_options(line):
    ms = list(re.finditer(r"(?:^|\s)([A-H])\s*[\.\、．]\s*", line))
    out=[]
    for i,m in enumerate(ms):
        end = ms[i+1].start() if i+1 < len(ms) else len(line)
        out.append((m.group(1), line[m.end():end].strip()))
    return out

def rows_of(path):
    for l in io.open(path, encoding="utf-8"):
        l=l.rstrip("\n")
        if not l: continue
        _,style,text=l.split("|",2)
        yield style,text

def parse(path, subject):
    qs=[]; cur=None; drops=Counter()
    def flush():
        nonlocal cur
        if cur: qs.append(cur); cur=None
    for style,text in rows_of(path):
        t=text.strip()
        if not t: continue
        if style in ("Title","Heading 1","Heading 2"): flush(); continue
        m=ANS_RE.match(t)
        if m:
            if cur is not None and cur["ans"] is None: cur["ans"]=m.group(1).strip()
            else: drops["loose_answer"]+=1
            continue
        parts=split_options(t)
        if parts and (parts[0][0]=="A" or re.match(r"^[A-H]\s*[\.\、．]",t)):
            if cur is not None: cur["opts"].extend(parts)
            else: drops["loose_options"]+=1
            continue
        flush(); cur={"stem":t,"opts":[],"ans":None,"expl":None}
    flush()
    good=[]
    for q in qs:
        stem=q["stem"].strip(); ans=q["ans"]; expl=None; is_judge=False
        jm=JUDGE_RE.search(stem)
        if jm:
            is_judge=True
            stem=stem[:jm.start()].strip()
            ans="正确" if jm.group(1) in ("√","✓","正确","对") else "错误"
            if jm.group(2): expl=jm.group(2).strip()
        if not ans: drops["no_answer"]+=1; continue
        if NOTE_PAT.search(stem) and len(stem)>18: drops["note"]+=1; continue
        if SECTION_RE.match(stem): drops["section"]+=1; continue
        seen={}
        for k,v in q["opts"]:
            v=re.sub(r"\s+"," ",v).strip()
            if k not in seen and v: seen[k]=v
        opts=[(k,seen[k]) for k in sorted(seen)]
        if is_judge: qtype="判断题"; opts=[]
        else:
            if len(opts)<2: drops["no_options"]+=1; continue
            letters=[k for k,_ in opts]
            a="".join(sorted(set(re.sub(r"[^A-H]","",ans.upper()))))
            if not a or any(ch not in letters for ch in a): drops["bad_answer"]+=1; continue
            ans=a; qtype="多选题" if len(a)>1 else "单选题"
        stem=re.sub(r"\s+"," ",stem).strip()
        stem=re.sub(r"^[（(]\s*[)）]\s*","",stem)
        if len(stem)<6: drops["too_short"]+=1; continue
        good.append({"stem":stem,"opts":opts,"ans":ans,"type":qtype,"subject":subject,"expl":expl})
    return good, drops

def normkey(stem):
    s=re.sub(r"[（(]\s*[)）]","",stem)
    return re.sub(r"[^\w\u4e00-\u9fff]","",s)
def bigrams(s): return set(s[i:i+2] for i in range(len(s)-1)) or {s}
def digits(s): return tuple(re.findall(r"\d+", s))

allq=[]; alldrops=Counter()
for path,sub in [(r"D:\openwork\open-qsy\道路题库.txt","道路工程"),(r"D:\openwork\open-qsy\公基题库.txt","公共基础")]:
    g,d=parse(path,sub); allq.extend(g); alldrops.update(d)

by=defaultdict(list)
for q in allq: by[(q["subject"],normkey(q["stem"]))].append(q)
final=[]; conflicts=[]
for key,group in by.items():
    group.sort(key=lambda q:(-len(q["opts"]), -len(q["stem"])))
    base=dict(group[0]); base["opts"]=list(base["opts"])
    if base["type"]!="判断题":
        same=[q for q in group if [t for _,t in q["opts"]]==[t for _,t in base["opts"]]] or group
        votes=Counter(q["ans"] for q in same)
    else:
        votes=Counter(q["ans"] for q in group)
    if len(votes)>1: conflicts.append((base["stem"],[(a,c) for a,c in votes.most_common()]))
    base["ans"]=votes.most_common(1)[0][0]
    base["type"]="多选题" if (base["type"]!="判断题" and len(base["ans"])>1) else ("判断题" if base["type"]=="判断题" else "单选题")
    final.append(base)
print("exact dedupe:",len(final))

def optset(q): return tuple(sorted(t for _,t in q["opts"]))
def safe_merge(a,b):
    if a["type"]!=b["type"] or a["ans"]!=b["ans"] or optset(a)!=optset(b): return False
    ka,kb=normkey(a["stem"]),normkey(b["stem"])
    if digits(ka)!=digits(kb): return False
    sm=difflib.SequenceMatcher(None,ka,kb)
    if sm.ratio()<0.90: return False
    extra=set()
    for tag,i1,i2,j1,j2 in sm.get_opcodes():
        if tag=="replace": return False
        if tag=="insert": extra|=set(kb[j1:j2])
        elif tag=="delete": extra|=set(ka[i1:i2])
    return len(extra)<=4 and extra<=FILLER

by_sub=defaultdict(list)
for q in final: by_sub[q["subject"]].append(q)
kept=[]; merges=[]
for sub,items in by_sub.items():
    keys=[normkey(q["stem"]) for q in items]
    idx=defaultdict(set)
    for i,k in enumerate(keys):
        for g in bigrams(k): idx[g].add(i)
    dead=set()
    for i,q in enumerate(items):
        if i in dead: continue
        for j in sorted(set().union(*[idx[g] for g in bigrams(keys[i])])):
            if j<=i or j in dead: continue
            if safe_merge(q,items[j]):
                dead.add(j); merges.append((q["stem"],items[j]["stem"]))
    kept.extend([q for i,q in enumerate(items) if i not in dead])
print("merged:",len(merges),"final:",len(kept))

typeorder={"单选题":0,"多选题":1,"判断题":2}; subjectorder={"道路工程":0,"公共基础":1}
kept.sort(key=lambda q:(subjectorder[q["subject"]],typeorder[q["type"]]))
out={"meta":{"title":"2026年公路水运试验检测题库（道路工程＋公共基础）",
    "source":["道路题库.docx","公基题库.docx"],
    "generatedAt":datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "count":len(kept),
    "fields":"id 题号；subject 科目；type 题型；stem 题干；options 选项；answer 答案；answerText 答案内容",
    "note":"已剔除试卷说明、套卷标题、残缺题（无答案/无选项）及重复题；仅保留题干、选项与答案。"},
    "questions":[]}
for i,q in enumerate(kept,1):
    it={"id":i,"subject":q["subject"],"type":q["type"],"stem":q["stem"]}
    if q["type"]=="判断题": it["answer"]=q["ans"]
    else:
        it["options"]={k:v for k,v in q["opts"]}
        it["answer"]=q["ans"]
        it["answerText"]="；".join("%s.%s"%(k,v) for k,v in q["opts"] if k in q["ans"])
    if q.get("expl"): it["explanation"]=q["expl"]
    out["questions"].append(it)

for p in [r"C:\Users\13334\Downloads\题库合并.json", r"D:\openwork\open-qsy\题库合并.json"]:
    json.dump(out, io.open(p,"w",encoding="utf-8"), ensure_ascii=False, indent=1)

rep=io.open(r"D:\openwork\open-qsy\report3.txt","w",encoding="utf-8")
rep.write("drops=%s\nfinal=%d types=%s subjects=%s\n"%(dict(alldrops),len(kept),
    dict(Counter(q["type"] for q in kept)),dict(Counter(q["subject"] for q in kept))))
rep.write("conflicts=%d\n"%len(conflicts))
for s,c in conflicts: rep.write("  C| %s | %s\n"%(s[:70],c))
rep.write("\nmerges=%d\n"%len(merges))
for a,b in merges: rep.write("  M| %s   <=   %s\n"%(a[:60],b[:60]))
rep.close()
print("ok")