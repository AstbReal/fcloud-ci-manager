# ============================================================================
# GitLab Lite —— 测试用 OAuth2 提供方
#
# 用途：在无法拉取真实 gitlab/gitlab-ce 镜像（约 2.5GB）的环境里，
#       端到端验证 fcloud-ci-manager 的 GitLab OAuth 登录链路。
#
# 它实现了真实 GitLab 的以下契约，Spring Security OAuth2 Client 无需改动：
#   GET  /oauth/authorize    授权页
#   POST /oauth/authorize    同意授权 → 302 带 code
#   POST /oauth/token        授权码换 access_token（支持 Basic / form 认证）
#   GET  /api/v4/user        用户信息（id/username/name/email/avatar_url）
#
# 基础镜像复用本机已有的 postgres:16-alpine（Alpine + python3），
# 避免再次依赖 Docker Hub。
# ============================================================================
FROM postgres:16-alpine

RUN apk add --no-cache python3 \
    && python3 --version

WORKDIR /app
COPY app.py /app/app.py

ENV GITLAB_PORT=8929 \
    GITLAB_HOST=0.0.0.0 \
    GITLAB_CLIENT_ID=test-client-id \
    GITLAB_CLIENT_SECRET=test-client-secret \
    GITLAB_BASE_URL=http://127.0.0.1:8929 \
    PYTHONUNBUFFERED=1

EXPOSE 8929

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
    CMD python3 -c "import urllib.request,sys; \
sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8929/-/health',timeout=3).status==200 else 1)"

CMD ["python3", "/app/app.py"]
