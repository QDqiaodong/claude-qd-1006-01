<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const props = defineProps({ role: String })

const rows = ref([])
const error = ref('')
const tip = ref('')
const loading = ref(false)
const chemFilter = ref('')

const canDose = computed(() => props.role === '运行工' || props.role === '管理员')
const chems = ['聚合氯化铝', '次氯酸钠']

const dialog = ref(false)
const saving = ref(false)
const form = ref(blank())

function blank() {
  return { chemName: '聚合氯化铝', doseMgPerL: '', targetTank: '', dosedAt: '', note: '' }
}

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api.listDosings(chemFilter.value ? { chemName: chemFilter.value } : {})
    rows.value = data || []
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

function openCreate() { form.value = blank(); dialog.value = true; tip.value = ''; error.value = '' }

async function save() {
  saving.value = true; error.value = ''
  try {
    const d = {
      chemName: form.value.chemName,
      doseMgPerL: Number(form.value.doseMgPerL),
      targetTank: form.value.targetTank,
      dosedAt: form.value.dosedAt ? form.value.dosedAt : null,
      note: form.value.note
    }
    await api.createDosing(d)
    tip.value = `已登记加药：${d.chemName} → ${d.targetTank}`
    dialog.value = false
    await load()
  } catch (e) { error.value = e.message } finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">加药 · 混凝与消毒</span>
        <span class="panel-desc">药剂限 聚合氯化铝 / 次氯酸钠，投加量 &gt; 0，目标池必填</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">加药次数</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">聚合氯化铝</div><div class="v">{{ rows.filter(r => r.chemName === '聚合氯化铝').length }}</div></div>
        <div class="stat"><div class="k">次氯酸钠</div><div class="v">{{ rows.filter(r => r.chemName === '次氯酸钠').length }}</div></div>
      </div>

      <div class="toolbar">
        <el-select v-model="chemFilter" placeholder="全部药剂" clearable style="width: 140px" @change="load">
          <el-option v-for="c in chems" :key="c" :label="c" :value="c" />
        </el-select>
        <span class="spacer"></span>
        <el-button type="primary" :disabled="!canDose" @click="openCreate">
          {{ canDose ? '登记加药' : '仅运行工/管理员可加药' }}
        </el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="chemName" label="药剂" width="120">
          <template #default="{ row }"><el-tag effect="plain">{{ row.chemName }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="doseMgPerL" label="投加量(mg/L)" width="130" />
        <el-table-column prop="targetTank" label="目标池" min-width="130" />
        <el-table-column prop="dosedAt" label="投加时间" min-width="170" />
        <el-table-column prop="note" label="备注" min-width="120" />
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="登记加药" width="560px">
      <el-form :model="form" label-width="120px" class="dialog-form">
        <el-form-item label="药剂">
          <el-select v-model="form.chemName" style="width: 100%">
            <el-option v-for="c in chems" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="投加量(mg/L)"><el-input v-model="form.doseMgPerL" type="number" step="0.1" /></el-form-item>
        <el-form-item label="目标池" class="full"><el-input v-model="form.targetTank" placeholder="如 沉淀池-1 / 清水池" /></el-form-item>
        <el-form-item label="投加时间" class="full">
          <el-date-picker v-model="form.dosedAt" type="datetime" style="width: 100%"
            value-format="YYYY-MM-DDTHH:mm:ss" placeholder="留空取当前时间" />
        </el-form-item>
        <el-form-item label="备注" class="full"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>
