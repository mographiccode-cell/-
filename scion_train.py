import re, json, base64, zipfile, shutil, subprocess
from pathlib import Path

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
WINDOW = 12

def download(url, dst):
    print("Downloading", url, flush=True)
    subprocess.run(["curl","-L","--retry","5","--retry-delay","2","-o",str(dst),url], check=True)

def extract_zip(zp, dst):
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    with zipfile.ZipFile(zp) as z:
        z.extractall(dst)

def num(v):
    if v is None:
        return np.nan
    if isinstance(v, (int,float,np.integer,np.floating)):
        try: return float(v)
        except: return np.nan
    s = str(v).replace(",","").strip()
    try:
        return float(s)
    except:
        m = re.search(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?", s)
        return float(m.group()) if m else np.nan

def bps_mbps(v):
    # Match the official ScionPathML analysis implementation:
    # first numeric token is achieved bits/sec, converted to Mbps by /1e6.
    if v is None:
        return np.nan
    s = str(v).replace(",","").strip()
    try:
        first = s.split()[0]
        return float(first) / 1_000_000.0
    except:
        x = num(s)
        return x / 1_000_000.0 if np.isfinite(x) else np.nan

def parse_prober(path, lab):
    rows=[]
    try:
        doc=json.loads(path.read_text(encoding="utf-8",errors="ignore"))
    except:
        return rows
    ts=pd.to_datetime(doc.get("timestamp"),utc=True,errors="coerce")
    dest=doc.get("ia")
    if pd.isna(ts) or not dest:
        return rows
    for p in doc.get("probes",[]) or []:
        fp=p.get("fingerprint")
        stats=((p.get("ping_result") or {}).get("statistics") or {})
        if not fp:
            continue
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
    except:
        return rows
    ts=pd.to_datetime(doc.get("timestamp"),utc=True,errors="coerce")
    dest=doc.get("as") or (doc.get("target_server") or {}).get("ia")
    tier=num(doc.get("target_mbps") or (doc.get("target") or {}).get("tier_mbps"))
    if pd.isna(ts):
        return rows
    for p in doc.get("paths",[]) or []:
        fp=p.get("fingerprint")
        if not fp:
            continue
        result=p.get("result") or {}
        bwvals=[]
        lossvals=[]
        for k in ("S->C results","C->S results"):
            d=result.get(k) or {}
            bwv=bps_mbps(d.get("achieved_bps"))
            lv=num(str(d.get("loss_rate","")).replace("%",""))
            if np.isfinite(bwv): bwvals.append(bwv)
            if np.isfinite(lv): lossvals.append(lv)
        rows.append({
            "lab":lab, "destination":dest, "timestamp":ts,
            "fingerprint":fp, "sequence":p.get("sequence"),
            "tier_mbps":tier,
            "throughput_mbps":float(np.mean(bwvals)) if bwvals else np.nan,
            "bw_loss_pct":float(np.mean(lossvals)) if lossvals else np.nan,
        })
    return rows

def split_time(df):
    df=df.sort_values("timestamp").reset_index(drop=True)
    n=len(df)
    i1=int(n*0.70); i2=int(n*0.85)
    return df.iloc[:i1].copy(),df.iloc[i1:i2].copy(),df.iloc[i2:].copy()

def metrics_reg(model, df, xcols, ycols):
    if df.empty: return {}
    yp=np.asarray(model.predict(df[xcols]))
    yt=np.asarray(df[ycols].to_numpy())
    if yp.ndim==1: yp=yp.reshape(-1,1)
    if yt.ndim==1: yt=yt.reshape(-1,1)
    out={}
    for i,y in enumerate(ycols):
        out[y]={
            "MAE":float(mean_absolute_error(yt[:,i],yp[:,i])),
            "RMSE":float(mean_squared_error(yt[:,i],yp[:,i])**0.5),
            "R2":float(r2_score(yt[:,i],yp[:,i]))
        }
    return out

def make_lag_table(df, group_cols, metric_cols, targets, window=12):
    d=df.sort_values(group_cols+["timestamp"]).copy()
    feature_cols=[]
    gb=d.groupby(group_cols, sort=False)
    for c in metric_cols:
        for lag in range(window):
            name=f"{c}_t{lag}"
            d[name]=gb[c].shift(lag)
            feature_cols.append(name)
    target_cols=[]
    for c in targets:
        name=f"next_{c}"
        d[name]=gb[c].shift(-1)
        target_cols.append(name)
    d["next_timestamp"]=gb["timestamp"].shift(-1)
    d["next_gap"]=d["next_timestamp"]-d["timestamp"]
    d=d[
        d["next_gap"].gt(pd.Timedelta(0)) &
        d["next_gap"].le(MAX_NEXT_GAP)
    ].dropna(subset=feature_cols+target_cols)
    return d,feature_cols,target_cols

# Download official archives and extract them.
for lab,url in URLS.items():
    zp=DATA/f"{lab}_raw_data.zip"
    download(url,zp)
    print(lab,"archive MB",round(zp.stat().st_size/1024/1024,2),flush=True)
    extract_zip(zp,EXTRACT/lab)

# Parse raw JSON.
prober_rows=[]; bw_rows=[]
for lab in URLS:
    files=list((EXTRACT/lab).rglob("*.json"))
    print(lab,"JSON files",len(files),flush=True)
    for p in files:
        low=p.name.lower()
        if low.startswith("prober_"):
            prober_rows.extend(parse_prober(p,lab))
        elif p.name.startswith("BW_"):
            bw_rows.extend(parse_bw(p,lab))

prober=pd.DataFrame(prober_rows)
bw=pd.DataFrame(bw_rows)
print("Parsed prober rows",len(prober),flush=True)
print("Parsed bandwidth rows",len(bw),flush=True)

# Clean stable study period only.
prober=prober.dropna(subset=["timestamp","fingerprint","rtt_ms","jitter_ms","loss_pct"]).copy()
prober=prober[prober.timestamp>=CLEAN_START].copy()
prober=prober[
    prober.rtt_ms.ge(0) &
    prober.jitter_ms.ge(0) &
    prober.loss_pct.between(0,100)
].copy()

bw=bw.dropna(subset=["timestamp","fingerprint","throughput_mbps","tier_mbps"]).copy()
bw=bw[bw.timestamp>=CLEAN_START].copy()
# Achieved rate cannot materially exceed the configured test tier.
bw=bw[
    bw.throughput_mbps.ge(0) &
    bw.throughput_mbps.le(bw.tier_mbps*1.05)
].copy()

print("Clean prober rows",len(prober),flush=True)
print("Clean bandwidth rows",len(bw),flush=True)
print("BW throughput summary",bw.throughput_mbps.describe().to_dict(),flush=True)

# Model A: 12-step history -> next RTT/Jitter/Loss.
PKEYS=["lab","destination","fingerprint"]
perf,PX,PY=make_lag_table(
    prober,PKEYS,["rtt_ms","jitter_ms","loss_pct"],
    ["rtt_ms","jitter_ms","loss_pct"],WINDOW
)
ptr,pv,pt=split_time(perf)
perf_model=Pipeline([("scale",StandardScaler()),("ridge",Ridge(alpha=1.0))])
perf_model.fit(ptr[PX],ptr[PY])

# Model B: choose highest test tier available for each path/time,
# then 12-step throughput history -> next throughput.
bw=(bw.sort_values(["lab","destination","fingerprint","timestamp","tier_mbps"])
      .groupby(["lab","destination","fingerprint","timestamp"],as_index=False)
      .tail(1)
      .sort_values(["lab","destination","fingerprint","timestamp"]))
through,TX,TY=make_lag_table(
    bw,["lab","destination","fingerprint"],["throughput_mbps"],
    ["throughput_mbps"],WINDOW
)
ttr,tv,tt=split_time(through)
through_model=Pipeline([("scale",StandardScaler()),("ridge",Ridge(alpha=1.0))])
through_model.fit(ttr[TX],ttr[TY])

# Project-specific path availability classifier.
# Build the next-cycle presence label, then 12-step lag features.
pbase=prober.sort_values(["lab","destination","fingerprint","timestamp"]).copy()
label_map={}
for (lab,dest),g in pbase.groupby(["lab","destination"]):
    times=sorted(g.timestamp.unique())
    present={t:set(g.loc[g.timestamp==t,"fingerprint"]) for t in times}
    for i,t in enumerate(times[:-1]):
        nt=times[i+1]
        if (pd.Timestamp(nt)-pd.Timestamp(t))>MAX_NEXT_GAP:
            continue
        for fp in present[t]:
            label_map[(lab,dest,t,fp)] = 1 if fp in present[nt] else 0

pbase["next_available"]=[
    label_map.get((r.lab,r.destination,r.timestamp,r.fingerprint),np.nan)
    for r in pbase.itertuples()
]
pbase=pbase.dropna(subset=["next_available"]).copy()

CFEATURES=[]
gb=pbase.groupby(PKEYS,sort=False)
for c in ["rtt_ms","jitter_ms","loss_pct"]:
    for lag in range(WINDOW):
        name=f"{c}_t{lag}"
        pbase[name]=gb[c].shift(lag)
        CFEATURES.append(name)
# Trend/delta features.
for c in ["rtt_ms","jitter_ms","loss_pct"]:
    name=f"{c}_delta_1"
    pbase[name]=pbase[f"{c}_t0"]-pbase[f"{c}_t1"]
    CFEATURES.append(name)

clf_df=pbase.dropna(subset=CFEATURES+["next_available"]).copy()
ctr,cv,ct=split_time(clf_df)
classifier_metrics={}
if len(ctr)>100 and ctr.next_available.nunique()>1 and len(ct)>0:
    clf=RandomForestClassifier(
        n_estimators=350,max_depth=16,min_samples_leaf=2,
        class_weight=None,random_state=42,n_jobs=-1
    )
    clf.fit(ctr[CFEATURES],ctr.next_available.astype(int))
    for name,frame in [("train",ctr),("validation",cv),("test",ct)]:
        pred=clf.predict(frame[CFEATURES])
        prob=clf.predict_proba(frame[CFEATURES])[:,1]
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

bundle={
    "version":"4.0-real-scionpathml-window12",
    "source_dataset":"ScionPathML official raw measurement archives",
    "clean_start":"2025-07-16",
    "history_window":WINDOW,
    "performance_features":PX,
    "performance_targets":PY,
    "performance_model":perf_model,
    "throughput_features":TX,
    "throughput_targets":TY,
    "throughput_model":through_model,
    "decision_weights":{"rtt":0.30,"jitter":0.15,"loss":0.30,"throughput":0.25}
}
model_path=OUT/"path_performance_model.joblib"
joblib.dump(bundle,model_path,compress=3)
(OUT/"path_performance_model.joblib.b64").write_text(
    base64.b64encode(model_path.read_bytes()).decode("ascii")
)

metrics={
    "dataset":{
        "raw_prober_rows":int(len(prober_rows)),
        "raw_bandwidth_rows":int(len(bw_rows)),
        "clean_prober_rows":int(len(prober)),
        "clean_bandwidth_rows":int(len(bw)),
        "performance_window_examples":int(len(perf)),
        "throughput_window_examples":int(len(through)),
        "availability_window_examples":int(len(clf_df)),
        "date_min":str(min(prober.timestamp.min(),bw.timestamp.min())),
        "date_max":str(max(prober.timestamp.max(),bw.timestamp.max())),
        "throughput_mbps_min":float(bw.throughput_mbps.min()),
        "throughput_mbps_median":float(bw.throughput_mbps.median()),
        "throughput_mbps_max":float(bw.throughput_mbps.max())
    },
    "split":{
        "performance":{"train":len(ptr),"validation":len(pv),"test":len(pt)},
        "throughput":{"train":len(ttr),"validation":len(tv),"test":len(tt)},
        "availability":{"train":len(ctr),"validation":len(cv),"test":len(ct)}
    },
    "performance_regression":{
        "train":metrics_reg(perf_model,ptr,PX,PY),
        "validation":metrics_reg(perf_model,pv,PX,PY),
        "test":metrics_reg(perf_model,pt,PX,PY)
    },
    "throughput_regression":{
        "train":metrics_reg(through_model,ttr,TX,TY),
        "validation":metrics_reg(through_model,tv,TX,TY),
        "test":metrics_reg(through_model,tt,TX,TY)
    },
    "path_availability_classifier":classifier_metrics,
    "model_file_bytes":model_path.stat().st_size
}
(OUT/"training_metrics.json").write_text(json.dumps(metrics,indent=2))
(OUT/"model_status.json").write_text(json.dumps({
    "trained":True,
    "model":"path_performance_model.joblib",
    "source":"ScionPathML official raw data",
    "actual_training":True,
    "synthetic_data_used":False,
    "history_window":WINDOW,
    "model_size_bytes":model_path.stat().st_size
},indent=2))

pp=perf_model.predict(pt[PX]) if len(pt) else np.empty((0,3))
pd.DataFrame({
    "timestamp":pt.timestamp.astype(str),
    "rtt_actual":pt.next_rtt_ms,
    "rtt_pred":pp[:,0] if len(pt) else [],
    "jitter_actual":pt.next_jitter_ms,
    "jitter_pred":pp[:,1] if len(pt) else [],
    "loss_actual":pt.next_loss_pct,
    "loss_pred":pp[:,2] if len(pt) else [],
}).to_csv(OUT/"performance_test_predictions.csv",index=False)

tp=np.asarray(through_model.predict(tt[TX])).reshape(-1) if len(tt) else np.array([])
pd.DataFrame({
    "timestamp":tt.timestamp.astype(str),
    "throughput_actual":tt.next_throughput_mbps,
    "throughput_pred":tp
}).to_csv(OUT/"throughput_test_predictions.csv",index=False)

print("=== TRAINING COMPLETE ===",flush=True)
print(json.dumps(metrics,indent=2),flush=True)
print("MODEL_BYTES",model_path.stat().st_size,flush=True)
