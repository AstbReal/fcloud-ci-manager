# 部署 CI 教程：拉取模块文件并执行 invoke

适用场景：打包机 / CI runner 需要用 `invoke` 执行各模块自带的 `tasks.py`，而共享模块和私有模块可能包含同名文件。

核心结论先给：**不要循环调单文件下载接口。** `GET /api/v1/pipeline/files/{moduleId}/{fileId}` 的 `Content-Disposition` 只有文件名，`curl -O -J` 会把它平铺到当前目录，`db-config/tasks.py` 和 `common-notify/tasks.py` 必然互相覆盖。批量接口返回的 tar 里条目名是 `{moduleCode}/{fileKey}`，模块之间天然隔离，消费端不需要改任何东西。

配套脚本：`scripts/fcloud-deploy.sh`（克隆后先 `chmod +x`）。

---

## 0. 前置条件

| 项 | 要求 | 检查方式 |
|---|---|---|
| 流水线 Token | 管理员在控制台「令牌」页签发，前缀 `chub_` | 调一次 `/api/v1/pipeline/config` 应返回 200 |
| 应用 code | 模块归属的应用 | 控制台应用卡片 |
| 环境 code | `sit` / `uat` / `rel` / `master`（按你实际建的） | 控制台「环境」页 |
| 模块绑环境 | 模块**必须**已绑定目标环境，否则 400 | 模块详情页 → 环境列表 |
| 打包机 | `curl`、`tar`、`python3` + `invoke` | `inv --list` |

```bash
python3 -m pip install 'invoke>=2.2,<3'
```

建议把 invoke 固化进打包机镜像，别每次流水线现装。

---

## 1. 接口契约（只需要知道这些）

```
POST /api/v1/pipeline/artifacts
Authorization: Bearer chub_xxxxxxxx
Content-Type: application/json

{"application":"pay","environment":"prod","modules":["db-config","common-notify"]}
```

- `application` 必填，`environment` 必填，`modules` 必填（数组，按顺序遍历），`files` 可选（省略 = 该环境全部文件）
- 成功：`200` + `application/gzip`，tar 条目 `{moduleCode}/{fileKey}`
- 失败：JSON `{"error":"..."}`，状态码映射如下

| 状态 | 含义 | 处理 |
|---|---|---|
| 400 | `modules` 为空 / 模块未绑定该环境 / 参数缺失 | 补环境绑定 |
| 401 | Token 缺失、无效、过期、吊销 | 重签 Token |
| 403 | 模块属于别的应用，或共享模块未订阅 | 订阅模块 |
| 404 | `application` 不存在，或该环境下所有模块都没有文件 | 先传文件 |
| 429 | 每 Token 每分钟 60 次，超出 | 退避重试 |

tar 是流式的（nginx 对 `/api/` 已关 `proxy_buffering`），大包直接落盘即可。

---

## 2. 完整流程（6 步）

### 步骤 1 — 预检，别把 401/403 带到解包阶段

先用 `/config` 打一发，代价极小，能在下载前就把鉴权、应用、环境、模块绑定全部验证掉：

```bash
curl -sS -G \
  -H "Authorization: Bearer $FCLOUD_TOKEN" \
  --data-urlencode "application=$FCLOUD_APP" \
  --data-urlencode "environment=$FCLOUD_ENV" \
  --data-urlencode "modules=db-config" \
  "$BASE/api/v1/pipeline/config"
```

**为什么用 `/config` 而不是直接下载**：`/config` 的校验链和 `artifacts` 完全一致（同一个 `requireApplication` + `ensureBoundToEnv`），但响应是文本，出错时一眼能看出是 Token 问题还是绑定问题。`/config` 只校验第一个模块，所以第一个模块要选你最确定存在的那个。

### 步骤 2 — 下载 tar，先清空暂存目录

```bash
rm -rf "$STAGE" && mkdir -p "$STAGE"
curl -sS -X POST \
  -H "Authorization: Bearer $FCLOUD_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"application":"pay","environment":"prod","modules":["db-config","common-notify"]}' \
  "$BASE/api/v1/pipeline/artifacts" -o "$STAGE/artifacts.tar.gz"
```

**`rm -rf $STAGE` 不能省。** 上一次留下的旧文件混在新包里，`tasks.py` 会执行上一版逻辑，这种事故定位起来极慢。要并发跑多个流水线，就把 `STAGE` 设成 `$(mktemp -d)`。

### 步骤 3 — 校验包结构，再解包

```bash
tar -tzf "$STAGE/artifacts.tar.gz" > "$STAGE/.members"
[ -s "$STAGE/.members" ] || { echo "空包"; exit 1; }
grep -qE '(^|/)\.\.(/|$)' "$STAGE/.members" && { echo "含路径穿越条目"; exit 1; }
grep -q '^db-config/' "$STAGE/.members" || echo "警告：db-config 没有文件"
```

两个目的：确认每个模块真的拿到了文件（空模块不会报错，只会静默缺失）；拒绝含 `..` 的条目。后端目前对 `fileKey` 只做了非空校验，没拦 `..`，这里当第二道闸。

### 步骤 4 — 解包

```bash
tar -xzf "$STAGE/artifacts.tar.gz" -C "$STAGE" --no-same-owner
find "$STAGE" -type f -name '*.sh' -exec chmod +x {} +
```

**绝对不要加 `--strip-components=1`。** 加了就把唯一的模块隔离层削掉，退化成你现在的覆盖问题。

