<template>
  <div class="page">
    <el-skeleton v-if="loading" :rows="6" animated />

    <template v-else-if="detail">
      <!-- ===== 顶部会话信息 ===== -->
      <el-card shadow="never" class="session-head">
        <div class="head-left">
          <h2 class="page-title">{{ detail.position || '模拟面试' }}</h2>
          <p class="page-sub">
            {{ detail.techStack || '技术栈待 AI 回填' }} · 创建于 {{ formatTime(detail.createdAt) }}
          </p>
        </div>
        <div class="head-right">
          <el-tag v-if="detail.agentRounds" type="success" effect="plain">
            AgentLoop {{ detail.agentRounds }} 轮
          </el-tag>
          <el-tag :type="statusType(detail.status)" effect="light">
            {{ statusLabel(detail.status) }}
          </el-tag>
          <span class="progress-text">已答 {{ answeredCount }} / {{ detail.questionCount }}</span>
        </div>
      </el-card>

      <!-- 出题中 -->
      <el-card v-if="detail.status === 0" shadow="never" class="generating">
        <el-icon class="is-loading" :size="22"><Loading /></el-icon>
        <div>
          <p class="gen-title">AI 正在出题</p>
          <p class="muted">Planner 拆解维度 → Executor 出题 → Critic 校验，通常 10-40 秒</p>
        </div>
      </el-card>

      <el-result
        v-else-if="detail.status === 2"
        icon="error"
        title="出题失败"
        sub-title="该会话生成失败，请返回列表重新创建"
      >
        <template #extra>
          <el-button type="primary" @click="router.push('/create')">新建面试</el-button>
        </template>
      </el-result>

      <el-empty v-else-if="!items.length" description="没有题目" />

      <!-- ===== 双栏：左题号导航 / 右答题区 ===== -->
      <div v-else class="body">
        <el-card shadow="never" class="nav-col">
          <p class="nav-title">题目导航</p>
          <ul class="q-list">
            <li
              v-for="(it, idx) in items"
              :key="it.questionId"
              :class="{ active: idx === currentIndex, done: it.answerStatus === 1 }"
              @click="select(idx)"
            >
              <span class="q-no">{{ idx + 1 }}</span>
              <span class="q-dim">{{ it.dimension || '未分类' }}</span>
              <el-icon v-if="it.answerStatus === 1" class="q-check"><CircleCheckFilled /></el-icon>
              <el-icon v-else-if="it.answerStatus === 0" class="q-check pending"><Clock /></el-icon>
            </li>
          </ul>
        </el-card>

        <el-card v-if="current" shadow="never" class="answer-col">
          <div class="q-head">
            <div class="tags">
              <el-tag size="small" effect="dark" type="primary">
                {{ itLabel(QUESTION_TYPE, current.type, '题型') }}
              </el-tag>
              <el-tag size="small" :type="tagTypeOf(DIFFICULTY, current.difficulty)">
                难度 {{ labelOf(DIFFICULTY, current.difficulty) }}
              </el-tag>
              <el-tag v-if="current.dimension" size="small" type="success" effect="plain">
                维度 · {{ current.dimension }}
              </el-tag>
            </div>
            <span class="q-index">第 {{ currentIndex + 1 }} / {{ items.length }} 题</span>
          </div>

          <p class="q-content">{{ current.content }}</p>

          <el-divider content-position="left">你的回答</el-divider>

          <el-input
            v-model="answerText"
            type="textarea"
            :rows="8"
            maxlength="5000"
            show-word-limit
            resize="vertical"
            placeholder="像真实面试那样，条理清晰地写下你的思路与实现要点……"
            :disabled="grading"
          />

          <div class="answer-actions">
            <el-button
              type="primary"
              :loading="submitting || grading"
              @click="submit"
            >
              {{ grading ? 'AI 评分中…' : current.answerStatus === 1 ? '重新作答并评分' : '提交回答' }}
            </el-button>
            <el-button
              :disabled="currentIndex === 0"
              @click="select(currentIndex - 1)"
            >
              上一题
            </el-button>
            <el-button
              :disabled="currentIndex >= items.length - 1"
              @click="select(currentIndex + 1)"
            >
              下一题
            </el-button>
          </div>

          <!-- ===== 评分反馈 ===== -->
          <template v-if="current.answerStatus === 1 && current.score != null">
            <el-divider content-position="left">AI 评分反馈</el-divider>

            <div class="score-row">
              <div class="score-badge" :style="{ background: scoreColor(current.score) }">
                <span class="score-num">{{ current.score }}</span>
                <span class="score-max">/ 10</span>
              </div>
              <div class="score-text">
                <strong>{{ scoreComment(current.score) }}</strong>
                <p class="muted">基于你的回答，AI 给出了以下针对性反馈</p>
              </div>
            </div>

            <div class="feedback">
              <div class="fb-item fb-pros">
                <p class="fb-title"><el-icon><Select /></el-icon> 亮点</p>
                <p class="fb-body">{{ current.pros || '—' }}</p>
              </div>
              <div class="fb-item fb-cons">
                <p class="fb-title"><el-icon><Warning /></el-icon> 不足</p>
                <p class="fb-body">{{ current.cons || '—' }}</p>
              </div>
              <div class="fb-item fb-sug">
                <p class="fb-title"><el-icon><MagicStick /></el-icon> 改进建议</p>
                <p class="fb-body">{{ current.suggestions || '—' }}</p>
              </div>
            </div>
          </template>

          <el-alert
            v-else-if="current.answerStatus === 2"
            class="mt16"
            type="error"
            :closable="false"
            show-icon
            title="评分失败"
            description="AI 评分服务暂时不可用，可重新提交回答。"
          />
        </el-card>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Loading,
  CircleCheckFilled,
  Clock,
  Select,
  Warning,
  MagicStick
} from '@element-plus/icons-vue'
import { interviewApi } from '../api'
import {
  SESSION_STATUS,
  QUESTION_TYPE,
  DIFFICULTY,
  labelOf,
  tagTypeOf
} from '../utils/dict'
import { formatTime } from '../utils/format'

