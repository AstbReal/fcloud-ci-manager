<template>
  <div class="login-wrap">
    <div>
      <div class="login-card">
        <div class="login-logo">
          <el-icon :size="22"><Cloudy /></el-icon>
        </div>
        <h2>登录 fcloud-ci-hub</h2>
        <p>使用公司 GitLab 账号登录<br />配置分发中心</p>

        <div v-if="authError" class="login-error" role="alert">
          <svg class="err-ic" viewBox="0 0 16 16" aria-hidden="true">
            <circle cx="8" cy="8" r="6.6" fill="none" stroke="currentColor" stroke-width="1.4" />
            <path d="M8 4.6v4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
            <circle cx="8" cy="11.2" r="0.95" fill="currentColor" />
          </svg>
          <span>{{ authError }}</span>
        </div>

        <button class="gitlab-btn" :disabled="loading" @click="login">
          <svg viewBox="0 0 24 24" class="gl-ic">
            <path
              d="M23.955 13.587l-1.342-4.135-2.652-8.162c-.13-.4-.708-.4-.838 0l-2.652 8.162H8.53L5.878 1.29c-.13-.4-.708-.4-.838 0L2.388 9.452.046 13.587c-.108.332.01.7.288.905l11.66 8.474 11.66-8.474c.278-.205.396-.573.288-.905z"
            />
          </svg>
          使用 GitLab 登录
        </button>

        <label class="remember">
          <input v-model="remember" type="checkbox" />
          <span class="checkbox" :class="{ on: remember }">
            <svg viewBox="0 0 12 12" class="ck">
              <path d="M2 6.2 4.6 8.8 10 3.4" />
            </svg>
          </span>
          记住我（7 天）
        </label>

        <div class="login-foot">
          首次登录自动创建账号 · 空库首位登录者将成为系统管理员
        </div>
      </div>
      <div class="copyright">© 2026 fcloud · 配置分发中心 v1.0</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Cloudy } from '@element-plus/icons-vue'

const remember = ref(false)
const loading = ref(false)
const route = useRoute()

/** Spring Security 授权失败会带 ?error 回跳（如 error=access_denied），此前只显示空白登录页 */
const authError = computed(() => {
  if (!('error' in route.query)) return ''
  const code = String(route.query.error ?? '')
  if (code === 'access_denied') return '你取消了授权，登录未完成'
  if (code === 'invalid_state' || code === 'invalid_request') return '授权会话已失效，请重新登录'
  return 'GitLab 授权失败，请重试'
})

/**
 * §6.2 "记住我"：通过 /oauth2/authorization/gitlab?remember=1 传参，
 * SuccessHandler 读取 remember 请求参数决定 TTL。
 */
function login() {
  loading.value = true
  const url = remember.value
    ? '/oauth2/authorization/gitlab?remember=1'
    : '/oauth2/authorization/gitlab'
  window.location.href = url
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: radial-gradient(900px 500px at 50% -10%, #e8eeff 0%, transparent 60%),
    var(--bg);
}

.login-card {
  width: 392px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: 0 20px 50px -20px rgba(16, 24, 40, 0.2);
  padding: 32px 32px 26px;
  text-align: center;
}

.login-logo {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: linear-gradient(140deg, #3b82f6, #1d4ed8);
  display: grid;
  place-items: center;
  color: #fff;
  margin: 0 auto 14px;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.32);
}

.login-card h2 {
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--text);
}

.login-card p {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 6px;
  line-height: 1.6;
}

.login-error {
  margin-top: 14px;
  padding: 9px 12px;
  border: 1px solid #fbd5d5;
  border-radius: var(--r-sm);
  background: var(--danger-50);
  color: var(--danger);
  font-size: 12.5px;
  line-height: 1.5;
  text-align: left;
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.err-ic {
  flex: 0 0 auto;
  width: 15px;
  height: 15px;
  margin-top: 2px;
}

.gitlab-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 40px;
  border: 0;
  border-radius: var(--r-sm);
  background: #fc6d26;
  color: #fff;
  font-size: 13.5px;
  font-weight: 600;
  font-family: inherit;
  margin-top: 22px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(252, 109, 38, 0.3);
  transition: background 0.15s;
}

.gitlab-btn:hover {
  background: #e85f1c;
}

.gitlab-btn:disabled {
  opacity: 0.7;
  cursor: default;
}

.gl-ic {
  width: 16px;
  height: 16px;
  fill: currentColor;
}

.remember {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 10px;
  padding: 5px 8px;
  font-size: 12.5px;
  color: var(--text-2);
  cursor: pointer;
  user-select: none;
}

.remember input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.remember input:focus-visible + .checkbox {
  outline: 2px solid rgba(37, 99, 235, 0.45);
  outline-offset: 1px;
}

.checkbox {
  width: 15px;
  height: 15px;
  border: 1px solid var(--border-2);
  border-radius: 4px;
  background: #fff;
  display: grid;
  place-items: center;
  transition: 0.14s;
}

.checkbox.on {
  background: var(--primary);
  border-color: var(--primary);
}

.ck {
  width: 10px;
  height: 10px;
  fill: none;
  stroke: #fff;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  opacity: 0;
}

.checkbox.on .ck {
  opacity: 1;
}

.login-foot {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.6;
}

.copyright {
  text-align: center;
  margin-top: 18px;
  font-size: 11.5px;
  color: var(--text-3);
}
</style>
