import os, re, json, math, base64, zipfile, shutil, subprocess, sys
from pathlib import Path
from collections import defaultdict

import numpy as np
import pandas as pd
import joblib

from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import Ridge
from sklearn.pipeline import Pipeline
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    mean_absolute_error, mean_squared_error, r2_score,
    confusion_matrix, accuracy_score, precision_score, recall_score,
    f1_score, roc_auc_score
)

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "scion_raw"
EXTRACT = ROOT / "scion_extracted"
OUT = ROOT / "training-output"
for d in (DATA, EXTRACT, OUT):
    d.mkdir(parents=True, exist_ok=True)

URLS = {
    "Lab1": "https://raw.githubusercontent.com/keshvadi/ScionPathML/main/AnalysisResults/Lab1/Data/raw_data.zip",
    "Lab2": "https://raw.githubusercontent.com/keshvadi/ScionPathML/main/AnalysisResults/Lab2/Data/raw_data.zip",
    "Lab3": "https://raw.githubusercontent.com/keshvadi/ScionPathML/main/AnalysisResults/Lab3/Data/raw_data.zip",
}
CLEAN_START = pd.Timestamp("2025-07-16", tz="UTC")
MAX_NEXT_GAP = pd.Timedelta("3h")

def download(url, dst):
    if dst.exists() and dst.stat().st_size > 1_000_000:
        return
    print("Downloading", url, flush=True)
    subprocess.run(["curl","-L","--retry","5","--retry-delay","3","-o",str(dst),url], check=True)

def extract_zip(zp, dst):
    marker = dst / ".done"
    if marker.exists():
        return
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    with zipfile.ZipFile(zp) as z:
        z.extractall(dst)
    marker.write_text("ok")

def num(v):
    if v is None:
        return np.nan
    if isinstance(v, (int,float,np.integer,np.floating)):
        try: return float(v)
        except: return np.nan
    m = re.search(r"[-+]?\d+(?:\.\d+)?", str(v).replace(",",""))
    return float(m.group()) if m else np.nan

def bps_mbps(v):
    if v is None: return np.nan
    s = str(v).strip().lower()
    x = num(s)
    if not np.isfinite(x): return np.nan
    if "gbps" in s: return x * 1000.0
    if "mbps" in s: return x
    if "kbps" in s: return x / 1000.0
    if "bps" in s: return x / 1_000_000.0
    # Original analysis divides numeric achieved_bps by 1e6.
    return x / 1_000_000.0 if x > 10000 else x

def ia_mdev(v):
    if v is None: return np.nan
    vals = re.findall(r"[-+]?\d+(?:\.\d+)?", str(v))
    return float(vals[3]) if len(vals) >= 4 else np.nan

def parse_prober(path, lab):
    rows=[]
    try:
        doc=json.loads(path.read_text(encoding="utf-8",errors="ignore"))
    except Exception:
        return rows
    ts=pd.to_datetime(doc.get("timestamp"),utc=True,errors="coerce")
    dest=doc.get("ia")
    if pd.isna(ts) or not dest: return rows
    for p in doc.get("probes",[]) or []:
        fp=p.get("fingerprint")
        pr=p.get("ping_result") or {}
        stats=pr.get("statistics") or {}
        if not fp: continue
        rows.append({
            "lab":lab, "destination":dest, "timestamp":ts,
            "fingerprint":fp, "sequence":p.get("sequence"),
            "rtt_ms":num(stats.get("avg_rtt")),
            "jitter_ms":num(stats.get("mdev_rtt")),
            "loss_pct":num(stats.get("packet_loss")),
        })
    return rows

