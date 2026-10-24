from pathlib import Path
import cv2,numpy as np,json,struct,hashlib
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import LeaveOneGroupOut
from sklearn.metrics import classification_report,confusion_matrix,roc_auc_score
from features import extract,lighting
root=Path(__file__).resolve().parent;out=root/'training';out.mkdir(exist_ok=True);ann=json.loads((root/'data/annotations.json').read_text())
X=[];labels=[];groups=[];ids=[];aug=[]
for scene in ann:
 gray=cv2.imread(str(root/'data'/(scene['group']+'.png')),0)
 variants=[lighting(gray,j) for j in range(6)]
 for a in scene['annotations']:
  if a['partial']:continue
  X.append(extract(gray,a['x'],a['y'],a['radius']));labels.append(a['label']);groups.append(scene['group']);ids.append(a['id']);aug.append([extract(v,a['x'],a['y'],a['radius']) for v in variants])
X=np.array(X);y=np.array(labels);groups=np.array(groups);aug=np.array(aug);scores=np.zeros(len(y))
def classifier():return RandomForestClassifier(n_estimators=160,max_depth=7,min_samples_leaf=2,max_features='sqrt',class_weight='balanced',random_state=20261018,n_jobs=2)
folds=[]
for tr,te in LeaveOneGroupOut().split(X,y,groups):
 model=classifier();model.fit(aug[tr].reshape(-1,X.shape[1]),np.repeat(y[tr],6));scores[te]=model.predict_proba(X[te])[:,1]
 item={'held_out_scene':groups[te][0],'n':len(te),'positive':int(y[te].sum()),'confusion':confusion_matrix(y[te],scores[te]>=.5,labels=[0,1]).tolist()};folds.append(item);print(item,flush=True)
pred=scores>=.5;report={'model':'random_forest_20261018_v1','features':int(X.shape[1]),'n_base_pellets':len(y),'positive':int(y.sum()),'negative':int((1-y).sum()),'scenes':len(ann),'augmentation_variants_per_training_pellet':6,'evaluation':'leave-one-scene-out, original held-out images only, fixed threshold 0.5; annotations provisional, synthetic or provenance unverified; no physical phone or real production validation','folds':folds,'confusion':confusion_matrix(y,pred).tolist(),'metrics':classification_report(y,pred,output_dict=True,zero_division=0),'roc_auc':roc_auc_score(y,scores),'uncertain_count':int(((scores>.4)&(scores<.6)).sum())}
model=classifier();model.fit(aug.reshape(-1,X.shape[1]),np.repeat(y,6))
p=out/'crack_forest.pvrf'
with p.open('wb') as f:
 f.write(struct.pack('>4siii',b'PVF1',1,X.shape[1],len(model.estimators_)))
 for est in model.estimators_:
  tree=est.tree_;f.write(struct.pack('>i',tree.node_count))
  for i in range(tree.node_count):
   val=tree.value[i,0];score=float(val[1]/sum(val));f.write(struct.pack('>iiidd',int(tree.feature[i]),int(tree.children_left[i]),int(tree.children_right[i]),float(tree.threshold[i]),score))
report['sha256']=hashlib.sha256(p.read_bytes()).hexdigest();report['final_fit']='All seven scenes after cross-validation. Scores reported above are out-of-fold, not final in-sample scores.'
(out/'validation.json').write_text(json.dumps(report,indent=2));(out/'metadata.json').write_text(json.dumps({k:report[k] for k in ['model','features','n_base_pellets','positive','negative','scenes','sha256','evaluation']},indent=2))
np.savez(out/'reference_vectors.npz',features=X,score_final=model.predict_proba(X)[:,1],score_oof=scores,labels=y,groups=groups,ids=np.array(ids))
with (out/'reference_vectors.csv').open('w') as f:
 for x,pred in zip(X,model.predict_proba(X)[:,1]):f.write(','.join(map(str,x.tolist()+[float(pred)]))+'\n')
with (out/'oof_predictions.csv').open('w') as f:
 f.write('scene;pellet_id;label;out_of_fold_score;predicted\n')
 for g,i,l,s in zip(groups,ids,y,scores):f.write(f'{g};{i};{l};{s:.8f};{int(s>=.5)}\n')
print(json.dumps(report['metrics'],indent=2),flush=True)
