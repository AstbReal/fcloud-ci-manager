# 历史项目迁移工具

把老项目的「应用目录 / 环境目录 / 文件」搬进 fcloud-ci-hub：按应用匹配、自动建模块、按环境上传文件，并把构建脚本合并成 `invoke.py`。

零第三方依赖，Python 3.8+，Linux / Windows 都能跑。

## 它认的目录结构

```
<root>/apps/
  ama-biz-cms/
    init.py                          # XcApp 子类，读 set_gitlab_id / set_scc_master_name / set_scc_name
    ama-xc/
      pro/
        scc/deployment/deployment.yaml
        build/docker/Dockerfile      # Dockerfile / start.template
        build/docker/start.template
      rel/
        scc/deployment/deployment.yaml
        build/docker/
      uat/
        scc/deployment/deployment.yaml
        build/backend/*.sh           # 每个环境各自一份构建脚本
        build/fronted/*.sh           # 历史项目拼写就是 fronted
```

映射规则：

| 老项目 | 新系统 |
| --- | --- |
| `apps/ama-biz-cms` | 应用 `ama-biz-cms`（按 init.py 的 gitlab id → code → scc_name 依次匹配） |
| `ama-xc/pro` | 环境 code `pro`（可用 `--env-map rel=staging` 改） |
| `ama-xc/pro/scc/deployment/deployment.yaml` | 模块 `scc` · fileKey `deployment.yaml`（默认只留文件名） |
| `ama-xc/pro/build/docker/Dockerfile` | 模块 `image` · fileKey `Dockerfile`（`build/docker` → `image`） |
| `ama-xc/pro/build/docker/start.template` | 模块 `image` · fileKey `start.template` |
| `ama-xc/pro/build/{backend,fronted}/*.sh` | 该环境的脚本合并成 `invoke.py`，上传到 `--invoke-module`（默认 `image`） |
| 其它 `<env>/<目录>/**` | 兜底：`<目录>` 当模块 code，后续路径当 fileKey |

> 应用默认**不自动创建**（避免拼错 code 污染看板），需要时加 `--create-apps`。
> 模块一律自动创建；已有模块缺环境绑定时会自动补绑，不会动其它绑定。
> `build/{backend,fronted}` 目录只认 `.sh`，其它文件会被忽略并告警。

## 用法

### 1. 拿 cookie

登录管理端 → DevTools → Application → Cookies → 复制 `fcloud_token` 的值。
（HttpOnly，`document.cookie` 拿不到。）默认 24 小时有效，勾了「记住我」是 7 天。

### 2. 先看计划（默认 dry-run）

```bash
python3 migrate.py \
  --root /data/legacy \
  --base-url http://10.0.0.5:8080 \
  --cookie "fcloud_token=eyJhbGciOi..."
```

输出示例：

```
扫描 /data/legacy: 3 个应用 / 42 个文件（dry-run，不写入）
目标系统已有 12 个应用 / 4 个环境

== 应用 ama-biz-cms ← /data/legacy/apps/ama-biz-cms（gitlab #37578）
   命中应用 id=2112154700710174722 code=ama-biz-cms
   [+] 计划创建应用（模块 id 在执行时解析）
   [skip] 空文件 ama-xc/pro/scc/deployment/deployment.yaml
计划：新建 3 · 补绑 1 · 上传 38 · 跳过 4 · 问题 0

（dry-run 结束。确认无误后加 --apply 执行）
```

### 3. 真跑

```bash
python3 migrate.py --root /data/legacy --base-url http://10.0.0.5:8080 \
  --cookie-file ~/.fcloud-cookie --apply --skip-existing
```

- `--skip-existing`：同名 fileKey 已存在就跳过。**不加**时每次跑都会生成新版本（v2、v3…）。
- 失败项不会中断整体，最后汇总打印，退出码非 0。
- `--json-report report.json` 可以留一份迁移记录。

## 参数

| 参数 | 说明 |
| --- | --- |
| `--root` | 历史项目根目录（下面有 `apps/`） |
| `--apps-subdir` | 应用目录名，默认 `apps`；如果 `--root` 本身就是应用集合，传空串 |
| `--base-url` | 管理端地址，默认 `http://127.0.0.1:8080` |
| `--cookie` / `--cookie-file` | 会话 cookie，也支持环境变量 `FCLOUD_COOKIE` |
| `--apply` | 不加就是 dry-run |
| `--env-map` | 目录名 → 环境 code，如 `rel=staging,pro=prod`；默认同名 |
| `--module-map` | 阶段目录 → 模块 code，默认 `build/docker=image`；如 `build/docker=image,build/scripts=tools` |
| `--only-app` / `--only-env` | 分批迁移时用，逗号分隔 |
| `--create-apps` | 应用不存在时自动创建 |
| `--skip-existing` | 已存在的 fileKey 跳过 |
| `--no-scripts` | 不合并 sh → invoke.py |
| `--invoke-module` | invoke.py 上传到哪个模块，默认 `image` |
| `--invoke-env` | invoke.py 只上传到这些环境，默认跟随脚本所在环境（每个环境各生成一份） |
| `--use-env-proxy` | 走 `HTTP_PROXY`；默认直连（内网管理端不需要代理） |
| `--json-report` | 计划/结果写 JSON |