const route = useRoute()
const router = useRouter()
const sessionId = Number(route.params.id)

const loading = ref(true)
const detail = ref(null)
const items = computed(() => detail.value?.items || [])
const currentIndex = ref(0)
const current = computed(() => items.value[currentIndex.value] || null)

const answerText = ref('')
const submitting = ref(false)
const grading = ref(false)

// 所有仍在跑的定时器，卸载时统一清理，避免切页后继续轮询
const timers = new Set()

const statusLabel = (c) => labelOf(SESSION_STATUS, c)
const statusType = (c) => tagTypeOf(SESSION_STATUS, c)
const itLabel = labelOf
const answeredCount = computed(
  () => items.value.filter((it) => it.answerStatus === 1).length
)

function scoreColor(score) {
  if (score >= 8) return '#67c23a'
  if (score >= 6) return '#409eff'
  if (score >= 4) return '#e6a23c'
  return '#f56c6c'
}

function scoreComment(score) {
  if (score >= 8) return '回答质量很高'
  if (score >= 6) return '基本达标，仍有提升空间'
  if (score >= 4) return '部分要点缺失'
  return '与题目要求偏离较大'
}

function select(idx) {
  currentIndex.value = idx
  answerText.value = current.value?.answerContent || ''
}

/** 出题未完成时轮询会话状态 */
function waitForQuestions() {
  return new Promise((resolve) => {
    const t = setInterval(async () => {
      try {
        const vo = await interviewApi.getSession(sessionId)
        if (vo.status === 1 || vo.status === 2) {
          clearInterval(t)
          timers.delete(t)
          const fresh = await interviewApi.sessionDetail(sessionId)
          resolve(fresh)
        }
      } catch (e) {
        // 单次失败继续轮询
      }
    }, 2000)
    timers.add(t)
  })
}

/** 提交后轮询评分结果，并回填到当前题目 */
function pollGrade(item) {
  return new Promise((resolve) => {
    const t = setInterval(async () => {
      try {
        const r = await interviewApi.answerResult(item.answerId)
        item.answerStatus = r.status
        if (r.status === 1) {
          item.score = r.score
          item.pros = r.pros
          item.cons = r.cons
          item.suggestions = r.suggestions
          item.answerContent = answerText.value.trim()
          clearInterval(t)
          timers.delete(t)
          resolve()
        } else if (r.status === 2) {
          clearInterval(t)
          timers.delete(t)
          resolve()
        }
      } catch (e) {
        // 继续轮询
      }
    }, 2000)
    timers.add(t)
  })
}

