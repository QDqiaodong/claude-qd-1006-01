<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import api from '../api'

const props = defineProps({ role: String })

// ------------------------------------------------------------------
// 加药批次登记与异常复核：待确认 → 已确认 / 异常待复核 → 已确认 / 已退回。
// 所有状态以后端为准：每次操作后端返回最新详情，刷新 / 重进都从这里重新拉取。
// 按钮可用性与后端校验口径一致：
//   登记 / 维护 = 运行工、管理员（维护限本人 + 待确认）；确认 / 标异常 / 复核 / 配置 = 管理员。
// ------------------------------------------------------------------

const isAdmin = computed(() => props.role === '管理员')
const canRegister = computed(() => props.role === '运行工' || props.role === '管理员')

const rows = ref([])
const config = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)

// 当前操作者：登记人 / 复核人，持久化到 localStorage，刷新不丢
const operator = ref(localStorage.getItem('dosing-operator') || '')

// 筛选：目标工段 / 状态 / 执行时间范围
const tankFilter = ref('')
const statusFilter = ref('')
const range = ref([])

const statuses = ['待确认', '异常待复核', '已确认', '已退回', '已撤回']

const activeChems = computed(() =>
  config.value.filter(c => c.configType === '药剂' && c.active).map(c => c.configValue))
const activeTargets = computed(() =>
  config.value.filter(c => c.configType === '工段' && c.active).map(c => c.configValue))

// 工段筛选项：当前配置 + 历史记录里出现过的工段（配置变更后历史仍可追溯）
const filterTargets = computed(() =>
  [...new Set([...activeTargets.value, ...rows.value.map(r => r.targetTank)])])

const stats = computed(() => ({
  pending: rows.value.filter(r => r.status === '待确认').length,
  review: rows.value.filter(r => r.status === '异常待复核').length,
  confirmed: rows.value.filter(r => r.status === '已确认').length,
  returned: rows.value.filter(r => r.status === '已退回').length
}))

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}

function statusTag(s) {
  return s === '已确认' ? 'success' : s === '异常待复核' ? 'danger' : s === '待确认' ? 'warning' : 'info'
}

/** 维护权限：本人提交且尚未确认（待确认）—— 与后端 requireOwner + requirePending 一致 */
function canMaintainRow(row) {
  return canRegister.value && row.status === '待确认' && row.operatorName === operator.value.trim()
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const p = {}
    if (tankFilter.value) p.targetTank = tankFilter.value
    if (statusFilter.value) p.status = statusFilter.value
    if (range.value && range.value.length === 2) { p.from = range.value[0]; p.to = range.value[1] }
    rows.value = await api.listDosingRecords(p) || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

async function loadConfig() {
  try {
    config.value = await api.listDosingConfig() || []
  } catch (e) { /* 配置拉取失败不阻断列表，登记时后端仍会校验 */ }
}

function persistOperator() {
  localStorage.setItem('dosing-operator', operator.value || '')
}

function requireOperator() {
  if (!operator.value || !operator.value.trim()) {
    detailError.value = '请先在右上角填写当前操作者'
    error.value = '请先在右上角填写当前操作者'
    return false
  }
  persistOperator()
  return true
}

// ==================== 登记 / 修改 ====================

const dialog = ref(false)
const dialogError = ref('')
const saving = ref(false)
const editingId = ref(null)
const form = ref(blank())

function nowString() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function blank() {
  return { chemName: '', doseMgPerL: null, targetTank: '', dosedAt: '', operator: '', note: '' }
}

/** 后端返回的执行时间可能省略秒（2026-10-06T08:00），补齐给日期控件 */
function toPickerValue(t) {
  if (!t) return ''
  let s = String(t)
  if (s.length === 16) s += ':00'
  return s
}

function openCreate() {
  editingId.value = null
  form.value = { ...blank(), dosedAt: nowString(), operator: operator.value }
  dialogError.value = ''
  dialog.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    chemName: row.chemName,
    doseMgPerL: row.doseMgPerL,
    targetTank: row.targetTank,
    dosedAt: toPickerValue(row.dosedAt),
    operator: row.operatorName,
    note: row.note || ''
  }
  dialogError.value = ''
  dialog.value = true
}

