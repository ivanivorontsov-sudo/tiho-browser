#!/usr/bin/env python3
"""
Тихо — offline publisher for signed content packs and in-app update descriptors.

The content-signing private keys never leave this machine (not in git, not in CI). Signatures are minisign-compatible
(Ed25519, pre-hashed BLAKE2b-512 "ED" mode + signed trusted comment), so they can also be checked with
`minisign -Vm manifest.json -P <pubkey>`.

  tiho-publish.py keygen  --dir KEYDIR                         # primary + backup keys (run once)
  tiho-publish.py pack    --dir KEYDIR --src SRC --repo REPO --version N [--days 45] [--min-app 3] [--min-webview 148]
  tiho-publish.py update  --dir KEYDIR --repo REPO --apk APK --version-code 3 --version-name 1.1.0 --url URL [--days 120]
  tiho-publish.py verify  --pub PUBFILE --file F [--sig F.minisig]

SRC layout: filters/*.txt, security/*.txt, js/*.js, payment-domains.json, config.json, userscripts/*  (kind is derived
from the path: js/ → code, filters/ & security/ → filter, everything else → data).
"""
import argparse, base64, getpass, hashlib, json, os, secrets, shutil, sys, time
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

def b64(b): return base64.b64encode(b).decode()

def passphrase(keydir):
    env = os.environ.get("TIHO_SIGN_PASSPHRASE")
    if env: return env.encode()
    p = os.path.join(keydir, "passphrase.txt")
    if os.path.exists(p): return open(p).read().strip().encode()
    return getpass.getpass("content-signing key passphrase: ").encode()

def keygen(a):
    os.makedirs(a.dir, exist_ok=True); os.chmod(a.dir, 0o700)
    pp_path = os.path.join(a.dir, "passphrase.txt")
    if not os.path.exists(pp_path):
        with open(pp_path, "w") as f: f.write(secrets.token_urlsafe(32) + "\n")
        os.chmod(pp_path, 0o600)
    pp = passphrase(a.dir)
    for name in a.names.split(","):
        sk_path = os.path.join(a.dir, f"tiho-content-{name}.key")
        if os.path.exists(sk_path): sys.exit(f"{sk_path} exists — refusing to overwrite a signing key")
        sk = Ed25519PrivateKey.generate(); key_id = secrets.token_bytes(8)
        pem = sk.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.BestAvailableEncryption(pp))
        with open(sk_path, "wb") as f: f.write(pem)
        os.chmod(sk_path, 0o600)
        with open(sk_path + ".id", "w") as f: f.write(key_id.hex() + "\n")
        pk = sk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        kid = key_id[::-1].hex().upper()
        with open(os.path.join(a.dir, f"tiho-content-{name}.pub"), "w") as f:
            f.write(f"untrusted comment: minisign public key {kid} (Тихо content {name})\n{b64(b'Ed' + key_id + pk)}\n")
        print(name, kid, "sha256:", ":".join(f"{x:02X}" for x in hashlib.sha256(pk).digest()))

def load_key(keydir, name="primary"):
    sk_path = os.path.join(keydir, f"tiho-content-{name}.key")
    sk = serialization.load_pem_private_key(open(sk_path, "rb").read(), password=passphrase(keydir))
    key_id = bytes.fromhex(open(sk_path + ".id").read().strip())
    return sk, key_id

def sign(sk, key_id, data, trusted_comment, label):
    sig = sk.sign(hashlib.blake2b(data, digest_size=64).digest())
    glob = sk.sign(sig + trusted_comment.encode())
    kid = key_id[::-1].hex().upper()
    return (f"untrusted comment: signature from Тихо {label} key {kid}\n{b64(b'ED' + key_id + sig)}\n"
            f"trusted comment: {trusted_comment}\n{b64(glob)}\n")

def sha256(data): return hashlib.sha256(data).hexdigest()

def kind_of(path):
    if path.startswith("js/"): return "code"
    if path.startswith("filters/") or path.startswith("security/"): return "filter"
    return "data"

def pack(a):
    sk, key_id = load_key(a.dir, a.key)
    content = os.path.join(a.repo, "content")
    old = os.path.join(content, "manifest.json")
    if os.path.exists(old):
        prev = json.load(open(old))["version"]
        if a.version <= prev: sys.exit(f"version must grow: published {prev}, requested {a.version}")
    dest = os.path.join(content, "packs", str(a.version))
    if os.path.exists(dest): shutil.rmtree(dest)
    files = []
    for root, _, names in os.walk(a.src):
        for n in sorted(names):
            full = os.path.join(root, n); rel = os.path.relpath(full, a.src).replace(os.sep, "/")
            data = open(full, "rb").read()
            out = os.path.join(dest, rel); os.makedirs(os.path.dirname(out), exist_ok=True)
            shutil.copyfile(full, out)
            files.append({"path": rel, "url": f"packs/{a.version}/{rel}", "sha256": sha256(data), "size": len(data), "kind": kind_of(rel)})
    files.sort(key=lambda f: f["path"])
    now = int(time.time())
    m = {"format": 1, "name": "tiho-content", "version": a.version,
         "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)), "expires": now + a.days * 86400,
         "min_app_version": a.min_app, "min_webview_major": a.min_webview, "files": files}
    data = (json.dumps(m, ensure_ascii=False, indent=1) + "\n").encode()
    open(old, "wb").write(data)
    tc = f"tiho-content v{a.version} timestamp:{now} file:manifest.json"
    open(old + ".minisig", "w").write(sign(sk, key_id, data, tc, "content"))
    # keep only the 2 newest packs in the tree (older ones stay in git history)
    packs = sorted(int(x) for x in os.listdir(os.path.join(content, "packs")) if x.isdigit())
    for v in packs[:-2]: shutil.rmtree(os.path.join(content, "packs", str(v)))
    print(f"pack v{a.version}: {len(files)} files, {sum(f['size'] for f in files)} bytes, expires {time.strftime('%Y-%m-%d', time.gmtime(m['expires']))}")

