import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 原水监测 ----------------
  listRawWaters: (p) => call(http.get('/raw-waters', params(p))),
  createRawWater: (d) => call(http.post('/raw-waters', d)),
  updateRawWater: (id, d) => call(http.put(`/raw-waters/${id}`, d)),
  reclassifyRawWater: (id, status) =>
    call(http.post(`/raw-waters/${id}/reclassify`, null, params({ status }))),

  // ---------------- 沉淀池 ----------------
  listTanks: (p) => call(http.get('/sedimentation-tanks', params(p))),
  createTank: (d) => call(http.post('/sedimentation-tanks', d)),
  updateTank: (id, d) => call(http.put(`/sedimentation-tanks/${id}`, d)),
  transferTank: (id, target) =>
    call(http.post(`/sedimentation-tanks/${id}/transfer`, null, params({ target }))),

  // ---------------- 滤池 ----------------
  listFilters: (p) => call(http.get('/filters', params(p))),
  createFilter: (d) => call(http.post('/filters', d)),
  updateFilter: (id, d) => call(http.put(`/filters/${id}`, d)),
  backwashFilter: (id) => call(http.post(`/filters/${id}/backwash`)),
  resumeFilter: (id) => call(http.post(`/filters/${id}/resume`)),
  transferFilter: (id, target) =>
    call(http.post(`/filters/${id}/transfer`, null, params({ target }))),

  // ---------------- 加药 ----------------
  listDosings: (p) => call(http.get('/dosings', params(p))),
  createDosing: (d) => call(http.post('/dosings', d)),
  updateDosing: (id, d) => call(http.put(`/dosings/${id}`, d)),

  // ---------------- 超标处置闭环 ----------------
  listDisposals: (p) => call(http.get('/disposals', params(p))),
  getDisposal: (id) => call(http.get(`/disposals/${id}`)),
  initiateDisposal: (d) => call(http.post('/disposals', d)),
  startDisposal: (id, operator) => call(http.post(`/disposals/${id}/start`, { operator })),
  resolveDisposal: (id, d) => call(http.post(`/disposals/${id}/resolve`, d)),
  replaceDisposalEquip: (id, linkId, d) => call(http.post(`/disposals/${id}/equipments/${linkId}/replace`, d)),
  excludeDisposalEquip: (id, linkId, d) => call(http.post(`/disposals/${id}/equipments/${linkId}/exclude`, d)),
  registerDisposalDosing: (id, d) => call(http.post(`/disposals/${id}/dosings`, d))
}
