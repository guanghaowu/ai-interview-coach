import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('../layouts/AppLayout.vue'),
    redirect: '/sessions',
    children: [
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('../views/SessionListView.vue'),
        meta: { title: '我的会话' }
      },
      {
        path: 'create',
        name: 'create',
        component: () => import('../views/CreateView.vue'),
        meta: { title: '新建面试' }
      },
      {
        path: 'sessions/:id',
        name: 'interview',
        component: () => import('../views/InterviewView.vue'),
        meta: { title: '模拟面试' }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('../views/ProfileView.vue'),
        meta: { title: '个人中心' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/sessions' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    // 已登录还想去登录页 → 直接送回会话列表
    if (auth.isLogin && to.name === 'login') return { name: 'sessions' }
    return true
  }
  if (!auth.isLogin) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