/** 提交前必须填写完整：药剂 / 投加量 / 目标工段 / 执行时间 / 操作者（后端同样校验） */
function validateForm() {
  const f = form.value
  if (!f.chemName) return '请选择药剂'
  if (!f.doseMgPerL || f.doseMgPerL <= 0) return '投加量必须大于 0（mg/L）'
  if (!f.targetTank) return '请选择目标工段'
  if (!f.dosedAt) return '请选择执行时间'
  if (!f.operator || !f.operator.trim()) return '请填写操作者'
  return ''
}

async function save() {
  const msg = validateForm()
  if (msg) { dialogError.value = msg + '，填写完整后才能提交'; return }
  saving.value = true; dialogError.value = ''
  try {
    const f = form.value
    const d = {
      chemName: f.chemName,
      doseMgPerL: f.doseMgPerL,
      targetTank: f.targetTank,
      dosedAt: f.dosedAt,
      operator: f.operator.trim(),
      note: f.note,
      role: props.role
    }
    const saved = editingId.value
      ? await api.editDosingRecord(editingId.value, d)
      : await api.registerDosingRecord(d)
    operator.value = d.operator; persistOperator()
    tip.value = editingId.value
      ? `批次 ${saved.batchNo} 已更新`
      : `批次 ${saved.batchNo} 已登记，待管理员核对现场回执`
    dialog.value = false
    await load()
    openDetail(saved.id)
  } catch (e) {
    // 重复登记 / 配置失效 / 权限不足：后端返回的明确原因直接展示，并刷新列表让已存在批次可见
    dialogError.value = e.message
    await load()
  } finally { saving.value = false }
}

