#!/bin/bash
# Acompanha execucoes do GitHub Actions pelo proxy da sessao Claude e imprime
# etapas com falha + anotacoes (unica saida legivel: logs e artefatos ficam em
# armazenamento bloqueado). Uso: tools/ci_status.sh [RUN_ID ...]
# Sem argumentos: as 2 execucoes mais recentes.
API=https://api.github.com/repos/Rolympio/pelotavision
IDS="$*"
[ -z "$IDS" ] && IDS=$(curl -sS "$API/actions/runs?per_page=2" | python3 -c "import sys,json;print(' '.join(str(r['id']) for r in json.load(sys.stdin)['workflow_runs']))")
for R in $IDS; do
  for i in $(seq 1 90); do
    st=$(curl -sS --max-time 20 "$API/actions/runs/$R" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['name'],'|',d['event'],'|',d['status'],d['conclusion'])")
    case "$st" in *completed*) break;; esac; sleep 30
  done
  echo "=== $R $st"
  for JID in $(curl -sS "$API/actions/runs/$R/jobs" | python3 -c "
import sys,json
for j in json.load(sys.stdin)['jobs']:
  for s in j['steps']:
    if s['conclusion'] not in ('success','skipped'): print('   ETAPA',s['number'],s['name'],s['conclusion'],file=sys.stderr)
  print(j['id'])"); do
    curl -sS "$API/check-runs/$JID/annotations?per_page=100" | python3 -c "import sys,json;[print('-',a['annotation_level'],a['title'],'|',a['message'][:900]) for a in json.load(sys.stdin) if 'deprecated' not in a['message'] and 'will migrate' not in a['message']]"
  done
done
