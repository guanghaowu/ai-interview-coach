// 与后端 constant 包一一对应的字典。后端以 int 传输，前端负责翻译成中文标签。
// 抽成单一来源，避免各页面各写一份 switch。

export const SESSION_STATUS = {
  0: { label: 'AI 出题中', type: 'warning' },
  1: { label: '已完成', type: 'success' },
  2: { label: '失败', type: 'danger' }
}

export const ANSWER_STATUS = {
  0: { label: '待评分', type: 'warning' },
  1: { label: '已评分', type: 'success' },
  2: { label: '评分失败', type: 'danger' }
}

export const QUESTION_TYPE = {
  1: { label: '编程题', type: 'primary' },
  2: { label: '场景题', type: 'warning' },
  3: { label: '项目题', type: 'success' },
  4: { label: '八股题', type: 'info' }
}

export const DIFFICULTY = {
  1: { label: '易', type: 'success' },
  2: { label: '中', type: 'warning' },
  3: { label: '难', type: 'danger' }
}

export function labelOf(dict, code, fallback = '未知') {
  const hit = dict[code]
  return hit ? hit.label : fallback
}

export function tagTypeOf(dict, code, fallback = 'info') {
  const hit = dict[code]
  return hit ? hit.type : fallback
}