async function submit() {
  const text = answerText.value.trim()
  if (!text) {
    ElMessage.warning('请先写下你的回答')
    return
  }
  const item = current.value
  submitting.value = true
  grading.value = true
  try {
    const res = await interviewApi.submitAnswer({
      questionId: item.questionId,
      content: text
    })
    item.answerId = res.answerId
    item.answerStatus = 0
    await pollGrade(item)
    if (item.answerStatus === 1) {
      ElMessage.success(`评分完成：${item.score} / 10`)
    } else if (item.answerStatus === 2) {
      ElMessage.error('评分失败，请重试')
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    submitting.value = false
    grading.value = false
  }
}

async function init() {
  loading.value = true
  try {
    let d = await interviewApi.sessionDetail(sessionId)
    if (d.status === 0) {
      d = await waitForQuestions()
    }
    detail.value = d
    const firstUnanswered = d.items.findIndex((it) => it.answerStatus !== 1)
    currentIndex.value = firstUnanswered >= 0 ? firstUnanswered : 0
    answerText.value = current.value?.answerContent || ''
  } catch (e) {
    // 404/403 等由拦截器提示，这里给一个兜底跳转
    detail.value = null
  } finally {
    loading.value = false
  }
}

onMounted(init)

onBeforeUnmount(() => {
  timers.forEach((t) => clearInterval(t))
  timers.clear()
})
</script>

<style scoped>
.session-head {
  border-radius: 12px;
  margin-bottom: 16px;
}

.session-head :deep(.el-card__body) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.progress-text {
  font-size: 13px;
  color: #606266;
}

.generating {
  border-radius: 12px;
}

.generating :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 14px;
}

.gen-title {
  margin: 0;
  font-weight: 600;
}

.body {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 16px;
  align-items: start;
}

.nav-col,
.answer-col {
  border-radius: 12px;
}

.nav-title {
  font-size: 13px;
  color: #909399;
  margin: 0 0 12px;
}

.q-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.q-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
  transition: background 0.15s ease;
}

.q-list li:hover {
  background: #f5f7fa;
}

.q-list li.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}

.q-list li.done .q-no {
  background: #67c23a;
  color: #fff;
}

.q-no {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #e4e7ed;
  color: #606266;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.q-dim {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.q-check {
  color: #67c23a;
}

.q-check.pending {
  color: #e6a23c;
}

.q-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.q-index {
  font-size: 13px;
  color: #909399;
}

.q-content {
  font-size: 15px;
  line-height: 1.7;
  margin: 16px 0;
  white-space: pre-wrap;
}

.answer-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.score-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 18px;
}

.score-badge {
  width: 76px;
  height: 76px;
  border-radius: 14px;
  color: #fff;
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 2px;
  flex-shrink: 0;
}

.score-num {
  font-size: 32px;
  font-weight: 700;
  line-height: 1;
}

.score-max {
  font-size: 13px;
  opacity: 0.85;
}

.score-text strong {
  font-size: 15px;
}

.score-text p {
  margin: 4px 0 0;
}

.feedback {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.fb-item {
  border-radius: 10px;
  padding: 14px 16px;
  border-left: 3px solid transparent;
}

.fb-pros {
  background: #f0f9eb;
  border-left-color: #67c23a;
}

.fb-cons {
  background: #fdf6ec;
  border-left-color: #e6a23c;
}

.fb-sug {
  background: #ecf5ff;
  border-left-color: #409eff;
}

.fb-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
  margin: 0 0 6px;
}

.fb-pros .fb-title {
  color: #529b2e;
}

.fb-cons .fb-title {
  color: #b88230;
}

.fb-sug .fb-title {
  color: #337ecc;
}

.fb-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
}

.mt16 {
  margin-top: 16px;
}

@media (max-width: 860px) {
  .body {
    grid-template-columns: 1fr;
  }
}
</style>