def parse_bw(path, lab):
    rows=[]
    try:
        doc=json.loads(path.read_text(encoding="utf-8",errors="ignore"))
    except Exception:
        return rows
    ts=pd.to_datetime(doc.get("timestamp"),utc=True,errors="coerce")
    dest=doc.get("as") or (doc.get("target_server") or {}).get("ia")
    tier=num(doc.get("target_mbps") or (doc.get("target") or {}).get("tier_mbps"))
    if pd.isna(ts): return rows
    for p in doc.get("paths",[]) or []:
        fp=p.get("fingerprint")
        if not fp: continue
        result=p.get("result") or {}
        dirs=[]
        for k in ("S->C results","C->S results"):
            d=result.get(k) or {}
            dirs.append({
                "bw":bps_mbps(d.get("achieved_bps")),
                "loss":num(str(d.get("loss_rate","")).replace("%","")),
                "jit":ia_mdev(d.get("interarrival time min/avg/max/mdev"))
            })
        bw=[d["bw"] for d in dirs if np.isfinite(d["bw"])]
        ls=[d["loss"] for d in dirs if np.isfinite(d["loss"])]
        jt=[d["jit"] for d in dirs if np.isfinite(d["jit"])]
        rows.append({
            "lab":lab, "destination":dest, "timestamp":ts,
            "fingerprint":fp, "sequence":p.get("sequence"),
            "tier_mbps":tier,
            "throughput_mbps":float(np.mean(bw)) if bw else np.nan,
            "bw_loss_pct":float(np.mean(ls)) if ls else np.nan,
            "bw_jitter_ms":float(np.mean(jt)) if jt else np.nan,
        })
    return rows

def chronological_split(df):
    df=df.sort_values("timestamp").reset_index(drop=True)
    n=len(df)
    i1=max(1,int(n*.70)); i2=max(i1+1,int(n*.85))
    return df.iloc[:i1].copy(), df.iloc[i1:i2].copy(), df.iloc[i2:].copy()

def regression_metrics(model, df, xcols, ycols):
    if len(df)==0: return {}
    yp=model.predict(df[xcols])
    yt=df[ycols].to_numpy()
    out={}
    for i,y in enumerate(ycols):
        out[y]={
            "MAE":float(mean_absolute_error(yt[:,i],yp[:,i])),
            "RMSE":float(mean_squared_error(yt[:,i],yp[:,i])**0.5),
            "R2":float(r2_score(yt[:,i],yp[:,i]))
        }
    return out

# Download + extract
for lab,url in URLS.items():
    zp=DATA/f"{lab}_raw_data.zip"
    download(url,zp)
    print(lab, "archive MB", round(zp.stat().st_size/1024/1024,2), flush=True)
    extract_zip(zp,EXTRACT/lab)

# Parse
prober_rows=[]; bw_rows=[]
for lab in URLS:
    files=list((EXTRACT/lab).rglob("*.json"))
    print(lab, "json files", len(files), flush=True)
    for p in files:
        low=p.name.lower()
        if low.startswith("prober_"):
            prober_rows += parse_prober(p,lab)
        elif p.name.startswith("BW_"):
            bw_rows += parse_bw(p,lab)

prober=pd.DataFrame(prober_rows)
bw=pd.DataFrame(bw_rows)
print("Parsed prober rows",len(prober),flush=True)
print("Parsed bandwidth rows",len(bw),flush=True)

if prober.empty:
    raise RuntimeError("No prober rows parsed")
if bw.empty:
    raise RuntimeError("No bandwidth rows parsed")

# Clean stable period
prober=prober.dropna(subset=["timestamp","fingerprint","rtt_ms","jitter_ms","loss_pct"]).copy()
bw=bw.dropna(subset=["timestamp","fingerprint","throughput_mbps"]).copy()
prober=prober[prober.timestamp>=CLEAN_START].copy()
bw=bw[bw.timestamp>=CLEAN_START].copy()

# Drop clearly impossible values only; no statistical imputation.
prober=prober[
    prober.rtt_ms.ge(0) &
    prober.jitter_ms.ge(0) &
    prober.loss_pct.between(0,100)
].copy()
bw=bw[bw.throughput_mbps.ge(0)].copy()

# ----------------------------
# Model A: RTT/Jitter/Loss T -> T+1
# ----------------------------
pkeys=["lab","destination","fingerprint"]
prober=prober.sort_values(pkeys+["timestamp"]).copy()
for c in ["rtt_ms","jitter_ms","loss_pct"]:
    prober["next_"+c]=prober.groupby(pkeys)[c].shift(-1)
