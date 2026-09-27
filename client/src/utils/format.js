// 时间与文本的展示层工具

/** 后端 LocalDateTime 序列化为 "2026-09-27T11:37:40"（无时区）。这里只做展示格式化，不做时区换算。 */
export function formatTime(value) {
  if (!value) return '—'
  const s = String(value).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

/** 把一段长文本压成单行摘要 */
export function summarize(text, max = 60) {
  if (!text) return ''
  const flat = String(text).replace(/\s+/g, ' ').trim()
  return flat.length > max ? flat.slice(0, max) + '…' : flat
}
