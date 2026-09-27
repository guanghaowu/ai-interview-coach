<template>
  <div class="page narrow">
    <h2 class="page-title">个人中心</h2>
    <p class="page-sub">查看账号信息，修改昵称与头像</p>

    <el-skeleton v-if="loading" :rows="4" animated />

    <template v-else-if="info">
      <el-card shadow="never" class="card">
        <div class="profile-top">
          <el-avatar :size="64" :src="info.avatar">
            {{ (info.nickname || info.username || 'U').charAt(0) }}
          </el-avatar>
          <div class="profile-meta">
            <p class="name">{{ info.nickname || info.username }}</p>
            <p class="muted">@{{ info.username }}</p>
          </div>
          <el-tag type="warning" effect="plain" class="quota">
            今日剩余 AI 配额 {{ info.dailyQuota }}
          </el-tag>
        </div>

        <el-descriptions :column="2" border class="desc">
          <el-descriptions-item label="用户 ID">{{ info.id }}</el-descriptions-item>
          <el-descriptions-item label="用户名">{{ info.username }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ info.nickname || '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ formatTime(info.createdAt) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="card">
        <template #header>
          <span class="card-title">修改资料</span>
        </template>

        <el-form :model="form" label-width="80px" @submit.prevent>
          <el-form-item label="昵称">
            <el-input
              v-model="form.nickname"
              maxlength="50"
              show-word-limit
              placeholder="给自己起个昵称"
            />
          </el-form-item>
          <el-form-item label="头像地址">
            <el-input
              v-model="form.avatar"
              maxlength="255"
              placeholder="填写一个图片 URL，例如 https://example.com/a.png"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
            <el-button @click="reset">重置</el-button>
          </el-form-item>
        </el-form>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="用户名是登录凭据、每日配额由服务端控制，均不支持自助修改。"
        />
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { formatTime } from '../utils/format'

const auth = useAuthStore()

const loading = ref(true)
const saving = ref(false)
const info = ref(null)
const form = reactive({ nickname: '', avatar: '' })

function fill(vo) {
  info.value = vo
  form.nickname = vo.nickname || ''
  form.avatar = vo.avatar || ''
}

async function load() {
  loading.value = true
  try {
    fill(await userApi.info())
  } catch (e) {
    info.value = null
  } finally {
    loading.value = false
  }
}

function reset() {
  form.nickname = info.value?.nickname || ''
  form.avatar = info.value?.avatar || ''
}

async function save() {
  saving.value = true
  try {
    const vo = await userApi.updateInfo({
      nickname: form.nickname.trim(),
      avatar: form.avatar.trim()
    })
    fill(vo)
    // 同步到全局 store，导航栏昵称立即更新
    auth.nickname = vo.nickname
    auth.avatar = vo.avatar
    ElMessage.success('资料已更新')
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.narrow {
  max-width: 720px;
}

.card {
  border-radius: 12px;
  margin-bottom: 16px;
}

.card-title {
  font-weight: 600;
}

.profile-top {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.profile-meta {
  flex: 1;
}

.name {
  font-size: 17px;
  font-weight: 600;
  margin: 0;
}

.profile-meta p {
  margin: 2px 0 0;
}

.quota {
  flex-shrink: 0;
}
</style>
