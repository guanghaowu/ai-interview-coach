<template>
  <div class="page narrow">
    <h2 class="page-title">新建模拟面试</h2>
    <p class="page-sub">
      粘贴目标岗位的 JD 原文，AI 会先用 Planner 拆解考察维度，再由 Executor 出题，最后由 Critic 校验
    </p>

    <el-card shadow="never" class="form-card">
      <el-form label-position="top">
        <el-form-item>
          <template #label>
            <div class="label-row">
              <span>JD 原文</span>
              <el-button link type="primary" size="small" @click="fillSample">
                填入示例 JD
              </el-button>
            </div>
          </template>
          <el-input
            v-model="jdContent"
            type="textarea"
            :rows="10"
            maxlength="5000"
            show-word-limit
            resize="vertical"
            placeholder="例如：招聘 Java 后端实习生，要求熟悉 SpringBoot、MySQL、Redis，了解 RocketMQ，有高并发项目经验优先……"
            :disabled="running"
          />
        </el-form-item>
      </el-form>

      <div class="actions">
        <el-button
          type="primary"
          size="large"
          :icon="MagicStick"
          :loading="running"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ running ? 'AI 出题中…' : '开始生成面试题' }}
        </el-button>
        <span class="muted">
          JD 长度需在 20-5000 字 · 每人每天最多 10 次 AI 出题
        </span>
      </div>
    </el-card>

    <!-- 出题进度：AgentLoop 三角色的可视化解构 -->
    <el-card v-if="sessionId" shadow="never" class="progress-card">
      <div class="progress-head">
        <span>会话 #{{ sessionId }}</span>
        <el-tag :type="statusType" effect="light">{{ statusText }}</el-tag>
      </div>

      <el-steps :active="activeStep" align-center finish-status="success" class="steps">
        <el-step title="Planner" description="拆解考察维度" />
        <el-step title="Executor" description="按计划出题" />
        <el-step title="Critic" description="校验覆盖与一致性" />
      </el-steps>

      <el-alert
        v-if="failed"
        type="error"
        :closable="false"
        show-icon
        title="出题失败"
        description="AI 服务可能暂时不可用，可稍后重试或返回列表查看历史会话。"
      />
      <p v-else class="muted tip">
        <el-icon class="is-loading"><Loading /></el-icon>
        正在调用大模型，通常需要 10-40 秒，请勿关闭页面…
      </p>

      <div class="progress-actions">
        <el-button v-if="failed" type="primary" @click="retry">重新填写</el-button>
        <el-button @click="router.push('/sessions')">返回会话列表</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick, Loading } from '@element-plus/icons-vue'
import { interviewApi } from '../api'

const router = useRouter()

const SAMPLE_JD = `招聘 Java 后端开发实习生（日常实习）
岗位职责：
1. 参与后端服务的设计与开发，编写高质量、可维护的代码；
2. 参与接口性能优化，保障服务在高并发场景下的稳定性。
任职要求：
1. 熟悉 Java 基础与 SpringBoot 框架，了解 IoC / AOP 等核心机制；
2. 熟悉 MySQL，了解索引原理、SQL 优化与事务隔离级别；
3. 熟悉 Redis，了解缓存穿透 / 击穿 / 雪崩及常见解决方案；
4. 了解 RocketMQ 等消息中间件，理解异步解耦与消息可靠性；
5. 有高并发、分布式相关项目经验者优先。`

const jdContent = ref('')
const running = ref(false)
const sessionId = ref(null)
const status = ref(0)
const failed = ref(false)
let timer = null

const canSubmit = computed(() => jdContent.value.trim().length >= 20 && !running.value)

const statusText = computed(() => {
  if (failed.value) return '失败'
  return running.value ? 'AI 出题中' : '已完成'
})

const statusType = computed(() => {
  if (failed.value) return 'danger'
  return running.value ? 'warning' : 'success'
})

// 出题是黑盒，无法精确得知当前跑到哪个角色，用一个「随时间推进」的近似指示，
// 让用户感知到 AgentLoop 确实分三段在跑。
const activeStep = computed(() => {
  if (failed.value) return 1
  if (!running.value && sessionId.value) return 3
  return elapsed.value >= 12 ? 2 : elapsed.value >= 5 ? 1 : 0
})

const elapsed = ref(0)

function fillSample() {
  jdContent.value = SAMPLE_JD
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function submit() {
  running.value = true
  failed.value = false
  elapsed.value = 0
  stopPolling()

  let created
  try {
    created = await interviewApi.create({ jdContent: jdContent.value.trim() })
  } catch (e) {
    running.value = false
    return
  }

  sessionId.value = created.sessionId
  status.value = created.status
  ElMessage.success(`会话 #${created.sessionId} 已创建，AI 正在出题`)

  timer = setInterval(async () => {
    elapsed.value += 2
    try {
      const vo = await interviewApi.getSession(sessionId.value)
      status.value = vo.status
      if (vo.status === 1) {
        stopPolling()
        running.value = false
        ElMessage.success('出题完成，即将进入答题页')
        setTimeout(() => router.replace(`/sessions/${sessionId.value}`), 600)
      } else if (vo.status === 2) {
        stopPolling()
        running.value = false
        failed.value = true
      }
    } catch (e) {
      // 轮询期间的单次失败不终止，等下一轮
    }
  }, 2000)
}

function retry() {
  sessionId.value = null
  failed.value = false
  elapsed.value = 0
}

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.narrow {
  max-width: 860px;
}

.label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.form-card {
  border-radius: 12px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.progress-card {
  border-radius: 12px;
  margin-top: 16px;
}

.progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  margin-bottom: 20px;
}

.steps {
  margin-bottom: 20px;
}

.tip {
  display: flex;
  align-items: center;
  gap: 6px;
}

.progress-actions {
  margin-top: 16px;
  display: flex;
  gap: 12px;
}
</style>