prober["next_timestamp"]=prober.groupby(pkeys)["timestamp"].shift(-1)
prober["gap"]=prober["next_timestamp"]-prober["timestamp"]
perf=prober[
    prober["gap"].gt(pd.Timedelta(0)) &
    prober["gap"].le(MAX_NEXT_GAP)
].dropna(subset=[
    "rtt_ms","jitter_ms","loss_pct",
    "next_rtt_ms","next_jitter_ms","next_loss_pct"
]).copy()

PX=["rtt_ms","jitter_ms","loss_pct"]
PY=["next_rtt_ms","next_jitter_ms","next_loss_pct"]
ptr,pv,pt=chronological_split(perf)

perf_model=Pipeline([
    ("scale",StandardScaler()),
    ("ridge",Ridge(alpha=1.0))
])
perf_model.fit(ptr[PX],ptr[PY])

# ----------------------------
# Model B: throughput T -> T+1
# Prefer 100 Mbps tier, otherwise highest per path/time.
# ----------------------------
bw=bw.sort_values(["lab","destination","fingerprint","timestamp","tier_mbps"]).copy()
bw=(bw.groupby(["lab","destination","fingerprint","timestamp"],as_index=False)
      .tail(1)
      .sort_values(["lab","destination","fingerprint","timestamp"]))
bkeys=["lab","destination","fingerprint"]
bw["next_throughput_mbps"]=bw.groupby(bkeys)["throughput_mbps"].shift(-1)
bw["next_timestamp"]=bw.groupby(bkeys)["timestamp"].shift(-1)
bw["gap"]=bw["next_timestamp"]-bw["timestamp"]
through=bw[
    bw["gap"].gt(pd.Timedelta(0)) &
    bw["gap"].le(MAX_NEXT_GAP)
].dropna(subset=["throughput_mbps","next_throughput_mbps"]).copy()

TX=["throughput_mbps"]; TY=["next_throughput_mbps"]
ttr,tv,tt=chronological_split(through)
through_model=Pipeline([
    ("scale",StandardScaler()),
    ("ridge",Ridge(alpha=1.0))
])
through_model.fit(ttr[TX],ttr[TY])

# ----------------------------
# Classifier: will this fingerprint still exist at the next measurement cycle?
# This is a project-specific path-availability label built from observed path presence.
# ----------------------------
availability_rows=[]
for (lab,dest),g in prober.groupby(["lab","destination"]):
    times=sorted(g["timestamp"].dropna().unique())
    if len(times)<2: continue
    time_to_next={times[i]:times[i+1] for i in range(len(times)-1)}
    present={t:set(g.loc[g.timestamp==t,"fingerprint"]) for t in times}
    for _,r in g.iterrows():
        t=r["timestamp"]
        nt=time_to_next.get(t)
        if nt is None: continue
        if (pd.Timestamp(nt)-pd.Timestamp(t))>MAX_NEXT_GAP: continue
        availability_rows.append({
            "timestamp":t,
            "rtt_ms":r["rtt_ms"],"jitter_ms":r["jitter_ms"],"loss_pct":r["loss_pct"],
            "next_available":1 if r["fingerprint"] in present.get(nt,set()) else 0
        })
avail=pd.DataFrame(availability_rows).dropna()
atr,av,at=chronological_split(avail)
classifier_metrics={}
train_cm=[]; test_cm=[]
if len(atr)>=50 and atr["next_available"].nunique()>1 and len(at)>0:
    clf=RandomForestClassifier(
        n_estimators=250,max_depth=12,min_samples_leaf=2,
        class_weight="balanced_subsample",random_state=42,n_jobs=-1
    )
    clf.fit(atr[PX],atr["next_available"])
    for name,frame in [("train",atr),("validation",av),("test",at)]:
        if frame.empty: continue
        pred=clf.predict(frame[PX])
        prob=clf.predict_proba(frame[PX])[:,1] if len(clf.classes_)==2 else np.zeros(len(frame))
        m={
            "accuracy":float(accuracy_score(frame.next_available,pred)),
            "precision":float(precision_score(frame.next_available,pred,zero_division=0)),
            "recall":float(recall_score(frame.next_available,pred,zero_division=0)),
            "f1":float(f1_score(frame.next_available,pred,zero_division=0)),
            "confusion_matrix":confusion_matrix(frame.next_available,pred,labels=[0,1]).tolist()
        }
        if frame.next_available.nunique()>1:
            m["roc_auc"]=float(roc_auc_score(frame.next_available,prob))
        classifier_metrics[name]=m
    train_cm=classifier_metrics.get("train",{}).get("confusion_matrix",[])
    test_cm=classifier_metrics.get("test",{}).get("confusion_matrix",[])