`chmod +x` 那行是必须的：上传时走 `Files.copy`，落盘是 `0644`，tar 条目继承这个权限，解包出来的 `.sh` 没有执行位。如果你的 `tasks.py` 里 `./build.sh`，不加这行直接 permission denied。

### 步骤 5 — 逐模块执行，每个模块独立 cwd

```bash
for m in db-config common-notify; do
  ( cd "$STAGE/$m" && inv --list >/dev/null && inv deploy ) || exit 1
done
```

**为什么必须 `cd`**：invoke 的 collection 发现从当前目录向上找 `tasks.py`，同层有多个时只认最近的那个。`cd` 进去才是确定的。

`inv --list` 先跑一次是廉价的门禁：`tasks.py` 有语法错误或 import 失败时，它会以非零退出并打出 traceback，比直接跑任务时报半截错清晰得多。

失败策略建议 fail-fast（脚本默认）：打包机停在半成品状态比整体失败更难排查。

### 步骤 6 — 留痕

```bash
( cd "$STAGE" && find . -type f ! -name 'artifacts.tar.gz' ! -name '.members' -print0 \
    | sort -z | xargs -0 sha256sum ) > "$STAGE/.deployed.sha256"
```

**目前的硬限制**：tar 里没有 manifest，拿不到每个文件的版本号。想知道"这次部署的是 db-config v2 还是 v3"，现在只能靠时间戳和 sha256 比对。留一份指纹至少能回答"这台机器的文件是不是我要的那份"。

```bash
./scripts/fcloud-deploy.sh
```

完整脚本把上面 6 步都串好了，失败时保留暂存目录直接可查。

---

## 3. 接入 CI

### GitHub Actions（self-hosted runner 在打包机上）

```yaml
- name: Deploy config modules
  env:
    FCLOUD_BASE: ${{ vars.FCLOUD_BASE }}
    FCLOUD_TOKEN: ${{ secrets.FCLOUD_TOKEN }}
    FCLOUD_APP: pay
    FCLOUD_ENV: ${{ inputs.environment }}   # sit / uat / rel / master
    FCLOUD_MODULES: db-config,common-notify
    TASK: deploy
  run: ./scripts/fcloud-deploy.sh
```

### GitLab CI

```yaml
deploy-config:
  stage: deploy
  variables:
    FCLOUD_APP: pay
    FCLOUD_ENV: $CI_ENVIRONMENT_NAME
    FCLOUD_MODULES: "db-config,common-notify"
    TASK: deploy
  script:
    - ./scripts/fcloud-deploy.sh
  environment:
    name: $ENV_NAME
```

`FCLOUD_TOKEN` 和 `FCLOUD_BASE` 放在 CI/CD Variables 里，Token 勾 Masked。

### Jenkins

```groovy
withCredentials([string(credentialsId: 'fcloud-token', variable: 'FCLOUD_TOKEN')]) {
  withEnv(["FCLOUD_BASE=${FCLOUD_BASE}", "FCLOUD_APP=pay", "FCLOUD_ENV=${params.ENV}",
           "FCLOUD_MODULES=db-config,common-notify", "TASK=deploy"]) {
    sh './scripts/fcloud-deploy.sh'
  }
}
```

---

## 4. 可选：同时拉变量

文件里的配置通常伴随变量，同一 Token 一次请求就能拿 dotenv：

```bash
curl -sS -G -H "Authorization: Bearer $FCLOUD_TOKEN" \
  --data-urlencode "application=$FCLOUD_APP" \
  --data-urlencode "environment=$FCLOUD_ENV" \
  --data-urlencode "modules=db-config,common-notify" \
  "$BASE/api/v1/pipeline/config" -o "$STAGE/.env"

set -a; . "$STAGE/.env"; set +a
```

dotenv 输出对含空格、`#`、`$`、单引号的值会加单引号并做 POSIX 转义，`set -a; . file` 能直接安全加载。加密变量在这个接口返回的是**解密后的明文**（§4.2），所以这个文件等同于密钥，用完即删，不要留在工作区和流水线日志里。

---

## 5. 排错

| 现象 | 原因 | 处置 |
|---|---|---|
| tar 里只有一个模块 | `modules` 只传了一个 | 检查循环拼参数 |
| 解包后文件全平铺 | 用了 `--strip-components=1` | 去掉 |
| `inv: command not found` | 打包机没装 invoke | `pip install 'invoke>=2.2,<3'` |
| `Can't find any collection with a tasks.py` | 没 `cd` 进模块目录 | `cd "$STAGE/$m"` |
| `Permission denied: ./build.sh` | tar 权限 0644 | 解包后 `chmod +x` |
| 执行的是上一版逻辑 | 没清暂存目录 | 先 `rm -rf $STAGE` |
| 429 | 同一 Token 高频调用 | 复用一次下载，别 per-module 下载 |

---

## 6. 已知限制

1. **无版本回执**。tar 不带 manifest，无法确认拿到的版本号。建议后续在包里加 `.fcloud/manifest.json`（module / environment / version / fileKey / sha256），打包机就能把版本写进部署记录，也能做完整性校验。
2. **缺少内容 hash**。`fch_file_version` 没有 `sha256` 列，服务端无法判断"这个环境的这份文件已经是同一内容"，所以同步/去重类功能现在做不了。
3. **单模块内的子目录会丢**。`fileKey` 里的 `/` 目前不被支持（`FileService` 只建到 `v{n}` 目录，`Files.copy` 会因父目录不存在失败），`frontend` 上传时也只取 `file.name`。如果你需要 `migrations/001.sql` 这种结构，那是另一个改动，与本文无关。
