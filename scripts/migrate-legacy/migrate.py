#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""历史项目 → fcloud-ci-manager 迁移工具（零第三方依赖，Python 3.8+）。

扫描的历史项目结构::

    <root>/apps/<应用目录>/
        init.py                          # XcApp 子类：set_gitlab_id / set_scc_master_name ...
        ama-xc/<env>/
            scc/deployment/deployment.yaml   # 模块 scc，fileKey deployment.yaml
            build/docker/Dockerfile          # 模块 image（build/docker → image）
            build/docker/start.template
            build/backend/*.sh               # 合并成 invoke.py（按环境各一份）
            build/fronted/*.sh               # 历史项目拼写就是 fronted

映射规则::

    ama-xc/pro/scc/deployment/deployment.yaml
      → 应用 ama-biz-cms · 环境 pro · 模块 scc · fileKey deployment.yaml
        （模块 × 环境已经唯一，默认不保留 deployment/ 这层历史目录）

    ama-xc/pro/build/docker/Dockerfile
      → 模块 image · fileKey Dockerfile

    ama-xc/pro/build/{backend,fronted}/*.sh
      → 合并成 invoke.py，上传到 --invoke-module（默认 image）· 环境 pro

默认 dry-run，只打印计划不写任何东西；确认后加 ``--apply`` 才落库。
模块不存在会自动创建并绑定环境；应用默认必须已存在（``--create-apps`` 可放开）。

用法::

    # 1) 看计划
    python3 migrate.py --root /data/legacy --base-url http://10.0.0.5:8080 \\
        --cookie "fcloud_token=eyJ..."

    # 2) 真跑
    python3 migrate.py --root /data/legacy --base-url http://10.0.0.5:8080 \\
        --cookie "fcloud_token=eyJ..." --apply

Cookie 取法：登录管理端 → DevTools → Application → Cookies → 复制 fcloud_token
（HttpOnly 的 cookie 在 console 里 document.cookie 看不到）。有效期 24h（记住我 7 天）。
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------- 常量

INIT_GITLAB_ID = re.compile(r"set_gitlab_id\(\s*['\"]([^'\"]+)['\"]")
INIT_SCC_MASTER = re.compile(r"set_scc_master_name\(\s*['\"]([^'\"]+)['\"]")
INIT_SCC_NAME = re.compile(r"set_scc_name\(\s*['\"]([^'\"]+)['\"]")

SKIP_NAMES = {".git", ".svn", "__pycache__", ".idea", ".DS_Store", "node_modules"}

# build/ 下的这些子目录放的是构建脚本，合并成 invoke.py，不按普通文件上传
SCRIPT_STAGES = {"backend", "fronted", "frontend"}

# 阶段目录 → 目标模块 code。历史项目里 Dockerfile/start.template 放在 build/docker 下，
# 新系统对应 image 模块。其余目录按一级目录名当模块。
DEFAULT_MODULE_MAP = {"build/docker": "image"}


class HubError(RuntimeError):
    """服务端返回 code != 0，或 HTTP 层失败。"""


# ---------------------------------------------------------------- 数据结构


@dataclass
class LegacyFile:
    env_code: str          # 映射后的环境 code
    env_dir: str           # 原始目录名，如 pro
    module: str            # 模块 code
    file_key: str          # 文件名（只有一层）
    path: Path
    size: int


@dataclass
class LegacyApp:
    dir: Path
    code: str
    gitlab_id: str = ""
    scc_master: str = ""
    scc_name: str = ""
    files: List[LegacyFile] = field(default_factory=list)
    # 每个环境自己的脚本：ama-xc/<env>/build/{backend,fronted}/*.sh
    env_scripts: Dict[str, List[Path]] = field(default_factory=dict)
    # 老布局：应用根目录下的 backend/ frontend/ 脚本（新布局没有）
    app_scripts: List[Path] = field(default_factory=list)
    # 同一「模块 + 环境」里出现同名文件：fileKey 只有文件名，没法共存
    conflicts: List[str] = field(default_factory=list)

    def module_envs(self) -> Dict[str, List[str]]:
        """只在有非空文件时才需要这个模块，避免空占位目录也建一堆空模块。"""
        out: Dict[str, List[str]] = {}
        for f in self.files:
            if f.size == 0:
                continue
            out.setdefault(f.module, [])
            if f.env_code not in out[f.module]:
                out[f.module].append(f.env_code)
        return out

    def env_codes(self) -> List[str]:
        out: List[str] = []
        for f in self.files:
            if f.env_code not in out:
                out.append(f.env_code)
        return out

    def scripts_by_env(self) -> Dict[str, List[Path]]:
        """每个环境要合并哪些脚本。

        新布局按环境存放（ama-xc/uat/build/backend ...），就按环境各生成一份；
        老布局是应用级 backend/frontend，则每个环境都用同一份。
        """
        if self.env_scripts:
            return {env: sorted(paths) for env, paths in self.env_scripts.items() if paths}
        if self.app_scripts:
            return {env: list(self.app_scripts) for env in self.env_codes()}
        return {}


# ---------------------------------------------------------------- 扫描


def parse_init(path: Path) -> Dict[str, str]:
    if not path.is_file():
        return {}
    text = path.read_text(encoding="utf-8", errors="replace")
    out = {}
    for key, pattern in (
        ("gitlab_id", INIT_GITLAB_ID),
        ("scc_master", INIT_SCC_MASTER),
        ("scc_name", INIT_SCC_NAME),
    ):
        m = pattern.search(text)
        if m:
            out[key] = m.group(1).strip()
    return out


def iter_files(base: Path) -> Iterable[Path]:
    for p in sorted(base.rglob("*")):
        if not p.is_file():
            continue
        rel_parts = p.relative_to(base).parts
        if any(part in SKIP_NAMES for part in rel_parts):
            continue
        if p.name.startswith("."):
            continue
        yield p


def scan_app(app_dir: Path, env_map: Dict[str, str],
             module_map: Dict[str, str]) -> LegacyApp:
    init = parse_init(app_dir / "init.py")
    code = init.get("scc_master") or app_dir.name
    app = LegacyApp(
        dir=app_dir,
        code=code,
        gitlab_id=init.get("gitlab_id", ""),
        scc_master=init.get("scc_master", ""),
        scc_name=init.get("scc_name", ""),
    )

    # (env_code, env_dir, module, key_parts, path, size)；fileKey 等全部扫完再定
    pending: List[Tuple[str, str, str, Tuple[str, ...], Path, int]] = []

    xc = app_dir / "ama-xc"
    if xc.is_dir():
        for env_dir in sorted(p for p in xc.iterdir() if p.is_dir()):
            env_code = env_map.get(env_dir.name, env_dir.name)
            for path in iter_files(env_dir):
                rel = path.relative_to(env_dir)
                if len(rel.parts) < 2:
                    # 直接丢在环境目录下的文件没有模块归属，报出来让人处理
                    print(f"  [warn] 跳过（缺模块目录）: {path}", file=sys.stderr)
                    continue

                head = rel.parts[0]
                if head == "build" and len(rel.parts) >= 3:
                    stage = rel.parts[1]
                    # build/backend、build/fronted（历史项目拼写就是 fronted）里是构建脚本
                    if stage in SCRIPT_STAGES:
                        if path.suffix.lower() == ".sh":
                            app.env_scripts.setdefault(env_code, []).append(path)
                        else:
                            print(f"  [warn] 构建目录里不是 .sh，忽略: {path}",
                                  file=sys.stderr)
                        continue
                    mapped = module_map.get(f"build/{stage}")
                    if mapped:
                        module = mapped
                        key_parts = rel.parts[2:]
                    else:
                        # 没配映射就按一级目录当模块，保留后续路径
                        module = head
                        key_parts = rel.parts[1:]
                else:
                    module = module_map.get(head, head)
                    key_parts = rel.parts[1:]

                pending.append((env_code, env_dir.name, module, key_parts, path,
                                path.stat().st_size))

    # 老布局：应用根目录下的 backend/ frontend/ 脚本（新布局放在各环境 build/ 下）
    for name in ("backend", "frontend", "fronted"):
        d = app_dir / name
        if not d.is_dir():
            continue
        for path in sorted(d.rglob("*.sh")):
            if any(part in SKIP_NAMES for part in path.relative_to(app_dir).parts):
                continue
            app.app_scripts.append(path)

    # fileKey 只有文件名：同一「模块 + 环境目录」下出现同名文件就无法共存，只留第一个。
    # 注意按 env_dir（原始目录名）分组，不按映射后的 env_code —— 两个目录映射到同一个
    # 环境由 build() 里的 seen_targets 处理，那里给的提示是「检查 --env-map」，
    # 比让用户改名更贴近真实原因。
    grouped: Dict[Tuple[str, str], Dict[str, List[Path]]] = {}
    for _, env_dir_name, module, key_parts, path, _ in pending:
        grouped.setdefault((module, env_dir_name), {}).setdefault(
            key_parts[-1], []).append(path)

    keep: set = set()
    for (module, env_dir_name), names in sorted(grouped.items()):
        for name, paths in names.items():
            keep.add(paths[0])
            if len(paths) > 1:
                rels = ", ".join(str(p.relative_to(app_dir)) for p in paths)
                app.conflicts.append(
                    f"{module}@{env_dir_name} 文件名重复：{name}（{rels}）"
                    f"——fileKey 只能是文件名，无法共存，请先给其中一个改名")

    for env_code, env_dir_name, module, key_parts, path, size in pending:
        if path not in keep:
            continue
        app.files.append(LegacyFile(
            env_code=env_code,
            env_dir=env_dir_name,
            module=module,
            file_key=key_parts[-1],
            path=path,
            size=size,
        ))

    return app


def scan_root(root: Path, apps_subdir: str, env_map: Dict[str, str],
              module_map: Dict[str, str]) -> List[LegacyApp]:
    base = root / apps_subdir if apps_subdir else root
    if not base.is_dir():
        raise SystemExit(f"找不到应用目录: {base}")
    apps = []
    for d in sorted(p for p in base.iterdir() if p.is_dir()):
        if d.name in SKIP_NAMES:
            continue
        if not (d / "init.py").is_file() and not (d / "ama-xc").is_dir():
            continue
        apps.append(scan_app(d, env_map, module_map))
    return apps


# ---------------------------------------------------------------- Hub API


class HubClient:
    def __init__(self, base_url: str, cookie: str, timeout: int = 60,
                 use_env_proxy: bool = False):
        self.base = base_url.rstrip("/")
        self.cookie = cookie
        self.timeout = timeout
        # 管理端在内网：默认不走 HTTP_PROXY/HTTPS_PROXY（很多机器上配了公司代理，
        # 会把 10.x 的请求也转发出去，表现就是 502/连不上）。确有需要加 --use-env-proxy。
        proxy = None if use_env_proxy else {}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxy))

    # ---- 底层 ----

    def _headers(self, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
        headers = {
            "Cookie": self.cookie,
            "Accept": "application/json",
            "User-Agent": "fcloud-legacy-migrator/1.0",
        }
        if extra:
            headers.update(extra)
        return headers

    def _parse(self, raw: bytes, status: int, url: str):
        try:
            body = json.loads(raw.decode("utf-8")) if raw else {}
        except (ValueError, UnicodeDecodeError):
            snippet = raw[:200].decode("utf-8", "replace")
            raise HubError(f"{url} 返回非 JSON（HTTP {status}）：{snippet}")

        if isinstance(body, dict) and body.get("code") not in (0, None):
            raise HubError(f"{url} → {body.get('message') or body.get('code')}")
        if isinstance(body, dict) and "data" in body:
            return body["data"]
        return body

    def _open(self, req: urllib.request.Request) -> bytes:
        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            raw = e.read() or b""
            if e.code == 401:
                raise HubError(
                    "401 未登录：cookie 失效或没带对。请重新从浏览器复制 fcloud_token"
                )
            if e.code == 403:
                raise HubError("403 无权限：迁移需要系统管理员账号")
            try:
                body = json.loads(raw.decode("utf-8"))
                msg = body.get("message") or body.get("code")
            except (ValueError, UnicodeDecodeError):
                msg = raw[:200].decode("utf-8", "replace")
            raise HubError(f"HTTP {e.code} {req.full_url} → {msg}")
        except urllib.error.URLError as e:
            raise HubError(f"连不上 {self.base}：{e.reason}")

    def get(self, path: str, params: Optional[Dict[str, object]] = None):
        url = self.base + path
        if params:
            clean = {k: v for k, v in params.items() if v is not None}
            if clean:
                url += "?" + urllib.parse.urlencode(clean)
        return self._parse(self._open(urllib.request.Request(
            url, headers=self._headers())), 200, url)

    def send_json(self, method: str, path: str, payload: Dict[str, object]):
        url = self.base + path
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            url, data=data, method=method,
            headers=self._headers({"Content-Type": "application/json"}))
        return self._parse(self._open(req), 200, url)

    def upload_file(self, module_id, env_id, file_key: str, path: Path,
                    remark: str):
        boundary = "----fcloud" + uuid.uuid4().hex
        parts: List[bytes] = []

        def field(name: str, value: str) -> None:
            parts.append(
                f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"'
                f"\r\n\r\n{value}\r\n".encode("utf-8"))

        field("fileKey", file_key)
        field("environmentId", str(env_id))
        field("remark", remark)

        filename = Path(file_key).name
        mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        if filename.isascii():
            disposition = f'name="file"; filename="{filename}"'
        else:
            quoted = urllib.parse.quote(filename)
            disposition = f"name=\"file\"; filename*=UTF-8''{quoted}"
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; {disposition}"
            f"\r\nContent-Type: {mime}\r\n\r\n".encode("utf-8"))
        parts.append(path.read_bytes())
        parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))

        url = f"{self.base}/api/console/modules/{module_id}/files"
        req = urllib.request.Request(
            url, data=b"".join(parts), method="POST",
            headers=self._headers(
                {"Content-Type": f"multipart/form-data; boundary={boundary}"}))
        return self._parse(self._open(req), 200, url)


# ---------------------------------------------------------------- 迁移器


@dataclass
class PlanItem:
    kind: str                 # create_app | create_module | bind_env | upload | skip
    app: str
    detail: str
    payload: dict = field(default_factory=dict)


class Migrator:
    def __init__(self, client: HubClient, opts: argparse.Namespace):
        self.client = client
        self.opts = opts
        self.apps_by_code: Dict[str, dict] = {}
        self.apps_by_gitlab: Dict[str, dict] = {}
        self.envs_by_code: Dict[str, dict] = {}
        self.modules: Dict[str, Dict[str, dict]] = {}   # appCode -> moduleCode -> module
        self.bindings: Dict[str, set] = {}              # moduleId -> envId set
        self.existing_files: Dict[str, set] = {}        # f"{moduleId}:{envId}" -> fileKey set
        self.created_apps: Dict[str, dict] = {}
        self.created_modules: Dict[str, dict] = {}
        self.plan: List[PlanItem] = []
        self.errors: List[str] = []
        self.uploaded = 0
        self.skipped = 0

    # ---- 预加载 ----

    def preload(self) -> None:
        for a in self.client.get("/api/console/admin/applications") or []:
            self.apps_by_code[a["code"]] = a
            if a.get("gitlabProjectId"):
                self.apps_by_gitlab[str(a["gitlabProjectId"])] = a
        for e in self.client.get("/api/console/admin/environments") or []:
            self.envs_by_code[e["code"]] = e

    def resolve_app(self, legacy: LegacyApp) -> Optional[dict]:
        if legacy.gitlab_id and legacy.gitlab_id in self.apps_by_gitlab:
            return self.apps_by_gitlab[legacy.gitlab_id]
        if legacy.code in self.apps_by_code:
            return self.apps_by_code[legacy.code]
        if legacy.scc_name and legacy.scc_name in self.apps_by_code:
            return self.apps_by_code[legacy.scc_name]
        return self.created_apps.get(legacy.code)

    def modules_of(self, app_code: str, app_id) -> Dict[str, dict]:
        if app_code not in self.modules:
            items = self.client.get(f"/api/console/admin/applications/{app_id}/modules") or []
            self.modules[app_code] = {m["code"]: m for m in items}
        return self.modules[app_code]

    def bindings_of(self, module_id) -> set:
        key = str(module_id)
        if key not in self.bindings:
            ids = self.client.get(
                f"/api/console/admin/modules/{module_id}/environment-ids") or []
            self.bindings[key] = {str(i) for i in ids}
        return self.bindings[key]

    def files_of(self, module_id, env_id) -> set:
        key = f"{module_id}:{env_id}"
        if key not in self.existing_files:
            items = self.client.get(
                f"/api/console/modules/{module_id}/files",
                {"environmentId": env_id}) or []
            self.existing_files[key] = {f["fileKey"] for f in items}
        return self.existing_files[key]

    # ---- 计划 ----

    def build(self, legacy_apps: Sequence[LegacyApp]) -> None:
        for legacy in legacy_apps:
            app = self.resolve_app(legacy)
            creating = app is None
            header = f"应用 {legacy.code} ← {legacy.dir}"
            if legacy.gitlab_id:
                header += f"（gitlab #{legacy.gitlab_id}）"

            if creating and not self.opts.create_apps:
                print(f"\n== {header}")
                self.errors.append(
                    f"{legacy.code}: 系统中没有这个应用（按 gitlab id / code / scc_name 都没匹配上）；"
                    f"确认后加 --create-apps，或先在管理端建好应用")
                print("   [skip] 应用不存在")
                continue

            print(f"\n== {header}")
            if creating:
                print("   [+] 计划创建应用（模块 id 在执行时解析）")
                self.plan.append(PlanItem(
                    "create_app", legacy.code, "创建应用 " + legacy.code,
                    {"code": legacy.code, "name": legacy.code,
                     "description": f"由历史项目迁移：{legacy.dir.name}"}))
            else:
                print(f"   命中应用 id={app['id']} code={app['code']}")

            for msg in legacy.conflicts:
                self.errors.append(f"{legacy.code}: {msg}")
                print(f"   [!] {msg}")

            if not legacy.files and not (legacy.env_scripts or legacy.app_scripts):
                print("   （没有可迁移的文件）")
                continue

            app_code = app["code"] if app else legacy.code
            known_modules = {} if creating else self.modules_of(app_code, app["id"])
            planned_items: Dict[Tuple[str, str], PlanItem] = {}
            planned_codes: Dict[Tuple[str, str], set] = {}

            def ensure_module(mod_code: str, env_codes: List[str]) -> None:
                """按需创建模块，或在已有模块上补齐环境绑定。

                同一模块可能被调用多次（文件循环 + invoke.py），创建动作只登记一次，
                环境绑定取并集 —— 否则 invoke.py 会传进一个没绑该环境的模块，
                流水线拉取时看不到它。
                """
                key = (app_code, mod_code)
                usable = [c for c in env_codes if c in self.envs_by_code]
                mod = known_modules.get(mod_code)
                if mod is None:
                    item = planned_items.get(key)
                    if item is None:
                        item = PlanItem("create_module", app_code, "",
                                        {"app_code": app_code, "code": mod_code,
                                         "name": mod_code, "environmentIds": []})
                        planned_items[key] = item
                        planned_codes[key] = set()
                        self.plan.append(item)
                    for c in usable:
                        eid = self.envs_by_code[c]["id"]
                        if eid not in item.payload["environmentIds"]:
                            item.payload["environmentIds"].append(eid)
                        planned_codes[key].add(c)
                    item.detail = (f"{app_code}/{mod_code}"
                                   f"（绑定 {', '.join(sorted(planned_codes[key])) or '无环境'}）")
                    return

                bound = self.bindings_of(mod["id"])
                missing = [c for c in usable if str(self.envs_by_code[c]["id"]) not in bound]
                if not missing:
                    return
                action = planned_items.get(key)
                if action is None:
                    action = PlanItem(
                        "bind_env", app_code,
                        f"{app_code}/{mod_code} 补绑环境",
                        {"module_id": mod["id"], "env_ids": set(bound), "codes": set()})
                    planned_items[key] = action
                    self.plan.append(action)
                action.payload["env_ids"] |= {str(self.envs_by_code[c]["id"]) for c in missing}
                action.payload["codes"] |= set(missing)
                action.detail = (f"{app_code}/{mod_code} 补绑环境 "
                                 f"{', '.join(sorted(action.payload['codes']))}")

            for module, env_codes in legacy.module_envs().items():
                ensure_module(module, env_codes)

            script_groups: Dict[str, List[Path]] = {}
            if not self.opts.no_scripts:
                script_groups = legacy.scripts_by_env()
                if self.opts.invoke_env:
                    limit = {x.strip() for x in self.opts.invoke_env.split(",") if x.strip()}
                    script_groups = {e: s for e, s in script_groups.items() if e in limit}
                if script_groups:
                    # invoke.py 要落到这些环境，模块绑定也得覆盖到，否则流水线取不到
                    ensure_module(self.opts.invoke_module, sorted(script_groups))

            seen_targets: set = set()
            for f in legacy.files:
                if f.env_code not in self.envs_by_code:
                    self.errors.append(
                        f"{legacy.code}/{f.module}: 环境 {f.env_dir} 在系统里没有对应 code"
                        f"（可用 --env-map {f.env_dir}=<已有code>）")
                    continue
                if f.size == 0:
                    self.skipped += 1
                    print(f"   [skip] 空文件 {f.path.relative_to(legacy.dir)}")
                    continue
                target_key = (f.module, f.env_code, f.file_key)
                if target_key in seen_targets:
                    self.skipped += 1
                    print(f"   [skip] 环境映射冲突：{f.env_dir}/ → {f.env_code} 已有同名文件 "
                          f"{f.module}/{f.file_key}（先到先得，检查 --env-map）")
                    continue
                seen_targets.add(target_key)
                mod = known_modules.get(f.module)
                if mod is not None and self.opts.skip_existing:
                    env_id = self.envs_by_code[f.env_code]["id"]
                    if f.file_key in self.files_of(mod["id"], env_id):
                        self.skipped += 1
                        print(f"   [skip] 已存在 {app_code}/{f.module}@{f.env_code} {f.file_key}")
                        continue
                self.plan.append(PlanItem(
                    "upload", app_code,
                    f"{app_code}/{f.module}@{f.env_code} ← {f.file_key}"
                    f"（{human_size(f.size)}）",
                    {"app_code": app_code, "module": f.module,
                     "env_code": f.env_code, "file_key": f.file_key,
                     "path": str(f.path), "legacy_dir": f.env_dir}))

            inv_mod = known_modules.get(self.opts.invoke_module)
            for env_code, scripts in script_groups.items():
                if env_code not in self.envs_by_code:
                    self.errors.append(
                        f"{legacy.code}: invoke.py 目标环境 {env_code} 不存在")
                    continue
                if inv_mod is not None and self.opts.skip_existing:
                    env_id = self.envs_by_code[env_code]["id"]
                    if "invoke.py" in self.files_of(inv_mod["id"], env_id):
                        self.skipped += 1
                        print(f"   [skip] 已存在 {app_code}/{self.opts.invoke_module}"
                              f"@{env_code} invoke.py")
                        continue
                content = build_invoke_py(scripts, scripts_base(scripts),
                                          f"{app_code} · {env_code}")
                self.plan.append(PlanItem(
                    "upload_text", app_code,
                    f"{app_code}/{self.opts.invoke_module}@{env_code} ← invoke.py"
                    f"（合并 {len(scripts)} 个脚本）",
                    {"app_code": app_code, "module": self.opts.invoke_module,
                     "env_code": env_code, "file_key": "invoke.py",
                     "content": content, "legacy_dir": env_code}))

    # ---- 执行 ----

    def apply(self) -> None:
        for item in self.plan:
            try:
                if item.kind == "create_app":
                    data = self.client.send_json(
                        "POST", "/api/console/admin/applications", item.payload)
                    self.created_apps[data["code"]] = data
                    self.apps_by_code[data["code"]] = data
                    print(f"   [+] 创建应用 {data['code']} id={data['id']}")
                elif item.kind == "create_module":
                    p = item.payload
                    target_app = self.apps_by_code.get(p["app_code"])
                    if target_app is None:
                        raise HubError(f"应用 {p['app_code']} 还不存在，模块创建顺序有误")
                    data = self.client.send_json(
                        "POST",
                        f"/api/console/admin/applications/{target_app['id']}/modules",
                        {k: p[k] for k in ("code", "name", "environmentIds")})
                    self.modules.setdefault(p["app_code"], {})[data["code"]] = data
                    self.created_modules[f"{p['app_code']}/{data['code']}"] = data
                    print(f"   [+] 创建模块 {item.detail} id={data['id']}")
                elif item.kind == "bind_env":
                    self.client.send_json(
                        "PUT", f"/api/console/admin/modules/{item.payload['module_id']}",
                        {"environmentIds": [int(x) for x in
                                            sorted(item.payload["env_ids"])]})
                    print(f"   [~] {item.detail}")
                elif item.kind == "upload":
                    self._do_upload(item)
                elif item.kind == "upload_text":
                    self._do_upload_text(item)
            except HubError as e:
                self.errors.append(f"{item.detail}: {e}")
                print(f"   [!] 失败 {item.detail}: {e}", file=sys.stderr)

    def _module_id(self, app_code: str, module_code: str):
        mod = self.modules.get(app_code, {}).get(module_code)
        return mod["id"] if mod else None

    def _do_upload(self, item: PlanItem) -> None:
        p = item.payload
        module_id = self._module_id(p["app_code"], p["module"])
        if module_id is None:
            raise HubError(f"模块 {p['module']} 未创建成功")
        env_id = self.envs_by_code[p["env_code"]]["id"]
        remark = f"迁移自历史项目 {p['legacy_dir']}"
        self.client.upload_file(module_id, env_id, p["file_key"], Path(p["path"]), remark)
        self.existing_files.setdefault(f"{module_id}:{env_id}", set()).add(p["file_key"])
        self.uploaded += 1
        print(f"   [↑] {item.detail}")

    def _do_upload_text(self, item: PlanItem) -> None:
        p = item.payload
        module_id = self._module_id(p["app_code"], p["module"])
        if module_id is None:
            raise HubError(f"模块 {p['module']} 未创建成功")
        env_id = self.envs_by_code[p["env_code"]]["id"]
        fd, tmp_name = tempfile.mkstemp(prefix="invoke_", suffix=".py")
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(p["content"])
            self.client.upload_file(module_id, env_id, p["file_key"], tmp,
                                    "由历史项目 backend/frontend 脚本合并")
        finally:
            tmp.unlink(missing_ok=True)
        self.uploaded += 1
        print(f"   [↑] {item.detail}")


# ---------------------------------------------------------------- invoke.py


def py_literal(text: str) -> str:
    """把脚本内容变成 Python 源码里的字符串字面量：优先三引号原样保留，冲突就 repr。"""
    if "'''" not in text and not text.endswith("\\"):
        return "r'''" + text + "'''"
    if '"""' not in text and not text.endswith("\\"):
        return 'r"""' + text + '"""'
    return repr(text)


def scripts_base(scripts: Sequence[Path]) -> Path:
    """脚本相对路径的基准目录：多个脚本取共同父目录，单个脚本取其父目录。"""
    if len(scripts) == 1:
        return scripts[0].parent
    return Path(os.path.commonpath([str(s.parent) for s in scripts]))


def build_invoke_py(scripts: Sequence[Path], base: Path, title: str) -> str:
    rels = [s.relative_to(base).as_posix() for s in scripts]
    blocks = []
    for s, rel in zip(scripts, rels):
        body = s.read_text(encoding="utf-8", errors="replace")
        if body and not body.endswith("\n"):
            body += "\n"
        blocks.append(f"    {rel!r}: {py_literal(body)},")

    return f'''#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""{title} 的历史部署脚本合并版（由 fcloud-ci-manager 迁移工具生成）。

来源：{", ".join(rels)}
用法：
    python3 invoke.py                 # 依次执行全部脚本
    python3 invoke.py backend/x.sh    # 只跑指定脚本
    python3 invoke.py --list          # 列出可用脚本
"""

import subprocess
import sys

SCRIPTS = {{
{os.linesep.join(blocks)}
}}


def main(argv):
    if "--list" in argv:
        for name in SCRIPTS:
            print(name)
        return 0
    names = [a for a in argv if not a.startswith("-")] or list(SCRIPTS)
    for name in names:
        body = SCRIPTS.get(name)
        if body is None:
            print("unknown script: %s" % name, file=sys.stderr)
            return 2
        print("==> %s" % name, flush=True)
        rc = subprocess.call(["bash", "-euo", "pipefail", "-c", body])
        if rc != 0:
            print("脚本 %s 执行失败，退出码 %d" % (name, rc), file=sys.stderr)
            return rc
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
'''


# ---------------------------------------------------------------- 杂项


def human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f}{unit}" if unit == "B" else f"{n:.1f}{unit}"
        n /= 1024.0
    return f"{n}B"


def parse_env_map(raw: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for chunk in filter(None, (c.strip() for c in raw.split(","))):
        if "=" not in chunk:
            raise SystemExit(f"--env-map 格式应为 老目录=新code，收到：{chunk}")
        k, v = chunk.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def parse_module_map(raw: str) -> Dict[str, str]:
    """阶段目录 → 模块 code；默认 build/docker → image，其余按一级目录名。"""
    out = dict(DEFAULT_MODULE_MAP)
    for chunk in filter(None, (c.strip() for c in raw.split(","))):
        if "=" not in chunk:
            raise SystemExit(f"--module-map 格式应为 阶段路径=模块code，收到：{chunk}")
        k, v = chunk.split("=", 1)
        out[k.strip().strip("/")] = v.strip()
    return out


def read_cookie(args: argparse.Namespace) -> str:
    raw = args.cookie or os.environ.get("FCLOUD_COOKIE", "")
    if not raw and args.cookie_file:
        raw = Path(args.cookie_file).read_text(encoding="utf-8").strip()
    if not raw:
        raise SystemExit(
            "缺少 cookie：用 --cookie \"fcloud_token=...\" 或 --cookie-file，或设 FCLOUD_COOKIE")
    raw = raw.strip()
    if raw.lower().startswith("cookie:"):
        raw = raw[7:].strip()
    if "=" not in raw:
        raw = f"fcloud_token={raw}"
    return raw


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="历史项目 → fcloud-ci-manager 迁移（默认 dry-run）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n  python3 migrate.py --root /data/legacy --base-url http://10.0.0.5:8080 "
               "--cookie fcloud_token=...\n  ... --apply")
    p.add_argument("--root", required=True, help="历史项目根目录（下面有 apps/）")
    p.add_argument("--apps-subdir", default="apps", help="应用所在子目录，默认 apps")
    p.add_argument("--base-url", default=os.environ.get("FCLOUD_BASE_URL", "http://127.0.0.1:8080"))
    p.add_argument("--cookie", help="Cookie 头，可只给 JWT 值")
    p.add_argument("--cookie-file", help="从文件读 cookie")
    p.add_argument("--apply", action="store_true", help="真正写入；不加则只打印计划")
    p.add_argument("--env-map", default="", help="环境目录名映射，如 rel=staging,pro=prod")
    p.add_argument("--module-map", default="",
                   help="阶段目录→模块 code，默认 build/docker=image；如 build/docker=image,scripts=tools")
    p.add_argument("--only-app", default="", help="只处理这些应用（逗号分隔）")
    p.add_argument("--only-env", default="", help="只处理这些环境目录（逗号分隔）")
    p.add_argument("--create-apps", action="store_true", help="应用不存在时自动创建")
    p.add_argument("--skip-existing", action="store_true",
                   help="同名 fileKey 已存在就跳过（否则每次跑都会生成新版本）")
    p.add_argument("--no-scripts", action="store_true", help="不合并 build/{backend,fronted} 下的 sh 脚本")
    p.add_argument("--invoke-module", default="image", help="invoke.py 上传到哪个模块，默认 image")
    p.add_argument("--invoke-env", default="",
                   help="invoke.py 只上传到这些环境，默认跟随脚本所在环境")
    p.add_argument("--timeout", type=int, default=60)
    p.add_argument("--use-env-proxy", action="store_true",
                   help="走 HTTP_PROXY/HTTPS_PROXY（默认直连，内网管理端不需要代理）")
    p.add_argument("--json-report", help="把计划与结果写到 JSON 文件")
    return p.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    env_map = parse_env_map(args.env_map)
    module_map = parse_module_map(args.module_map)
    root = Path(args.root).expanduser().resolve()

    legacy_apps = scan_root(root, args.apps_subdir, env_map, module_map)
    only_apps = {x.strip() for x in args.only_app if args.only_app}
    only_envs = {x.strip() for x in args.only_env if args.only_env}
    if only_apps:
        legacy_apps = [a for a in legacy_apps if a.code in only_apps or a.dir.name in only_apps]
    if only_envs:
        for a in legacy_apps:
            a.files = [f for f in a.files if f.env_dir in only_envs or f.env_code in only_envs]
            a.env_scripts = {e: s for e, s in a.env_scripts.items() if e in only_envs}

    total_files = sum(len(a.files) for a in legacy_apps)
    print(f"扫描 {root}: {len(legacy_apps)} 个应用 / {total_files} 个文件"
          + ("（dry-run，不写入）" if not args.apply else ""))

    client = HubClient(args.base_url, read_cookie(args), args.timeout, args.use_env_proxy)
    try:
        client.get("/api/console/auth/me")
    except HubError as e:
        print(f"认证失败：{e}", file=sys.stderr)
        return 2

    migrator = Migrator(client, args)
    try:
        migrator.preload()
    except HubError as e:
        print(f"读取系统数据失败：{e}", file=sys.stderr)
        return 2

    print(f"目标系统已有 {len(migrator.apps_by_code)} 个应用 / "
          f"{len(migrator.envs_by_code)} 个环境")
    migrator.build(legacy_apps)

    creates = sum(1 for i in migrator.plan if i.kind.startswith("create"))
    uploads = sum(1 for i in migrator.plan if i.kind.startswith("upload"))
    binds = sum(1 for i in migrator.plan if i.kind == "bind_env")
    print(f"\n计划：新建 {creates} · 补绑 {binds} · 上传 {uploads} · "
          f"跳过 {migrator.skipped} · 问题 {len(migrator.errors)}")

    if migrator.errors:
        print("\n需要你确认/处理：")
        for e in migrator.errors:
            print(f"  ! {e}")

    if not args.apply:
        print("\n（dry-run 结束。确认无误后加 --apply 执行）")
        report = {"apply": False, "plan": [i.__dict__ for i in migrator.plan],
                  "errors": migrator.errors}
    else:
        print("\n开始执行…")
        migrator.apply()
        print(f"\n完成：上传 {migrator.uploaded} 个文件，跳过 {migrator.skipped}，"
              f"失败 {len(migrator.errors)}")
        for e in migrator.errors:
            print(f"  ! {e}", file=sys.stderr)
        report = {"apply": True, "uploaded": migrator.uploaded,
                  "skipped": migrator.skipped, "errors": migrator.errors}

    if args.json_report:
        Path(args.json_report).write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"报告已写入 {args.json_report}")

    return 0 if not migrator.errors else 1


if __name__ == "__main__":
    sys.exit(main())