async function doWithdraw(row) {
  if (!requireOperator()) return
  try {
    await ElMessageBox.confirm(
      `确认撤回批次 ${row.batchNo}？撤回后「工段 + 执行时间」槽位释放，记录保留为「已撤回」可追溯。`,
      '撤回确认', { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' })
  } catch { return }
  try {
    const saved = await api.withdrawDosingRecord(row.id, { operator: operator.value.trim(), role: props.role })
    tip.value = `批次 ${saved.batchNo} 已撤回`
    if (detail.value && detail.value.id === row.id) detail.value = saved
    await load()
  } catch (e) { error.value = e.message; await load() }
}

// ==================== 详情 ====================

const drawer = ref(false)
const detail = ref(null)
const detailError = ref('')

async function openDetail(id) {
  detailError.value = ''
  try {
    detail.value = await api.getDosingRecord(id)
    drawer.value = true
  } catch (e) { error.value = e.message }
}

async function refreshDetail(id) {
  detail.value = await api.getDosingRecord(id)
  await load()
}

// ==================== 管理员：确认 / 标异常 / 复核 ====================

const acting = ref(false)
const confirmNote = ref('')
const flagReason = ref('')
const reviewConclusion = ref('')
const returnReason = ref('')

async function doConfirm() {
  if (!requireOperator()) return
  if (!confirmNote.value || !confirmNote.value.trim()) {
    detailError.value = '确认前须核对现场回执并填写核对说明'; return
  }
  acting.value = true; detailError.value = ''
  try {
    detail.value = await api.confirmDosingRecord(detail.value.id, {
      receiptNote: confirmNote.value.trim(), operator: operator.value.trim(), role: props.role
    })
    tip.value = `批次 ${detail.value.batchNo} 已确认`
    confirmNote.value = ''
    await load()
  } catch (e) { detailError.value = e.message; await refreshDetail(detail.value.id) } finally { acting.value = false }
}

async function doFlag() {
  if (!requireOperator()) return
  if (!flagReason.value || !flagReason.value.trim()) {
    detailError.value = '标记异常必须填写异常说明（投加量 / 药剂 / 目标工段）'; return
  }
  acting.value = true; detailError.value = ''
  try {
    detail.value = await api.flagDosingRecord(detail.value.id, {
      reason: flagReason.value.trim(), operator: operator.value.trim(), role: props.role
    })
    tip.value = `批次 ${detail.value.batchNo} 已转为异常待复核`
    flagReason.value = ''
    await load()
  } catch (e) { detailError.value = e.message; await refreshDetail(detail.value.id) } finally { acting.value = false }
}

async function doReviewConfirm() {
  if (!requireOperator()) return
  if (!reviewConclusion.value || !reviewConclusion.value.trim()) {
    detailError.value = '复核确认必须填写复核结论'; return
  }
  acting.value = true; detailError.value = ''
  try {
    detail.value = await api.reviewConfirmDosingRecord(detail.value.id, {
      reason: reviewConclusion.value.trim(), operator: operator.value.trim(), role: props.role
    })
    tip.value = `批次 ${detail.value.batchNo} 复核通过，已确认`
    reviewConclusion.value = ''
    await load()
  } catch (e) { detailError.value = e.message; await refreshDetail(detail.value.id) } finally { acting.value = false }
}

async function doReviewReturn() {
  if (!requireOperator()) return
  if (!returnReason.value || !returnReason.value.trim()) {
    detailError.value = '复核退回必须填写退回原因'; return
  }
  acting.value = true; detailError.value = ''
  try {
    detail.value = await api.reviewReturnDosingRecord(detail.value.id, {
      reason: returnReason.value.trim(), operator: operator.value.trim(), role: props.role
    })
    tip.value = `批次 ${detail.value.batchNo} 已退回，原始登记保留`
    returnReason.value = ''
    await load()
  } catch (e) { detailError.value = e.message; await refreshDetail(detail.value.id) } finally { acting.value = false }
}

// ==================== 工艺配置（管理员） ====================

const configDialog = ref(false)
const configError = ref('')
const configSaving = ref(false)
const addForm = ref({ configType: '药剂', configValue: '' })

function openConfig() {
  configError.value = ''
  addForm.value = { configType: '药剂', configValue: '' }
  configDialog.value = true
}

async function toggleConfig(item) {
  if (!requireOperator()) return
  configSaving.value = true; configError.value = ''
  try {
    await api.saveDosingConfig({
      configType: item.configType, configValue: item.configValue,
      active: !item.active, operator: operator.value.trim(), role: props.role
    })
    tip.value = `已${item.active ? '停用' : '启用'}${item.configType}「${item.configValue}」，历史批次保持登记时取值`
    await loadConfig()
    await load()
    if (detail.value) await refreshDetail(detail.value.id)
  } catch (e) { configError.value = e.message } finally { configSaving.value = false }
}

async function addConfig() {
  if (!requireOperator()) return
  if (!addForm.value.configValue || !addForm.value.configValue.trim()) {
    configError.value = '请填写配置值'; return
  }
  configSaving.value = true; configError.value = ''
  try {
    await api.saveDosingConfig({
      configType: addForm.value.configType, configValue: addForm.value.configValue.trim(),
      active: true, operator: operator.value.trim(), role: props.role
    })
    tip.value = `已新增${addForm.value.configType}「${addForm.value.configValue.trim()}」`
    addForm.value.configValue = ''
    await loadConfig()
  } catch (e) { configError.value = e.message } finally { configSaving.value = false }
}

// ==================== 入口 ====================

onMounted(async () => {
  await Promise.all([load(), loadConfig()])
})
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">加药记录 · 批次复核</span>
        <span class="panel-desc">待确认 → 已确认 / 异常待复核 → 复核；同一目标工段同一执行时间仅一个有效批次</span>
        <span class="spacer"></span>
        <span class="op-label">当前操作者</span>
        <el-input v-model="operator" placeholder="姓名" style="width: 130px" size="small"
          @change="persistOperator" />
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">待确认</div><div class="v">{{ stats.pending }}</div></div>
        <div class="stat"><div class="k">异常待复核</div><div class="v" :style="stats.review ? 'color:#c0392b' : ''">{{ stats.review }}</div></div>
        <div class="stat"><div class="k">已确认</div><div class="v">{{ stats.confirmed }}</div></div>
        <div class="stat"><div class="k">已退回</div><div class="v">{{ stats.returned }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="tankFilter" placeholder="全部工段" clearable style="width: 130px" @change="load">
          <el-option v-for="t in filterTargets" :key="t" :label="t" :value="t" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="执行开始" end-placeholder="执行结束" style="width: 240px" @change="load" />
        <el-button @click="load">刷新</el-button>
        <span class="spacer"></span>
        <el-button :disabled="!isAdmin" @click="openConfig">
          {{ isAdmin ? '工艺配置' : '工艺配置（仅管理员）' }}
        </el-button>
        <el-button type="primary" :disabled="!canRegister" @click="openCreate">
          {{ canRegister ? '登记批次' : '仅运行工/管理员可登记' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="batchNo" label="批次号" width="140" />
        <el-table-column prop="chemName" label="药剂" width="110">
          <template #default="{ row }"><el-tag effect="plain">{{ row.chemName }}</el-tag></template>
        </el-table-column>
        <el-table-column label="投加量" width="110">
          <template #default="{ row }">{{ row.doseMgPerL }} mg/L</template>
        </el-table-column>
        <el-table-column prop="targetTank" label="目标工段" width="100" />
        <el-table-column label="执行时间" width="160">
          <template #default="{ row }">{{ fmt(row.dosedAt) }}</template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作者" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="配置" width="100">
          <template #default="{ row }">
            <el-tooltip v-if="row.configChanged" content="登记时的药剂/工段已不在当前工艺配置中，历史记录保持登记时取值" placement="top">
              <el-tag type="danger" size="small">配置已变更</el-tag>
            </el-tooltip>
            <el-tag v-else type="success" size="small" effect="plain">有效</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reviewNote" label="复核 / 退回原因" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reviewNote || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row.id)">详情</el-button>
            <el-button size="small" type="primary" plain :disabled="!canMaintainRow(row)" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="warning" plain :disabled="!canMaintainRow(row)" @click="doWithdraw(row)">撤回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ==================== 登记 / 修改批次 ==================== -->
    <el-dialog v-model="dialog" :title="editingId ? '修改批次（仅待确认可改，修改留痕）' : '登记加药批次'" width="560px">
      <div v-if="dialogError" class="err">{{ dialogError }}</div>
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="药剂" required>
          <el-select v-model="form.chemName" placeholder="当前工艺配置内的药剂" style="width: 100%">
            <el-option v-for="c in activeChems" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="投加量(mg/L)" required>
          <el-input-number v-model="form.doseMgPerL" :min="0.01" :precision="2" :step="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="目标工段" required class="full">
          <el-select v-model="form.targetTank" placeholder="当前工艺配置内的工段" style="width: 100%">
            <el-option v-for="t in activeTargets" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行时间" required>
          <el-date-picker v-model="form.dosedAt" type="datetime" style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="现场投加执行时间" />
        </el-form-item>
        <el-form-item label="操作者" required>
          <el-input v-model="form.operator" placeholder="登记人姓名" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" placeholder="选填" /></el-form-item>
      </el-form>
      <div class="hint" style="margin-top: 4px">
        药剂 / 投加量 / 目标工段 / 执行时间 / 操作者均须填写完整；药剂与目标工段须在当前工艺配置内。
        同一目标工段 + 同一执行时间只允许一个有效批次，重复提交将被拒绝并显示已存在批次及其状态。
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="save">
          {{ editingId ? '保存修改' : '登记' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 批次详情 ==================== -->
    <el-drawer v-model="drawer" size="720px" :title="detail ? `批次 ${detail.batchNo}` : '批次详情'">
      <div v-if="detail" style="padding: 0 4px 20px">
        <div v-if="detailError" class="err">{{ detailError }}</div>

        <div v-if="detail.configChanged" class="err" style="font-weight:600">
          工艺配置已变更：本批次登记时的{{ detail.chemActive ? '' : '药剂' }}{{ !detail.chemActive && !detail.targetActive ? '、' : '' }}{{ detail.targetActive ? '' : '目标工段' }}已不在当前工艺配置中。
          历史记录保持登记时取值，不回溯改写。
        </div>

        <div class="sec-title">原始登记</div>
        <el-descriptions :column="2" border size="small" style="margin-bottom: 14px">
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.status)">{{ detail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="操作者">{{ detail.operatorName }}</el-descriptions-item>
          <el-descriptions-item label="药剂">
            {{ detail.chemName }}
            <el-tag v-if="!detail.chemActive" type="danger" size="small" style="margin-left:6px">已停用</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="投加量">{{ detail.doseMgPerL }} mg/L</el-descriptions-item>
          <el-descriptions-item label="目标工段">
            {{ detail.targetTank }}
            <el-tag v-if="!detail.targetActive" type="danger" size="small" style="margin-left:6px">已停用</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="执行时间">{{ fmt(detail.dosedAt) }}</el-descriptions-item>
          <el-descriptions-item label="登记时间">{{ fmt(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近更新">{{ fmt(detail.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.note" label="备注" :span="2">{{ detail.note }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="detail.reviewNote">
          <div class="sec-title">复核结论</div>
          <el-descriptions :column="1" border size="small" style="margin-bottom: 14px">
            <el-descriptions-item label="结论 / 原因">{{ detail.reviewNote }}</el-descriptions-item>
            <el-descriptions-item label="复核人">{{ detail.reviewedBy }} · {{ fmt(detail.reviewedAt) }}</el-descriptions-item>
          </el-descriptions>
        </template>

        <!-- 待确认：管理员核对 / 标异常 -->
        <template v-if="detail.status === '待确认'">
          <div class="sec-title">管理员核对</div>
          <div class="action-bar">
            <el-input v-model="confirmNote" type="textarea" :rows="2"
              placeholder="现场回执核对说明（必填）：核对一致后方可确认" style="margin-bottom: 8px" />
            <div style="display:flex;align-items:center;gap:10px">
              <span class="hint">确认后批次转为「已确认」，不可再修改</span>
              <span class="spacer"></span>
              <el-button type="success" :loading="acting" :disabled="!isAdmin || acting" @click="doConfirm">
                {{ isAdmin ? '核对无误，确认' : '仅管理员可确认' }}
              </el-button>
            </div>
          </div>
          <div class="action-bar" style="margin-top:10px">
            <el-input v-model="flagReason" type="textarea" :rows="2"
              placeholder="异常说明（必填）：投加量 / 药剂 / 目标工段何处异常" style="margin-bottom: 8px" />
            <div style="display:flex;align-items:center;gap:10px">
              <span class="hint">发现异常时转为「异常待复核」，由管理员复核</span>
              <span class="spacer"></span>
              <el-button type="danger" plain :loading="acting" :disabled="!isAdmin || acting" @click="doFlag">
                转为异常待复核
              </el-button>
            </div>
          </div>
        </template>

        <!-- 异常待复核：管理员复核 -->
        <template v-if="detail.status === '异常待复核'">
          <div class="sec-title">管理员复核</div>
          <div class="action-bar">
            <el-input v-model="reviewConclusion" type="textarea" :rows="2"
              placeholder="复核结论（必填）：复核通过后批次转为「已确认」" style="margin-bottom: 8px" />
            <div style="display:flex;align-items:center;gap:10px">
              <span class="hint">复核通过：批次转为「已确认」</span>
              <span class="spacer"></span>
              <el-button type="success" :loading="acting" :disabled="!isAdmin || acting" @click="doReviewConfirm">
                {{ isAdmin ? '复核通过并确认' : '仅管理员可复核' }}
              </el-button>
            </div>
          </div>
          <div class="action-bar" style="margin-top:10px">
            <el-input v-model="returnReason" type="textarea" :rows="2"
              placeholder="退回原因（必填）：原始登记保留，不会被覆盖" style="margin-bottom: 8px" />
            <div style="display:flex;align-items:center;gap:10px">
              <span class="hint">退回后原始登记保留并记录原因，运行工需另行登记新批次</span>
              <span class="spacer"></span>
              <el-button type="warning" :loading="acting" :disabled="!isAdmin || acting" @click="doReviewReturn">
                复核退回
              </el-button>
            </div>
          </div>
        </template>

        <!-- 待确认：登记人维护（本人） -->
        <template v-if="detail.status === '待确认'">
          <div class="sec-title">登记人维护</div>
          <div class="action-bar" style="display:flex;align-items:center;gap:10px">
            <span class="hint">仅登记人本人（{{ detail.operatorName }}）且批次「待确认」时可修改 / 撤回</span>
            <span class="spacer"></span>
            <el-button type="primary" plain :disabled="!canMaintainRow(detail)" @click="openEdit(detail)">修改批次</el-button>
            <el-button type="warning" plain :disabled="!canMaintainRow(detail)" @click="doWithdraw(detail)">撤回批次</el-button>
          </div>
        </template>

        <!-- 操作记录 -->
        <div class="sec-title">操作记录</div>
        <el-timeline style="padding-left: 4px">
          <el-timeline-item v-for="log in [...detail.logs].reverse()" :key="log.id"
            :timestamp="fmt(log.createdAt)" placement="top"
            :type="log.action === '复核退回' || log.action === '标异常' ? 'danger' : log.action === '确认' || log.action === '复核确认' ? 'success' : 'primary'">
            <div style="font-weight:700">
              {{ log.action }}
              <span v-if="log.fromStatus !== log.toStatus" style="font-weight:400;color:#6b8a96">
                {{ log.fromStatus || '—' }} → {{ log.toStatus }}
              </span>
              <span style="font-weight:400;color:#6b8a96"> · {{ log.operatorName }}</span>
            </div>
            <div style="font-size:12px;color:#6b8a96">{{ log.detail }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- ==================== 工艺配置（管理员） ==================== -->
    <el-dialog v-model="configDialog" title="加药工艺配置" width="620px">
      <div v-if="configError" class="err">{{ configError }}</div>
      <div class="hint" style="margin-bottom:10px">
        登记批次时药剂与目标工段必须在启用清单内；停用 / 新增只影响后续登记，
        历史批次保持登记时取值并标「配置已变更」，不回溯改写。
      </div>
      <el-table :data="config" border size="small" style="margin-bottom: 12px">
        <el-table-column prop="configType" label="类型" width="80" />
        <el-table-column prop="configValue" label="配置值" min-width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '已停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近调整" min-width="170">
          <template #default="{ row }">{{ row.updatedBy || '—' }} · {{ fmt(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" :type="row.active ? 'warning' : 'success'" plain
              :disabled="configSaving" @click="toggleConfig(row)">
              {{ row.active ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="display:flex;gap:10px;align-items:center">
        <el-select v-model="addForm.configType" style="width: 100px">
          <el-option label="药剂" value="药剂" />
          <el-option label="工段" value="工段" />
        </el-select>
        <el-input v-model="addForm.configValue" placeholder="新配置值，如 聚丙烯酰胺 / SED-04" style="flex:1" />
        <el-button type="primary" :loading="configSaving" :disabled="configSaving" @click="addConfig">新增</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.op-label { font-size: 12px; color: var(--muted); }
.sec-title {
  font-size: 13px; font-weight: 800; color: var(--primary-deep);
  border-left: 3px solid var(--primary); padding-left: 8px;
  margin: 16px 0 10px;
}
.action-bar { background: var(--primary-wash); border-radius: 8px; padding: 12px; }
.hint { font-size: 12px; color: var(--muted); }
</style>