# Overall real model bundle
bundle={
    "version":"3.0-real-scionpathml",
    "source_dataset":"ScionPathML official raw measurement archives",
    "clean_start":"2025-07-16",
    "performance_features":PX,
    "performance_targets":PY,
    "performance_model":perf_model,
    "throughput_features":TX,
    "throughput_targets":TY,
    "throughput_model":through_model,
    "decision_weights":{
        "rtt":0.30, "jitter":0.15, "loss":0.30, "throughput":0.25
    }
}
model_path=OUT/"path_performance_model.joblib"
joblib.dump(bundle,model_path,compress=3)
(OUT/"path_performance_model.joblib.b64").write_text(
    base64.b64encode(model_path.read_bytes()).decode("ascii")
)

metrics={
    "dataset":{
        "prober_rows_parsed":int(len(prober_rows)),
        "bandwidth_rows_parsed":int(len(bw_rows)),
        "prober_rows_clean":int(len(prober)),
        "bandwidth_rows_clean":int(len(bw)),
        "performance_pairs":int(len(perf)),
        "throughput_pairs":int(len(through)),
        "availability_examples":int(len(avail)),
        "date_min":str(min(prober.timestamp.min(),bw.timestamp.min())),
        "date_max":str(max(prober.timestamp.max(),bw.timestamp.max()))
    },
    "split":{
        "performance":{"train":len(ptr),"validation":len(pv),"test":len(pt)},
        "throughput":{"train":len(ttr),"validation":len(tv),"test":len(tt)},
        "availability":{"train":len(atr),"validation":len(av),"test":len(at)}
    },
    "performance_regression":{
        "train":regression_metrics(perf_model,ptr,PX,PY),
        "validation":regression_metrics(perf_model,pv,PX,PY),
        "test":regression_metrics(perf_model,pt,PX,PY)
    },
    "throughput_regression":{
        "train":regression_metrics(through_model,ttr,TX,TY),
        "validation":regression_metrics(through_model,tv,TX,TY),
        "test":regression_metrics(through_model,tt,TX,TY)
    },
    "path_availability_classifier":classifier_metrics,
    "model_file_bytes":model_path.stat().st_size
}
(OUT/"training_metrics.json").write_text(json.dumps(metrics,indent=2))
pd.DataFrame({
    "timestamp":pt["timestamp"].astype(str),
    "rtt_actual":pt["next_rtt_ms"],
    "jitter_actual":pt["next_jitter_ms"],
    "loss_actual":pt["next_loss_pct"],
    "rtt_pred":perf_model.predict(pt[PX])[:,0] if len(pt) else [],
    "jitter_pred":perf_model.predict(pt[PX])[:,1] if len(pt) else [],
    "loss_pred":perf_model.predict(pt[PX])[:,2] if len(pt) else [],
}).to_csv(OUT/"performance_test_predictions.csv",index=False)

pd.DataFrame({
    "timestamp":tt["timestamp"].astype(str),
    "throughput_actual":tt["next_throughput_mbps"],
    "throughput_pred":through_model.predict(tt[TX]).reshape(-1) if len(tt) else [],
}).to_csv(OUT/"throughput_test_predictions.csv",index=False)

status={
    "trained":True,
    "model":"path_performance_model.joblib",
    "source":"ScionPathML official raw data",
    "actual_training":True,
    "synthetic_data_used":False,
    "model_size_bytes":model_path.stat().st_size
}
(OUT/"model_status.json").write_text(json.dumps(status,indent=2))

print("=== TRAINING COMPLETE ===", flush=True)
print(json.dumps(metrics,indent=2), flush=True)
print("MODEL_BYTES", model_path.stat().st_size, flush=True)
