#!/usr/bin/env python3
"""Envia um AAB para uma faixa de teste do Google Play (API Android Publisher v3).

Uso: play_upload.py ARQUIVO.aab --track internal|alpha|beta --status completed|draft --notes "texto"
Credencial: JSON da conta de servico em PLAY_SERVICE_ACCOUNT_JSON (segredo do GitHub).
O app precisa existir no Play Console e a conta de servico precisa de permissao de lancamento nele.
Se o app ainda for rascunho no Console, o Google so aceita release "draft": o script refaz como draft
e avisa; a distribuicao entao e liberada com um clique no Console.
"""
import argparse, json, os, sys

PACKAGE = "br.com.pelotavision"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE


def fail(msg):
    print(f"::error title=Google Play::{msg}")
    sys.exit(1)


def check(resp, what):
    if resp.status_code >= 300:
        fail(f"{what}: HTTP {resp.status_code} {resp.text[:1500]}")
    return resp.json() if resp.content else {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("aab")
    ap.add_argument("--track", default="internal", choices=["internal", "alpha", "beta"])
    ap.add_argument("--status", default="completed", choices=["completed", "draft"])
    ap.add_argument("--notes", default="")
    ap.add_argument("--name", default="")
    a = ap.parse_args()

    info = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not info:
        fail("segredo PLAY_SERVICE_ACCOUNT_JSON ausente")
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
    creds = service_account.Credentials.from_service_account_info(
        json.loads(info), scopes=["https://www.googleapis.com/auth/androidpublisher"])
    s = AuthorizedSession(creds)

    edit = check(s.post(API + "/edits", timeout=60), "abrir edicao")["id"]
    with open(a.aab, "rb") as f:
        payload = f.read()
    up = check(s.post(f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", data=payload,
                      headers={"Content-Type": "application/octet-stream"}, timeout=900), "enviar AAB")
    vc = str(up["versionCode"])
    print(f"AAB aceito: versionCode {vc}, sha256 {up.get('sha256', '?')}")

    def release(status):
        r = {"versionCodes": [vc], "status": status}
        if a.name:
            r["name"] = a.name[:50]
        if a.notes:
            r["releaseNotes"] = [{"language": "pt-BR", "text": a.notes[:500]}]
        return {"track": a.track, "releases": [r]}

    status = a.status
    check(s.put(f"{API}/edits/{edit}/tracks/{a.track}", json=release(status), timeout=60), "definir faixa")
    resp = s.post(f"{API}/edits/{edit}:commit", timeout=120)
    if resp.status_code >= 300 and status != "draft" and "draft" in resp.text.lower():
        print("::warning title=Google Play::App ainda em rascunho no Console; release enviada como DRAFT. "
              "Libere-a no Play Console (Testes > faixa escolhida).")
        status = "draft"
        check(s.put(f"{API}/edits/{edit}/tracks/{a.track}", json=release(status), timeout=60), "definir faixa (draft)")
        resp = s.post(f"{API}/edits/{edit}:commit", timeout=120)
    check(resp, "confirmar edicao")
    print(f"::notice title=Google Play::versionCode {vc} na faixa '{a.track}' com status '{status}'.")


if __name__ == "__main__":
    main()
