import http from './http'

// 拦截器已把 { code, message, data } 解包，这里拿到的直接就是 data。

export const userApi = {
  register: (data) => http.post('/user/register', data),
  login: (data) => http.post('/user/login', data),
  info: () => http.get('/user/info'),
  updateInfo: (data) => http.put('/user/info', data)
}

export const interviewApi = {
  /** 创建会话（AI 异步出题），返回 { sessionId, status:0, ... } */
  create: (data) => http.post('/interview/create', data),
  /** 轮询出题结果：status 0=出题中 1=完成 2=失败 */
  getSession: (sessionId) => http.get(`/interview/${sessionId}`),
  /** 我的会话列表（分页） */
  listSessions: (page = 1, size = 10) =>
    http.get('/interview/sessions', { params: { page, size } }),
  /** 会话完整详情（题目 + 每题最新回答 + 反馈） */
  sessionDetail: (sessionId) => http.get(`/interview/sessions/${sessionId}`),
  /** 提交回答（AI 异步评分） */
  submitAnswer: (data) => http.post('/interview/answer', data),
  /** 轮询评分结果：status 0=待评分 1=已评分 2=失败 */
  answerResult: (answerId) => http.get(`/interview/answer/${answerId}`)
}
