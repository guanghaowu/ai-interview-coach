<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand" @click="router.push('/sessions')">
        <el-icon :size="20" color="#409eff"><Cpu /></el-icon>
        <span class="brand-name">AiInterviewCoach</span>
        <el-tag size="small" type="success" effect="plain">AgentLoop</el-tag>
      </div>

      <el-menu
        class="nav"
        mode="horizontal"
        :default-active="activeNav"
        :ellipsis="false"
        @select="onSelect"
      >
        <el-menu-item index="/sessions">
          <el-icon><List /></el-icon>
          <span>我的会话</span>
        </el-menu-item>
        <el-menu-item index="/create">
          <el-icon><Plus /></el-icon>
          <span>新建面试</span>
        </el-menu-item>
      </el-menu>

      <el-dropdown @command="onCommand">
        <span class="user">
          <el-avatar :size="28" :src="auth.avatar">
            {{ auth.displayName.charAt(0) }}
          </el-avatar>
          <span class="user-name">{{ auth.displayName }}</span>
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">
              <el-icon><User /></el-icon> 个人中心
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon> 退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>

    <el-main class="main">
      <router-view v-slot="{ Component }">
        <component :is="Component" />
      </router-view>
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeNav = computed(() => {
  if (route.path.startsWith('/sessions/')) return '/sessions'
  return route.path
})

function onSelect(path) {
  router.push(path)
}

function onCommand(cmd) {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'logout') {
    auth.clear()
    ElMessage.success('已退出登录')
    router.replace('/login')
  }
}

// 刷新页面后补全用户资料（token 在 localStorage，但昵称/头像需要重新拉）
onMounted(() => {
  if (auth.isLogin && !auth.userId) {
    auth.fetchProfile().catch(() => {})
  }
})
</script>

<style scoped>
.layout {
  min-height: 100vh;
}

.header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  padding: 0 24px;
  height: 60px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  flex-shrink: 0;
}

.brand-name {
  font-size: 16px;
  font-weight: 600;
}

.nav {
  flex: 1;
  border-bottom: none;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-size: 14px;
  color: #303133;
}

.main {
  padding: 0;
  background: var(--page-bg);
}
</style>
