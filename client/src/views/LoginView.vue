<template>
  <div class="login-wrap">
    <div class="login-card">
      <div class="login-head">
        <el-icon :size="30" color="#409eff"><Cpu /></el-icon>
        <h1>AiInterviewCoach</h1>
        <p>粘贴一段 JD，AI 以 Planner / Executor / Critic 三角色为你出一套面试题</p>
      </div>

      <el-tabs v-model="tab" stretch>
        <!-- ============ 登录 ============ -->
        <el-tab-pane label="登录" name="login">
          <el-form
            ref="loginRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            @keyup.enter="doLogin"
          >
            <el-form-item prop="username">
              <el-input
                v-model="loginForm.username"
                placeholder="用户名"
                :prefix-icon="User"
                size="large"
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                show-password
                placeholder="密码"
                :prefix-icon="Lock"
                size="large"
              />
            </el-form-item>
            <el-button
              type="primary"
              size="large"
              class="submit"
              :loading="loading"
              @click="doLogin"
            >
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ============ 注册 ============ -->
        <el-tab-pane label="注册" name="register">
          <el-form
            ref="regRef"
            :model="regForm"
            :rules="regRules"
            label-position="top"
            @keyup.enter="doRegister"
          >
            <el-form-item prop="username">
              <el-input
                v-model="regForm.username"
                placeholder="用户名（3-20 位）"
                :prefix-icon="User"
                size="large"
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="regForm.password"
                type="password"
                show-password
                placeholder="密码（6-20 位）"
                :prefix-icon="Lock"
                size="large"
              />
            </el-form-item>
            <el-form-item prop="confirm">
              <el-input
                v-model="regForm.confirm"
                type="password"
                show-password
                placeholder="确认密码"
                :prefix-icon="Lock"
                size="large"
              />
            </el-form-item>
            <el-button
              type="primary"
              size="large"
              class="submit"
              :loading="loading"
              @click="doRegister"
            >
              注册并登录
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { userApi } from '../api'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const tab = ref('login')
const loading = ref(false)

const loginRef = ref()
const regRef = ref()

const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', password: '', confirm: '' })

const loginRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const regRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度 3-20', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度 6-20', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_r, value, cb) =>
        value === regForm.password ? cb() : cb(new Error('两次输入的密码不一致')),
      trigger: 'blur'
    }
  ]
}

function afterLogin(vo) {
  auth.setLogin(vo)
  ElMessage.success(`欢迎，${vo.nickname || vo.username}`)
  const redirect = route.query.redirect
  router.replace(typeof redirect === 'string' && redirect ? redirect : '/sessions')
}

async function doLogin() {
  // validate() 校验不过会 reject，必须自己吞掉，否则点击按钮会抛出未捕获异常
  try {
    await loginRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const vo = await userApi.login({ ...loginForm })
    afterLogin(vo)
  } catch (e) {
    // 错误提示已在 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}

async function doRegister() {
  try {
    await regRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const vo = await userApi.register({
      username: regForm.username,
      password: regForm.password
    })
    afterLogin(vo)
  } catch (e) {
    // 忽略：拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef3ff 0%, #f5f7fa 100%);
  padding: 24px;
}

.login-card {
  width: 400px;
  max-width: 100%;
  background: #fff;
  border-radius: 12px;
  padding: 32px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
}

.login-head {
  text-align: center;
  margin-bottom: 16px;
}

.login-head h1 {
  font-size: 20px;
  margin: 8px 0 6px;
}

.login-head p {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin: 0;
}

.submit {
  width: 100%;
  margin-top: 4px;
}
</style>
