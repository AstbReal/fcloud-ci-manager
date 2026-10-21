<template>
  <div class="fc-page">
    <div class="fc-page-header">
      <div>
        <h2 class="fc-page-title">个人设置</h2>
        <div class="fc-page-subtitle">昵称可编辑；其余信息来自 GitLab</div>
      </div>
    </div>

    <el-card class="fc-card profile-card" shadow="never">
      <div class="profile-head">
        <el-avatar :size="56" :src="user?.avatarUrl">
          {{ (user?.nickname || user?.name || '?')[0] }}
        </el-avatar>
        <div>
          <div class="profile-name">{{ user?.nickname || user?.name }}</div>
          <div class="fc-muted">{{ user?.email }}</div>
          <el-tag
            size="small"
            :type="user?.globalRole === 'SYSTEM_ADMIN' ? 'danger' : 'info'"
            effect="plain"
            style="margin-top: 6px"
          >
            {{ user?.globalRole === 'SYSTEM_ADMIN' ? '系统管理员' : '普通用户' }}
          </el-tag>
        </div>
      </div>

      <el-divider />

      <el-form label-position="top" style="max-width: 420px">
        <el-form-item label="昵称">
          <el-input v-model="nickname" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="姓名（来自 GitLab，不可编辑）">
          <el-input :model-value="user?.name" disabled />
        </el-form-item>
        <el-form-item label="邮箱（唯一标识，来自 GitLab）">
          <el-input :model-value="user?.email" disabled />
        </el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存昵称</el-button>
        <el-button type="danger" plain @click="logout">退出登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

const store = useUserStore()
const router = useRouter()
const nickname = ref('')
const saving = ref(false)

const user = computed(() => store.user)

onMounted(async () => {
  await store.loadMe()
  nickname.value = user.value?.nickname || ''
})

async function save() {
  if (!nickname.value.trim()) {
    ElMessage.warning('昵称不能为空')
    return
  }
  saving.value = true
  try {
    await store.updateNickname(nickname.value.trim())
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

async function logout() {
  await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  await store.logout()
  router.push('/login')
}
</script>

<style scoped>
.profile-card {
  max-width: 720px;
  padding: 8px;
}

.profile-head {
  display: flex;
  align-items: center;
  gap: 16px;
}

.profile-name {
  font-size: 16px;
  font-weight: 600;
}
</style>
