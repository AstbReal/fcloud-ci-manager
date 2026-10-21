#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
轻量 GitLab OAuth2 模拟服务（仅用于本地/CI 测试）

实现的端点与真实 GitLab 契约一致，使 fcloud-ci-hub 的
Spring Security OAuth2 Client 无需任何改动即可完成登录：

  GET  /oauth/authorize   授权页（HTML 表单，选择用户后批准）
  POST /oauth/authorize   同意授权，带 code 重定向到 redirect_uri
  POST /oauth/token       授权码换 access_token（也支持 refresh_token 换取）
  GET  /api/v4/user       返回用户信息（id/username/name/email/avatar_url）
  GET  /api/v4/version    版本信息
  GET  /-/health          存活检查

设计要点：
- 授权码与 access_token 存内存，重启即失效（测试场景可接受）
- 支持"一键登录"：/oauth/authorize?...&auto=<email> 直接批准，便于脚本化测试
- 用户来自 USERS 环境变量或内置默认集合，可增删
- email 为 null 的场景可模拟（GITLAB_EMAIL_NULL_FOR=某个username），
  用于验证 §6.1 的 "email 为 null 时回退 username@域名"
"""
import base64
import html
import json
import os
import secrets
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("GITLAB_PORT", "8929"))
HOST = os.environ.get("GITLAB_HOST", "0.0.0.0")
CLIENT_ID = os.environ.get("GITLAB_CLIENT_ID", "test-client-id")
CLIENT_SECRET = os.environ.get("GITLAB_CLIENT_SECRET", "test-client-secret")
BASE_URL = os.environ.get("GITLAB_BASE_URL", f"http://127.0.0.1:{PORT}")
# 未配置时默认全部用户可发放 email；配置后这些 username 的 email 返回 null
EMAIL_NULL_FOR = set(filter(None, os.environ.get("GITLAB_EMAIL_NULL_FOR", "").split(",")))

# 内置默认用户；可用 GITLAB_USERS 覆盖（JSON 数组）
DEFAULT_USERS = [
    {"id": 1, "username": "root", "name": "GitLab Admin", "email": "root@gitlab.local"},
    {"id": 2, "username": "alice", "name": "Alice Chen", "email": "alice@gitlab.local"},
    {"id": 3, "username": "bob", "name": "Bob Li", "email": "bob@gitlab.local"},
    {"id": 4, "username": "noemail", "name": "No Email User", "email": None},
]


def load_users():
    raw = os.environ.get("GITLAB_USERS")
    if raw:
        try:
            data = json.loads(raw)
            if isinstance(data, list) and data:
                return data
        except json.JSONDecodeError as e:
            print(f"[gitlab-lite] GITLAB_USERS parse error: {e}", file=sys.stderr)
    return DEFAULT_USERS


USERS = load_users()

# 内存态：code -> {user_id, redirect_uri, client_id, expires_at}
AUTH_CODES = {}
# access_token -> {user_id, expires_at}
TOKENS = {}
# refresh_token -> {user_id, expires_at}
REFRESH = {}
LOCK = threading.Lock()

CODE_TTL = 600          # 授权码 10 分钟
TOKEN_TTL = 7200        # access_token 2 小时


def find_user(username=None, user_id=None):
    for u in USERS:
        if username is not None and u.get("username") == username:
            return u
        if user_id is not None and u.get("id") == user_id:
            return u
    return None


def user_payload(u, base_url=None):
    """按 GitLab /api/v4/user 的字段名返回，email 可显式为 null。"""
    email = u.get("email")
    if u.get("username") in EMAIL_NULL_FOR:
        email = None
    base = base_url or BASE_URL
    return {
        "id": u.get("id"),
        "username": u.get("username"),
        "name": u.get("name"),
        "email": email,
        "avatar_url": u.get("avatar_url"),
        "state": "active",
        "web_url": f"{base}/{u.get('username')}",
    }


def purge_expired():
    now = time.time()
    with LOCK:
        for k in [k for k, v in AUTH_CODES.items() if v["expires_at"] < now]:
            AUTH_CODES.pop(k, None)
        for k in [k for k, v in TOKENS.items() if v["expires_at"] < now]:
            TOKENS.pop(k, None)
        for k in [k for k, v in REFRESH.items() if v["expires_at"] < now]:
            REFRESH.pop(k, None)


INDEX_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>GitLab Lite · 测试用 OAuth2 提供方</title>
<style>
 body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;
      background:#f5f6f8;margin:0;padding:40px;color:#111827}}
 .card{{max-width:760px;margin:0 auto;background:#fff;border:1px solid #e5e7eb;
        border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.04);padding:28px}}
 h1{{font-size:20px;margin:0 0 6px}} .muted{{color:#6b7280;font-size:13px}}
 table{{width:100%;border-collapse:collapse;margin-top:18px;font-size:13px}}
 th,td{{text-align:left;padding:8px 10px;border-bottom:1px solid #e5e7eb}}
 th{{color:#6b7280;font-weight:500;background:#fafbfc}}
 code{{background:#f3f4f6;padding:1px 5px;border-radius:4px;
       font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px}}
 a.btn{{display:inline-block;padding:5px 12px;background:#2563eb;color:#fff;
        border-radius:6px;text-decoration:none;font-size:12px}}
 .warn{{background:#fffbeb;border:1px solid #fcd34d;border-radius:6px;
        padding:10px 12px;font-size:12.5px;color:#92400e;margin-top:16px}}
</style></head><body>
<div class="card">
  <h1>GitLab Lite</h1>
  <p class="muted">测试用 OAuth2 提供方 · 非真实 GitLab 实例</p>
  <div class="warn">
    这是 <b>fcloud-ci-hub 的测试替身</b>：实现了 GitLab 的
    <code>/oauth/authorize</code>、<code>/oauth/token</code>、<code>/api/v4/user</code>
    契约，用于端到端验证登录链路。生产请接真实 GitLab。
  </div>
  <table>
    <tr><th>id</th><th>username</th><th>name</th><th>email</th><th>操作</th></tr>
    {rows}
  </table>
  <p class="muted" style="margin-top:16px">
    客户端：<code>{client_id}</code> · 回调白名单需与 FCLOUD_OAUTH_REDIRECT_URI 一致
  </p>
</div></body></html>"""