def update(a):
    sk, key_id = load_key(a.dir, a.key)
    apk = open(a.apk, "rb").read()
    now = int(time.time())
    u = {"format": 2 if a.abi else 1, "package": "org.ivanivorontsov.tiho", "version_code": a.version_code, "version_name": a.version_name,
         "min_version_code": a.min_version_code, "apk_url": a.url, "sha256": sha256(apk), "size": len(apk),
         "cert_sha256": a.cert, "released": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now)),
         "expires": now + a.days * 86400, "notes": a.notes}
    if a.gecko: u["gecko_version"] = a.gecko
    if a.abi:
        u["apks"] = {}
        for spec in a.abi:  # abi=path=url
            abi, path, url = spec.split("=", 2)
            d = open(path, "rb").read()
            u["apks"][abi] = {"url": url, "sha256": sha256(d), "size": len(d)}
    os.makedirs(os.path.join(a.repo, "app"), exist_ok=True)
    p = os.path.join(a.repo, "app", a.out)
    data = (json.dumps(u, ensure_ascii=False, indent=1) + "\n").encode()
    open(p, "wb").write(data)
    tc = f"tiho-app {a.version_code} {a.version_name} timestamp:{now} file:{a.out}"
    open(p + ".minisig", "w").write(sign(sk, key_id, data, tc, "update"))
    print(f"update.json: {a.version_name} ({a.version_code}) sha256 {u['sha256']} size {len(apk)}")

def resign(a):
    """Refreshes released/expires of an existing descriptor and re-signs it (no new APK)."""
    sk, key_id = load_key(a.dir, a.key)
    p = a.file
    u = json.load(open(p))
    now = int(time.time())
    u["expires"] = now + a.days * 86400
    data = (json.dumps(u, ensure_ascii=False, indent=1) + "\n").encode()
    open(p, "wb").write(data)
    tc = f"tiho-app {u['version_code']} {u['version_name']} timestamp:{now} file:{os.path.basename(p)}"
    open(p + ".minisig", "w").write(sign(sk, key_id, data, tc, "update"))
    print(f"{os.path.basename(p)}: {u['version_name']} re-signed, expires {time.strftime('%Y-%m-%d', time.gmtime(u['expires']))}")

def verify(a):
    lines = [l for l in open(a.pub).read().splitlines() if l and not l.startswith("untrusted")]
    raw = base64.b64decode(lines[0]); key_id, pk = raw[2:10], Ed25519PublicKey.from_public_bytes(raw[10:42])
    s = open(a.sig or a.file + ".minisig").read().splitlines()
    sraw = base64.b64decode(s[1]); alg, skid, sig = sraw[:2], sraw[2:10], sraw[10:]
    assert skid == key_id, "key id mismatch"
    data = open(a.file, "rb").read()
    pk.verify(sig, hashlib.blake2b(data, digest_size=64).digest() if alg == b"ED" else data)
    tc = s[2][len("trusted comment: "):]
    pk.verify(base64.b64decode(s[3]), sig + tc.encode())
    print("OK:", tc)

ap = argparse.ArgumentParser(); sp = ap.add_subparsers(dest="cmd", required=True)
k = sp.add_parser("keygen"); k.add_argument("--dir", required=True); k.add_argument("--names", default="primary,backup"); k.set_defaults(f=keygen)
p = sp.add_parser("pack"); p.add_argument("--dir", required=True); p.add_argument("--src", required=True); p.add_argument("--repo", required=True)
p.add_argument("--version", type=int, required=True); p.add_argument("--days", type=int, default=45); p.add_argument("--min-app", type=int, default=3)
p.add_argument("--min-webview", type=int, default=0); p.add_argument("--key", default="primary"); p.set_defaults(f=pack)
u = sp.add_parser("update"); u.add_argument("--dir", required=True); u.add_argument("--repo", required=True); u.add_argument("--apk", required=True)
u.add_argument("--version-code", type=int, required=True); u.add_argument("--version-name", required=True); u.add_argument("--min-version-code", type=int, default=1)
u.add_argument("--url", required=True); u.add_argument("--days", type=int, default=120); u.add_argument("--notes", default="")
u.add_argument("--cert", default="F1:72:85:19:60:CF:08:63:34:B7:14:91:FB:DC:48:89:86:1E:1E:EB:60:7E:4F:2D:40:6C:BD:86:FF:90:D6:D0")
u.add_argument("--key", default="primary"); u.add_argument("--out", default="update.json")
u.add_argument("--abi", action="append", default=[], help="abi=path=url (repeatable)"); u.add_argument("--gecko", default="")
u.set_defaults(f=update)
r = sp.add_parser("resign"); r.add_argument("--dir", required=True); r.add_argument("--file", required=True); r.add_argument("--days", type=int, default=45)
r.add_argument("--key", default="ci"); r.set_defaults(f=resign)
v = sp.add_parser("verify"); v.add_argument("--pub", required=True); v.add_argument("--file", required=True); v.add_argument("--sig"); v.set_defaults(f=verify)
a = ap.parse_args(); a.f(a)