## invoke.py 长什么样

把每个 `.sh` 内联成字符串，按原相对路径分发：

每个环境各生成一份，`SCRIPTS` 的 key 是相对 `build/` 的路径（多个脚本取共同父目录）：

```python
SCRIPTS = {
    "backend/install.sh": r'''...原始脚本内容...''',
    "fronted/build.sh":   r'''...''',
}

# python3 invoke.py                 # 依次全跑
# python3 invoke.py backend/install.sh
# python3 invoke.py --list
```

每个脚本用 `bash -euo pipefail -c` 执行，失败即停并返回原退出码。

如果你们的 `invoke.py` 有自己的约定（比如按 `start/stop/restart` 动作分发），
改 `build_invoke_py()` 里的模板即可，其余流程不用动。

## 前置条件与坑

1. fileKey 只能是文件名：后端 `FileService.requireFlatFileKey()` 会拒绝带 `/`、`\` 的 key。
   同一模块同一环境出现同名文件时，脚本只保留先到的那个并报错，其余需要先改名。
2. 目标环境必须已存在。缺环境时脚本会列出「老目录 → 缺 code」，用 `--env-map` 映射，
   或先在管理端建环境（环境是全局配置，脚本不自动建）。
3. `401`：cookie 过期或没带对；`403`：账号不是系统管理员。
4. 连不上 / 502：先确认 `--base-url` 是后端直连地址（不是前端 5173），
   如果机器上配了公司代理，别加 `--use-env-proxy`。
5. 空文件会被跳过（后端拒绝 0 字节上传），并在计划里列出来；全是空文件的模块不会被创建。
6. 两个环境目录映射到同一个环境 code 时，同名文件只取第一个，并在计划里打 `环境映射冲突` 告警。

## fileKey 与压缩包结构

fileKey 是「模块 × 环境」内的文件标识，**只能是文件名**（不允许带目录）。后端存储路径是
`{root}/{应用}/{模块}/{环境}/v{n}/{fileKey}`，父目录会自动创建。

流水线 `POST /api/v1/pipeline/artifacts` 打 tar.gz 时，条目名是
**`{模块code}/{fileKey}`**，所以：

| fileKey | 压缩包内路径 |
| --- | --- |
| `deployment.yaml` | `scc/deployment.yaml` |

`tar -xzf` 会按条目里的 `/` 自动建目录。两点注意：

- `artifacts` 请求里的 `files` 过滤是**整串精确匹配**：带子目录的 key 要写
  `deployment/config.yaml`，写 `config.yaml` 匹配不到。
- 单文件下载接口用的是 `originalName`（文件名，不含路径），浏览器落盘不会带斜杠。

## 迁移后自查

1. 应用看板：应用下出现 `scc` / `image` 模块，chip 上的状态点正常。
2. 模块详情：切到对应环境，文件列表里有迁移过来的文件，点开能预览。
3. 流水线接入：「批量下载文件」能拉到这些文件；`invoke.py` 在 `image` 模块下。

## 本地已跑通的场景

- dry-run 计划输出、`--create-apps` 建应用、模块自动创建并绑定环境；
- `build/docker` → `image` 模块（Dockerfile / start.template）；`scc` 模块的 fileKey 就是 `deployment.yaml`；
- fileKey 上传 + 下载内容一致；同名文件（不同子目录）只保留先到者并报错；
- 每个环境各生成一份 `invoke.py`（`uat/build/{backend,fronted}` → 一份，其它环境无脚本则不发）；
- 生成的 `invoke.py` 通过 `python3 -m py_compile`，`--list` 输出 `backend/install.sh`、`fronted/build.sh`；
- 已有模块缺环境时自动补绑；全空文件的模块不创建；
- `--skip-existing` 二次运行 0 上传、7 跳过（含 invoke.py）；
- 环境映射冲突（`pro` 和 `rel` 都映射到 `rel`）时只保留先到的，并打告警；
- 空文件跳过、环境找不到时报错并给出 `--env-map` 提示。