def index_page(public_base=None):
    rows = []
    for u in USERS:
        p = user_payload(u, public_base)
        email = html.escape(p["email"]) if p["email"] else "<i>null</i>"
        rows.append(
            "<tr>"
            f"<td>{html.escape(str(p['id']))}</td>"
            f"<td><code>{html.escape(str(p['username']))}</code></td>"
            f"<td>{html.escape(str(p['name']))}</td>"
            f"<td>{email}</td>"
            f"<td><a class='btn' href='/oauth/authorize?force_user={urllib.parse.quote(str(p['username']))}'>以此身份登录</a></td>"
            "</tr>"
        )
    return INDEX_HTML.format(rows="".join(rows), client_id=html.escape(CLIENT_ID))


AUTHORIZE_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>授权 · GitLab Lite</title>
<style>
 body{{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;
      background:#f5f6f8;margin:0;padding:48px;color:#111827}}
 .card{{max-width:440px;margin:0 auto;background:#fff;border:1px solid #e5e7eb;
        border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.04);padding:28px}}
 h1{{font-size:18px;margin:0 0 4px}} .muted{{color:#6b7280;font-size:13px}}
 .user{{display:block;margin-top:10px;padding:12px 14px;border:1px solid #e5e7eb;
        border-radius:8px;font-size:14px;color:#111827;text-decoration:none}}
 .user:hover{{border-color:#2563eb;background:#eff6ff}}
 .user small{{color:#6b7280;margin-left:8px}}
 .scope{{margin-top:14px;padding:10px;background:#f9fafb;border-radius:6px;font-size:12.5px;color:#4b5563}}
</style></head><body>
<div class="card">
  <h1>授权应用访问你的账号</h1>
  <p class="muted"><b>fcloud-ci-hub</b> 请求访问你 GitLab 账号的只读信息（<code>read_user</code>）</p>
  {links}
  <div class="scope">scope：<code>read_user</code> · 只读用户资料，不能代表你执行任何写操作</div>
</div></body></html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "GitLabLite/1.0"

    def log_message(self, fmt, *args):
        print(f"[gitlab-lite] {self.address_string()} {fmt % args}", file=sys.stderr)

    # ---------------- 工具 ----------------

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _redirect(self, location):
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    # ---------------- GET ----------------

    def do_GET(self):
        purge_expired()
        parsed = urllib.parse.urlparse(self.path)
        # 支持路径前缀（如 /gitlab），便于反代到主程序同域下
        path = parsed.path
        prefix = os.environ.get("GITLAB_URL_PREFIX", "")
        if prefix and path.startswith(prefix):
            path = path[len(prefix):]
        if not path:
            path = "/"
        q = urllib.parse.parse_qs(parsed.query)

        if path == "/":
            return self._send(200, index_page(self.public_base()),
                              "text/html; charset=utf-8")

        if path in ("/-/health", "/health"):
            return self._send(200, {"status": "ok", "users": len(USERS)})

        if path == "/oauth/authorize":
            return self.authorize_page(q)

        if path == "/api/v4/version":
            return self._send(200, {"version": "16.11.0-lite", "revision": "lite"})

        if path == "/api/v4/user":
            return self.api_user()

        return self._send(404, {"error": "not_found", "path": path})

    def authorize_page(self, q):
        # 强制以某用户身份直接批准（脚本化测试用）
        force = (q.get("force_user") or [None])[0]
        if force:
            u = find_user(username=force)
            if not u:
                return self._send(400, {"error": "unknown_user", "username": force})
            return self.approve(u, q, redirect_uri=(q.get("redirect_uri") or [None])[0])

        # GET 带 user_id 直接批准：让授权动作走 GET 顶级导航，
        # 避免 SameSite=Lax cookie 在跨站 POST 导航响应中被 Chrome 丢弃
        #（本地 8080 ↔ 8929 跨 origin 联调时，POST 表单会导致登录后 cookie 丢失、auth/me 401）
        uid = (q.get("user_id") or [None])[0]
        if uid and uid.isdigit():
            u = find_user(user_id=int(uid))
            if u:
                return self.approve(u, q, redirect_uri=(q.get("redirect_uri") or [None])[0])

        # 授权页：每用户一个 GET 链接（保留全部 OAuth 查询参数）
        links = []
        for u in USERS:
            params = {k: v[0] for k, v in q.items() if v}
            params["user_id"] = str(u.get("id"))
            href = "/oauth/authorize?" + urllib.parse.urlencode(params)
            email = u.get("email") or "(email 为空，用于测试 §6.1 回退)"
            links.append(
                f"<a class='user' href='{html.escape(href)}'>"
                f"{html.escape(str(u.get('username')))}"
                f"<small>{html.escape(str(u.get('name')))} · {html.escape(str(email))}</small></a>"
            )
        return self._send(200, AUTHORIZE_HTML.format(links="".join(links)),
                          "text/html; charset=utf-8")

    def api_user(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return self._send(401, {"error": "invalid_token",
                                    "error_description": "missing bearer token"})
        token = auth[7:].strip()
        with LOCK:
            rec = TOKENS.get(token)
        if not rec:
            return self._send(401, {"error": "invalid_token",
                                    "error_description": "unknown or expired token"})
        u = find_user(user_id=rec["user_id"])
        if not u:
            return self._send(401, {"error": "invalid_token"})
        return self._send(200, user_payload(u, self.public_base()))

    def public_base(self):
        """
        推导"浏览器可达"的 GitLab 根地址（含路径前缀）。
        """
        explicit = os.environ.get("GITLAB_PUBLIC_URL")
        if explicit:
            return explicit.rstrip("/")
        prefix = os.environ.get("GITLAB_URL_PREFIX", "")
        host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host")
        if not host:
            base = BASE_URL
        else:
            scheme = self.headers.get("X-Forwarded-Proto") or "http"
            base = f"{scheme}://{host}"
        return base.rstrip("/") + prefix

    # ---------------- POST ----------------

    def do_POST(self):
        purge_expired()
        path = urllib.parse.urlparse(self.path).path
        prefix = os.environ.get("GITLAB_URL_PREFIX", "")
        if prefix and path.startswith(prefix):
            path = path[len(prefix):]
        if not path:
            path = "/"
        raw = self._body()

        if path == "/oauth/authorize":
            form = urllib.parse.parse_qs(raw.decode("utf-8", "replace"))
            uid = (form.get("user_id") or [None])[0]
            u = find_user(user_id=int(uid)) if uid and uid.isdigit() else None
            if not u:
                return self._send(400, {"error": "invalid_user"})
            return self.approve(u, form,
                                redirect_uri=(form.get("redirect_uri") or [None])[0])

        if path == "/oauth/token":
            return self.token(raw)

        return self._send(404, {"error": "not_found", "path": path})

    def approve(self, user, params, redirect_uri):
        if not redirect_uri:
            return self._send(400, {"error": "invalid_request",
                                    "error_description": "missing redirect_uri"})
        code = secrets.token_urlsafe(32)
        with LOCK:
            AUTH_CODES[code] = {
                "user_id": user.get("id"),
                "redirect_uri": redirect_uri,
                "expires_at": time.time() + CODE_TTL,
            }
        state = (params.get("state") or [None])[0]
        sep = "&" if "?" in redirect_uri else "?"
        loc = f"{redirect_uri}{sep}code={urllib.parse.quote(code)}"
        if state:
            loc += f"&state={urllib.parse.quote(state)}"
        return self._redirect(loc)

    def token(self, raw):
        ctype = (self.headers.get("Content-Type") or "").lower()
        if "application/x-www-form-urlencoded" in ctype:
            form = urllib.parse.parse_qs(raw.decode("utf-8", "replace"))
            get = lambda k: (form.get(k) or [None])[0]
        else:
            try:
                form = json.loads(raw or b"{}")
            except json.JSONDecodeError:
                return self._send(400, {"error": "invalid_request",
                                        "error_description": "bad body"})
            get = lambda k: form.get(k)

        grant = get("grant_type")
        client_id = get("client_id")
        client_secret = get("client_secret")

        # 支持 HTTP Basic 客户端认证（Spring Security 默认行为）
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Basic "):
            try:
                dec = base64.b64decode(auth[6:]).decode()
                cid, _, csec = dec.partition(":")
                client_id = client_id or cid
                client_secret = client_secret or csec
            except Exception:
                pass

        if client_id != CLIENT_ID or client_secret != CLIENT_SECRET:
            return self._send(401, {"error": "invalid_client",
                                    "error_description": "client authentication failed"})

        if grant == "authorization_code":
            code = get("code")
            redirect_uri = get("redirect_uri")
            with LOCK:
                rec = AUTH_CODES.pop(code, None) if code else None
            if not rec:
                return self._send(400, {"error": "invalid_grant",
                                        "error_description": "unknown or used code"})
            if redirect_uri and rec["redirect_uri"] != redirect_uri:
                return self._send(400, {"error": "invalid_grant",
                                        "error_description": "redirect_uri mismatch"})
            return self._issue(rec["user_id"])

        if grant == "refresh_token":
            rt = get("refresh_token")
            with LOCK:
                rec = REFRESH.pop(rt, None) if rt else None
            if not rec:
                return self._send(400, {"error": "invalid_grant",
                                        "error_description": "invalid refresh token"})
            return self._issue(rec["user_id"])

        return self._send(400, {"error": "unsupported_grant_type", "grant_type": grant})

    def _issue(self, user_id):
        at = secrets.token_urlsafe(40)
        rt = secrets.token_urlsafe(40)
        now = time.time()
        with LOCK:
            TOKENS[at] = {"user_id": user_id, "expires_at": now + TOKEN_TTL}
            REFRESH[rt] = {"user_id": user_id, "expires_at": now + 7 * 86400}
        return self._send(200, {
            "access_token": at,
            "token_type": "Bearer",
            "expires_in": TOKEN_TTL,
            "refresh_token": rt,
            "scope": "read_user",
            "created_at": int(now),
        })


def main():
    print(f"[gitlab-lite] listening on {HOST}:{PORT}", file=sys.stderr)
    print(f"[gitlab-lite] client_id={CLIENT_ID}", file=sys.stderr)
    print(f"[gitlab-lite] users={[u.get('username') for u in USERS]}", file=sys.stderr)
    if EMAIL_NULL_FOR:
        print(f"[gitlab-lite] email=null for: {sorted(EMAIL_NULL_FOR)}", file=sys.stderr)
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
