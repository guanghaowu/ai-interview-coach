import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useAuthStore } from '../stores/auth'

// 后端的错误模型（决定了拦截器怎么写）：
//   1. 业务异常（BusinessException）→ HTTP 200 + body { code: 非0 }（404/403/409/429/401-密码错）
//   2. JWT 拦截器失败 → HTTP 401 + body { code: 401 }
// 所以「跳登录」只能由 HTTP 401 触发，绝不能由 body.code===401 触发
// （否则用户在登录页输错密码会被直接踢回登录页，陷入死循环）。

let redirecting = false

function forceLogin(msg) {
  const auth = useAuthStore()
  auth.clear()
  if (redirecting || router.currentRoute.value.path === '/login') return
  redirecting = true
  ElMessage.error(msg || '登录已过期，请重新登录')
  router.replace('/login').finally(() => {
    redirecting = false
  })
}

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 统一包装体：{ code, message, data }
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      if (body.code === 429) {
        ElMessage.warning(body.message || '请求过于频繁，请稍后再试')
      } else {
        ElMessage.error(body.message || '请求失败')
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    // 非包装体（理论上不该出现）原样返回
    return body
  },
  (error) => {
    const status = error.response?.status
    const msg = error.response?.data?.message
    if (status === 401) {
      forceLogin(msg)
    } else if (status === 429) {
      ElMessage.warning(msg || '请求过于频繁，请稍后再试')
    } else if (status >= 500) {
      ElMessage.error(msg || '服务端异常，请稍后再试')
    } else if (msg) {
      ElMessage.error(msg)
    } else {
      ElMessage.error('网络异常，请确认后端服务已启动')
    }
    return Promise.reject(error)
  }
)

export default http
