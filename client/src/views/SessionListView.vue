<template>
  <div class="page">
    <div class="head">
      <div>
        <h2 class="page-title">我的面试会话</h2>
        <p class="page-sub">共 {{ total }} 个会话 · 点击卡片进入答题与评分</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="router.push('/create')">
        新建面试
      </el-button>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />

    <el-empty v-else-if="!records.length" description="还没有面试会话，先粘贴一段 JD 试试">
      <el-button type="primary" @click="router.push('/create')">新建面试</el-button>
    </el-empty>

    <template v-else>
      <div class="grid">
        <el-card
          v-for="item in records"
          :key="item.sessionId"
          class="session-card"
          shadow="hover"
          @click="open(item)"
        >
          <div class="card-top">
            <span class="card-title">{{ item.position || '未命名面试' }}</span>
            <el-tag :type="statusType(item.status)" size="small" effect="light">
              {{ statusLabel(item.status) }}
            </el-tag>
          </div>

          <p class="jd-ellipsis">{{ item.techStack || '技术栈待 AI 回填' }}</p>

          <div class="card-stats">
            <span><el-icon><Document /></el-icon> {{ item.questionCount }} 题</span>
            <span><el-icon><EditPen /></el-icon> 已答 {{ item.answeredCount }}</span>
          </div>

          <div class="card-foot">
            <span class="muted">{{ formatTime(item.createdAt) }}</span>
            <el-icon class="arrow"><ArrowRight /></el-icon>
          </div>
        </el-card>
      </div>

      <div class="pager">
        <el-pagination
          layout="prev, pager, next, total"
          :total="total"
          :current-page="page"
          :page-size="size"
          background
          @current-change="onPageChange"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Document, EditPen, ArrowRight } from '@element-plus/icons-vue'
import { interviewApi } from '../api'
import { SESSION_STATUS, labelOf, tagTypeOf } from '../utils/dict'
import { formatTime } from '../utils/format'

const router = useRouter()

const loading = ref(true)
const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(9)

const statusLabel = (c) => labelOf(SESSION_STATUS, c)
const statusType = (c) => tagTypeOf(SESSION_STATUS, c)

async function load() {
  loading.value = true
  try {
    const data = await interviewApi.listSessions(page.value, size.value)
    records.value = data.records || []
    total.value = Number(data.total) || 0
  } catch (e) {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onPageChange(p) {
  page.value = p
  load()
}

function open(item) {
  if (item.status === 2) return
  router.push(`/sessions/${item.sessionId}`)
}

onMounted(load)
</script>

<style scoped>
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 20px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-stats {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #606266;
  margin: 10px 0 12px;
}

.card-stats span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-top: 1px solid #f0f2f5;
  padding-top: 10px;
}

.arrow {
  color: #c0c4cc;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
