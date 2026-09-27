import { defineStore } from 'pinia'
import { userApi } from '../api'

const TOKEN_KEY = 'aicoach_token'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userId: null,
    username: '',
    nickname: '',
    avatar: ''
  }),

  getters: {
    isLogin: (s) => !!s.token,
    displayName: (s) => s.nickname || s.username || '未登录'
  },

  actions: {
    /** 登录/注册成功后写入 */
    setLogin(loginVO) {
      this.token = loginVO.token
      this.userId = loginVO.userId
      this.username = loginVO.username
      this.nickname = loginVO.nickname
      localStorage.setItem(TOKEN_KEY, loginVO.token)
    },

    /** 拉取最新用户资料（刷新页面后补全 userId/nickname） */
    async fetchProfile() {
      const info = await userApi.info()
      this.userId = info.id
      this.username = info.username
      this.nickname = info.nickname
      this.avatar = info.avatar
      return info
    },

    clear() {
      this.token = ''
      this.userId = null
      this.username = ''
      this.nickname = ''
      this.avatar = ''
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
