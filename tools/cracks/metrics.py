"""Métricas binárias (positivo = cracked) com ênfase em falsos negativos."""
import numpy as np


def binary_report(y, score, threshold=0.5, low=0.4, high=0.6):
    y = np.asarray(y, int)
    s = np.asarray(score, float)
    pred = s >= threshold
    tp = int(np.sum(pred & (y == 1)))
    fp = int(np.sum(pred & (y == 0)))
    fn = int(np.sum(~pred & (y == 1)))
    tn = int(np.sum(~pred & (y == 0)))
    prec = tp / (tp + fp) if tp + fp else None
    rec = tp / (tp + fn) if tp + fn else None
    spec = tn / (tn + fp) if tn + fp else None
    f1 = 2 * prec * rec / (prec + rec) if prec and rec else (0.0 if prec is not None and rec is not None else None)
    out = {
        'n': int(len(y)), 'positives': int(y.sum()), 'negatives': int((1 - y).sum()), 'threshold': threshold,
        'confusion_matrix': {'labels': ['intact', 'cracked'], 'rows_truth_cols_pred': [[tn, fp], [fn, tp]]},
        'precision_cracked': prec, 'recall_cracked': rec, 'specificity': spec, 'f1_cracked': f1,
        'false_negatives': fn, 'false_positives': fp,
        'balanced_accuracy': (rec + spec) / 2 if rec is not None and spec is not None else None,
        'roc_auc': _auc(y, s),
    }
    # política do app com sensibilidade 50: >=0,6 POSSIBLE, <=0,4 NO_INDICATION, meio = INCONCLUSIVE
    poss, noind = s >= high, s <= low
    inc = ~poss & ~noind
    out['app_policy_0.4_0.6'] = {
        'possible_cracked': int(np.sum(poss & (y == 1))), 'possible_intact': int(np.sum(poss & (y == 0))),
        'inconclusive_cracked': int(np.sum(inc & (y == 1))), 'inconclusive_intact': int(np.sum(inc & (y == 0))),
        'no_indication_cracked': int(np.sum(noind & (y == 1))), 'no_indication_intact': int(np.sum(noind & (y == 0))),
    }
    return out


def _auc(y, s):
    pos, neg = s[y == 1], s[y == 0]
    if len(pos) == 0 or len(neg) == 0:
        return None
    # Mann-Whitney com empates = 0,5
    order = np.argsort(np.concatenate([pos, neg]), kind='mergesort')
    allv = np.concatenate([pos, neg])[order]
    ranks = np.empty(len(allv))
    i = 0
    while i < len(allv):
        j = i
        while j + 1 < len(allv) and allv[j + 1] == allv[i]:
            j += 1
        ranks[i:j + 1] = (i + j) / 2 + 1
        i = j + 1
    r = np.empty(len(allv))
    r[order] = ranks
    return float((r[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))
