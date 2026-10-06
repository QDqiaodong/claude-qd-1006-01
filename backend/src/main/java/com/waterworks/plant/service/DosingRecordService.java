package com.waterworks.plant.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.waterworks.plant.common.BizException;
import com.waterworks.plant.common.Dict;
import com.waterworks.plant.dto.DosingRecordDetail;
import com.waterworks.plant.dto.DosingRecordSummary;
import com.waterworks.plant.entity.DosingConfig;
import com.waterworks.plant.entity.DosingRecord;
import com.waterworks.plant.entity.DosingRecordLog;
import com.waterworks.plant.repository.DosingConfigRepository;
import com.waterworks.plant.repository.DosingRecordLogRepository;
import com.waterworks.plant.repository.DosingRecordRepository;

/**
 * 加药批次登记与异常复核：待确认 → 已确认 / 异常待复核 → 已确认 / 已退回；待确认 → 已撤回。
 *
 * <p>一致性全部在后端保证：</p>
 * <ul>
 *   <li>完整性与配置 —— 药剂 / 投加量 / 目标工段 / 执行时间 / 操作者提交前必须填写完整；
 *       药剂与目标工段必须在当前工艺配置（dosing_config 启用项）内，否则不能登记为有效记录。</li>
 *   <li>并发唯一 —— 「目标工段 + 执行时间」由 slot 生成列 + 唯一索引 uk_dr_slot 兜底，
 *       事务内预检给出友好提示；重复点击 / 网络重试 / 两人同时登记，第二个请求命中唯一约束后
 *       转成「已存在批次 + 当前状态」的明确提示，不会分叉成一条待复核、一条已确认。</li>
 *   <li>状态约束 —— 所有状态推进先对记录行加悲观写锁再校验；已确认 / 已退回 / 已撤回为终态，
 *       业务字段不再改写。复核退回保留原始登记并记录原因，不覆盖成另一条正常记录。</li>
 *   <li>权限 —— 登记 / 维护：运行工、管理员；维护仅限本人提交且「待确认」的批次；
 *       确认 / 标异常 / 复核 / 工艺配置调整：仅管理员。与页面按钮口径一致。</li>
 *   <li>历史 —— 工艺配置调整不回溯改写历史批次；读取时对比当前配置，
 *       失效的药剂 / 工段标「配置已变更」。</li>
 * </ul>
 */
@Service
public class DosingRecordService {

    private final DosingRecordRepository recordRepository;
    private final DosingRecordLogRepository logRepository;
    private final DosingConfigRepository configRepository;
    private final TransactionTemplate txTemplate;

    public DosingRecordService(DosingRecordRepository recordRepository,
                               DosingRecordLogRepository logRepository,
                               DosingConfigRepository configRepository,
                               PlatformTransactionManager txManager) {
        this.recordRepository = recordRepository;
        this.logRepository = logRepository;
        this.configRepository = configRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ==================== 查询 ====================

    /** 列表：按目标工段 / 状态 / 执行时间范围追溯，附当前工艺配置对比结果 */
    public List<DosingRecordSummary> list(String targetTank, String status, String from, String to) {
        if (status != null && !status.isBlank() && !Dict.isValidDosingRecordStatus(status)) {
            throw new BizException("未知的批次状态：" + status);
        }
        List<DosingRecord> records = recordRepository.search(
                blankToNull(targetTank), blankToNull(status), parseFrom(from), parseTo(to));
        ConfigSnapshot config = configSnapshot();
        return records.stream().map(r -> toSummary(r, config)).toList();
    }

    /** 详情：原始登记快照 + 复核结论 + 完整操作记录 */
    public DosingRecordDetail getDetail(Long id) {
        DosingRecord r = recordRepository.findById(id)
                .orElseThrow(() -> new BizException("加药批次不存在：" + id));
        return buildDetail(r);
    }

    public List<DosingConfig> listConfig() {
        return configRepository.findAllByOrderByConfigTypeAscIdAsc();
    }

    // ==================== 登记（幂等） ====================

    /**
     * 登记加药批次。同一「目标工段 + 执行时间」只保留一个有效批次：
     * 先预检，事务内再预检，唯一索引兜底；命中冲突时返回已存在批次及其当前状态。
     */
    public DosingRecordDetail register(String chemName, BigDecimal doseMgPerL, String targetTank,
                                       LocalDateTime dosedAt, String operator, String note, String role) {
        requireRegisterRole(role);
        requireComplete(chemName, doseMgPerL, targetTank, dosedAt, operator);
        String chem = chemName.trim();
        String target = targetTank.trim();
        String op = operator.trim();
        requireInConfig(chem, target);
        // 预检（读已提交）：友好提示优先于唯一约束报错
        var occupants = recordRepository.findSlotOccupants(target, dosedAt, Dict.DR_SLOT_ACTIVE);
        if (!occupants.isEmpty()) {
            throw slotTaken(occupants.get(0));
        }
        Long id;
        try {
            id = txTemplate.execute(status -> {
                // 事务内再预检一次
                var again = recordRepository.findSlotOccupants(target, dosedAt, Dict.DR_SLOT_ACTIVE);
                if (!again.isEmpty()) {
                    throw slotTaken(again.get(0));
                }
                LocalDateTime now = LocalDateTime.now();
                DosingRecord r = new DosingRecord();
                r.setChemName(chem);
                r.setDoseMgPerL(doseMgPerL);
                r.setTargetTank(target);
                r.setDosedAt(dosedAt);
                r.setOperatorName(op);
                r.setNote(blankToNull(note));
                r.setStatus(Dict.DR_PENDING);
                r.setCreatedAt(now);
                r.setUpdatedAt(now);
                // saveAndFlush：uk_dr_slot 唯一约束在事务内即刻校验，而不是拖到提交时
                recordRepository.saveAndFlush(r);
                r.setBatchNo("PB" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                        + "-" + String.format("%04d", r.getId()));
                recordRepository.save(r);
                addLog(r.getId(), "登记", null, Dict.DR_PENDING, op,
                        chem + " " + doseMgPerL + " mg/L → " + target);
                return r.getId();
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                // 并发兜底：另一运行工 / 重复提交已抢先占用该槽位
                var existing = recordRepository.findSlotOccupants(target, dosedAt, Dict.DR_SLOT_ACTIVE);
                if (!existing.isEmpty()) {
                    throw slotTaken(existing.get(0));
                }
                throw new BizException("该目标工段 + 执行时间已存在加药批次，请勿重复登记");
            }
            throw e;
        }
        return getDetail(id);
    }

    // ==================== 维护（本人 + 待确认） ====================

    /** 修改：仅登记人本人且批次仍为「待确认」；修改内容留痕，状态保持「待确认」 */
    public DosingRecordDetail edit(Long id, String chemName, BigDecimal doseMgPerL, String targetTank,
                                   LocalDateTime dosedAt, String operator, String note, String role) {
        requireRegisterRole(role);
        requireComplete(chemName, doseMgPerL, targetTank, dosedAt, operator);
        String chem = chemName.trim();
        String target = targetTank.trim();
        String op = operator.trim();
        requireInConfig(chem, target);
        try {
            return txTemplate.execute(status -> {
                DosingRecord r = lockRecord(id);
                requirePending(r, "修改");
                requireOwner(r, op);
                boolean slotChanged = !r.getTargetTank().equals(target) || !r.getDosedAt().equals(dosedAt);
                if (slotChanged) {
                    var occupants = recordRepository.findSlotOccupants(target, dosedAt, Dict.DR_SLOT_ACTIVE);
                    if (!occupants.isEmpty()) {
                        throw slotTaken(occupants.get(0));
                    }
                }
                String diff = buildDiff(r, chem, doseMgPerL, target, dosedAt, note);
                LocalDateTime now = LocalDateTime.now();
                r.setChemName(chem);
                r.setDoseMgPerL(doseMgPerL);
                r.setTargetTank(target);
                r.setDosedAt(dosedAt);
                r.setNote(blankToNull(note));
                r.setUpdatedAt(now);
                recordRepository.saveAndFlush(r);
                addLog(r.getId(), "修改", Dict.DR_PENDING, Dict.DR_PENDING, op,
                        diff.isEmpty() ? "内容无变化" : diff);
                return buildDetail(r);
            });
        } catch (RuntimeException e) {
            if (isUniqueViolation(e)) {
                var existing = recordRepository.findSlotOccupants(target, dosedAt, Dict.DR_SLOT_ACTIVE);
                if (!existing.isEmpty()) {
                    throw slotTaken(existing.get(0));
                }
                throw new BizException("该目标工段 + 执行时间已存在加药批次，请调整后再提交");
            }
            throw e;
        }
    }

    /** 撤回：仅登记人本人且批次仍为「待确认」；撤回后槽位释放，记录保留可追溯 */
    public DosingRecordDetail withdraw(Long id, String operator, String role) {
        requireRegisterRole(role);
        requireOperator(operator);
        String op = operator.trim();
        return txTemplate.execute(status -> {
            DosingRecord r = lockRecord(id);
            requirePending(r, "撤回");
            requireOwner(r, op);
            r.setStatus(Dict.DR_WITHDRAWN);
            r.setUpdatedAt(LocalDateTime.now());
            recordRepository.save(r);
            addLog(r.getId(), "撤回", Dict.DR_PENDING, Dict.DR_WITHDRAWN, op, "登记人撤回，批次槽位释放");
            return buildDetail(r);
        });
    }

    // ==================== 确认与复核（管理员） ====================

    /** 待确认 → 已确认：管理员核对现场回执后方可确认，回执核对说明必填 */
    public DosingRecordDetail confirm(Long id, String receiptNote, String operator, String role) {
        requireAdmin(role);
        requireOperator(operator);
        if (receiptNote == null || receiptNote.isBlank()) {
            throw new BizException("确认前须核对现场回执并填写核对说明");
        }
        String op = operator.trim();
        return txTemplate.execute(status -> {
            DosingRecord r = lockRecord(id);
            requirePending(r, "确认");
            LocalDateTime now = LocalDateTime.now();
            r.setStatus(Dict.DR_CONFIRMED);
            r.setReviewNote(receiptNote.trim());
            r.setReviewedBy(op);
            r.setReviewedAt(now);
            r.setUpdatedAt(now);
            recordRepository.save(r);
            addLog(r.getId(), "确认", Dict.DR_PENDING, Dict.DR_CONFIRMED, op,
                    "现场回执核对：" + receiptNote.trim());
            return buildDetail(r);
        });
    }

    /** 待确认 → 异常待复核：管理员发现投加量 / 药剂 / 目标工段异常，异常说明必填 */
    public DosingRecordDetail flag(Long id, String reason, String operator, String role) {
        requireAdmin(role);
        requireOperator(operator);
        if (reason == null || reason.isBlank()) {
            throw new BizException("标记异常必须填写异常说明（投加量 / 药剂 / 目标工段）");
        }
        String op = operator.trim();
        return txTemplate.execute(status -> {
            DosingRecord r = lockRecord(id);
            requirePending(r, "标记异常");
            LocalDateTime now = LocalDateTime.now();
            r.setStatus(Dict.DR_REVIEW);
            r.setReviewNote(reason.trim());
            r.setReviewedBy(op);
            r.setReviewedAt(now);
            r.setUpdatedAt(now);
            recordRepository.save(r);
            addLog(r.getId(), "标异常", Dict.DR_PENDING, Dict.DR_REVIEW, op, reason.trim());
            return buildDetail(r);
        });
    }

    /** 异常待复核 → 已确认：管理员复核通过，复核结论必填 */
    public DosingRecordDetail reviewConfirm(Long id, String conclusion, String operator, String role) {
        requireAdmin(role);
        requireOperator(operator);
        if (conclusion == null || conclusion.isBlank()) {
            throw new BizException("复核确认必须填写复核结论");
        }
        String op = operator.trim();
        return txTemplate.execute(status -> {
            DosingRecord r = lockRecord(id);
            requireReview(r, "复核确认");
            LocalDateTime now = LocalDateTime.now();
            r.setStatus(Dict.DR_CONFIRMED);
            r.setReviewNote(conclusion.trim());
            r.setReviewedBy(op);
            r.setReviewedAt(now);
            r.setUpdatedAt(now);
            recordRepository.save(r);
            addLog(r.getId(), "复核确认", Dict.DR_REVIEW, Dict.DR_CONFIRMED, op, conclusion.trim());
            return buildDetail(r);
        });
    }

    /**
     * 异常待复核 → 已退回：管理员复核退回，退回原因必填。
     * 原始登记（药剂 / 投加量 / 工段 / 执行时间 / 操作者）保留不改写，槽位释放，
     * 运行工可另行登记新批次，本记录作为历史保留。
     */
    public DosingRecordDetail reviewReturn(Long id, String reason, String operator, String role) {
        requireAdmin(role);
        requireOperator(operator);
        if (reason == null || reason.isBlank()) {
            throw new BizException("复核退回必须填写退回原因");
        }
        String op = operator.trim();
        return txTemplate.execute(status -> {
            DosingRecord r = lockRecord(id);
            requireReview(r, "复核退回");
            LocalDateTime now = LocalDateTime.now();
            r.setStatus(Dict.DR_RETURNED);
            r.setReviewNote(reason.trim());
            r.setReviewedBy(op);
            r.setReviewedAt(now);
            r.setUpdatedAt(now);
            recordRepository.save(r);
            addLog(r.getId(), "复核退回", Dict.DR_REVIEW, Dict.DR_RETURNED, op,
                    reason.trim() + "；原始登记保留，批次槽位释放");
            return buildDetail(r);
        });
    }

    // ==================== 工艺配置（管理员） ====================

    /**
     * 新增或启停配置项。停用 / 启用只影响后续登记的校验，
     * 历史批次保持登记时取值，读取时标「配置已变更」，不回溯改写。
     */
    public DosingConfig saveConfig(String configType, String configValue, Boolean active,
                                   String operator, String role) {
        requireAdmin(role);
        requireOperator(operator);
        if (!Dict.CONFIG_CHEM.equals(configType) && !Dict.CONFIG_TARGET.equals(configType)) {
            throw new BizException("配置类型必须为 药剂 / 工段");
        }
        if (configValue == null || configValue.isBlank()) {
            throw new BizException("配置值不能为空");
        }
        String value = configValue.trim();
        LocalDateTime now = LocalDateTime.now();
        var existing = configRepository.findByConfigTypeAndConfigValue(configType, value);
        if (existing.isPresent()) {
            DosingConfig c = existing.get();
            c.setActive(active == null || active);
            c.setUpdatedBy(operator.trim());
            c.setUpdatedAt(now);
            return configRepository.save(c);
        }
        DosingConfig c = new DosingConfig();
        c.setConfigType(configType);
        c.setConfigValue(value);
        c.setActive(active == null || active);
        c.setUpdatedBy(operator.trim());
        c.setUpdatedAt(now);
        return configRepository.save(c);
    }

    // ==================== 内部工具 ====================

    private DosingRecord lockRecord(Long id) {
        return recordRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("加药批次不存在：" + id));
    }

    /** 登记 / 维护权限：运行工、管理员（与页面按钮口径一致） */
    private void requireRegisterRole(String role) {
        if (!Dict.canRegisterDosing(role)) {
            throw new BizException("仅运行工 / 管理员可登记或维护加药批次");
        }
    }

    /** 确认 / 标异常 / 复核 / 配置调整权限：仅管理员（与页面按钮口径一致） */
    private void requireAdmin(String role) {
        if (!Dict.ROLE_ADMIN.equals(role)) {
            throw new BizException("仅管理员可执行确认 / 复核 / 配置调整操作");
        }
    }

    private void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("操作人不能为空");
        }
    }

    /** 提交前必须填写完整：药剂 / 投加量 / 目标工段 / 执行时间 / 操作者 */
    private void requireComplete(String chemName, BigDecimal doseMgPerL, String targetTank,
                                 LocalDateTime dosedAt, String operator) {
        if (chemName == null || chemName.isBlank()) {
            throw new BizException("药剂不能为空");
        }
        if (doseMgPerL == null || doseMgPerL.signum() <= 0) {
            throw new BizException("投加量必须大于 0（mg/L）");
        }
        if (targetTank == null || targetTank.isBlank()) {
            throw new BizException("目标工段不能为空");
        }
        if (dosedAt == null) {
            throw new BizException("执行时间不能为空");
        }
        if (operator == null || operator.isBlank()) {
            throw new BizException("操作者不能为空");
        }
    }

    /** 药剂与目标工段必须在当前工艺配置（启用项）内，否则不能登记为有效记录 */
    private void requireInConfig(String chemName, String targetTank) {
        if (!activeValues(Dict.CONFIG_CHEM).contains(chemName)) {
            throw new BizException("药剂「" + chemName + "」不在当前工艺配置中，不能登记为有效记录");
        }
        if (!activeValues(Dict.CONFIG_TARGET).contains(targetTank)) {
            throw new BizException("目标工段「" + targetTank + "」不在当前工艺配置中，不能登记为有效记录");
        }
    }

    private Set<String> activeValues(String configType) {
        return configRepository.findByConfigTypeAndActiveTrue(configType).stream()
                .map(DosingConfig::getConfigValue)
                .collect(Collectors.toSet());
    }

    private void requirePending(DosingRecord r, String action) {
        if (!Dict.DR_PENDING.equals(r.getStatus())) {
            throw new BizException("当前状态「" + r.getStatus() + "」不允许" + action
                    + "，仅「待确认」的批次可" + action);
        }
    }

    private void requireReview(DosingRecord r, String action) {
        if (!Dict.DR_REVIEW.equals(r.getStatus())) {
            throw new BizException("当前状态「" + r.getStatus() + "」不允许" + action
                    + "，仅「异常待复核」的批次可" + action);
        }
    }

    /** 运行工只能维护本人提交的批次 */
    private void requireOwner(DosingRecord r, String operator) {
        if (!r.getOperatorName().equals(operator)) {
            throw new BizException("只能维护本人提交的批次（本批次操作者：" + r.getOperatorName() + "）");
        }
    }

    /** 槽位冲突提示：失败方能看到已存在批次及其当前状态 */
    private BizException slotTaken(DosingRecord r) {
        return new BizException("该目标工段 + 执行时间已存在加药批次 " + r.getBatchNo()
                + "（状态：" + r.getStatus() + "，操作者：" + r.getOperatorName() + "），请勿重复登记");
    }

    /** 唯一约束冲突识别：本流程中可能命中的只有 uk_dr_slot / batch_no 两个唯一键 */
    private boolean isUniqueViolation(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof DataIntegrityViolationException
                    || cur instanceof org.hibernate.exception.ConstraintViolationException
                    || cur instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private String buildDiff(DosingRecord r, String chem, BigDecimal dose, String target,
                             LocalDateTime dosedAt, String note) {
        StringBuilder sb = new StringBuilder();
        if (!r.getChemName().equals(chem)) {
            sb.append("药剂 ").append(r.getChemName()).append(" → ").append(chem).append("；");
        }
        if (r.getDoseMgPerL().compareTo(dose) != 0) {
            sb.append("投加量 ").append(r.getDoseMgPerL()).append(" → ").append(dose).append(" mg/L；");
        }
        if (!r.getTargetTank().equals(target)) {
            sb.append("目标工段 ").append(r.getTargetTank()).append(" → ").append(target).append("；");
        }
        if (!r.getDosedAt().equals(dosedAt)) {
            sb.append("执行时间 ").append(r.getDosedAt()).append(" → ").append(dosedAt).append("；");
        }
        String newNote = blankToNull(note);
        if (newNote == null ? r.getNote() != null : !newNote.equals(r.getNote())) {
            sb.append("备注已更新；");
        }
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }

    private void addLog(Long recordId, String action, String from, String to, String operator, String detail) {
        DosingRecordLog log = new DosingRecordLog();
        log.setRecordId(recordId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorName(operator);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.save(log);
    }

    // ==================== 视图装配 ====================

    /** 当前工艺配置快照：读取时对比，历史批次不回溯改写 */
    private record ConfigSnapshot(Set<String> chems, Set<String> targets) {
    }

    private ConfigSnapshot configSnapshot() {
        Map<String, Set<String>> byType = configRepository.findAll().stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .collect(Collectors.groupingBy(DosingConfig::getConfigType,
                        Collectors.mapping(DosingConfig::getConfigValue, Collectors.toSet())));
        return new ConfigSnapshot(
                byType.getOrDefault(Dict.CONFIG_CHEM, Set.of()),
                byType.getOrDefault(Dict.CONFIG_TARGET, Set.of()));
    }

    private DosingRecordSummary toSummary(DosingRecord r, ConfigSnapshot config) {
        DosingRecordSummary s = new DosingRecordSummary();
        fillSummary(s, r, config);
        return s;
    }

    private void fillSummary(DosingRecordSummary s, DosingRecord r, ConfigSnapshot config) {
        s.setId(r.getId());
        s.setBatchNo(r.getBatchNo());
        s.setChemName(r.getChemName());
        s.setDoseMgPerL(r.getDoseMgPerL());
        s.setTargetTank(r.getTargetTank());
        s.setDosedAt(r.getDosedAt());
        s.setOperatorName(r.getOperatorName());
        s.setNote(r.getNote());
        s.setStatus(r.getStatus());
        s.setReviewNote(r.getReviewNote());
        s.setReviewedBy(r.getReviewedBy());
        s.setReviewedAt(r.getReviewedAt());
        boolean chemActive = config.chems().contains(r.getChemName());
        boolean targetActive = config.targets().contains(r.getTargetTank());
        s.setChemActive(chemActive);
        s.setTargetActive(targetActive);
        s.setConfigChanged(!chemActive || !targetActive);
        s.setCreatedAt(r.getCreatedAt());
        s.setUpdatedAt(r.getUpdatedAt());
    }

    private DosingRecordDetail buildDetail(DosingRecord r) {
        DosingRecordDetail detail = new DosingRecordDetail();
        fillSummary(detail, r, configSnapshot());
        detail.setLogs(logRepository.findByRecordIdOrderById(r.getId()).stream().map(l -> {
            DosingRecordDetail.LogItem v = new DosingRecordDetail.LogItem();
            v.setId(l.getId());
            v.setAction(l.getAction());
            v.setFromStatus(l.getFromStatus());
            v.setToStatus(l.getToStatus());
            v.setOperatorName(l.getOperatorName());
            v.setDetail(l.getDetail());
            v.setCreatedAt(l.getCreatedAt());
            return v;
        }).toList());
        return detail;
    }

    // ==================== 参数解析 ====================

    private String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /** 时间筛选起点：日期按当天 00:00，日期时间按原值 */
    private LocalDateTime parseFrom(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        String v = from.trim();
        return v.length() <= 10 ? LocalDate.parse(v).atStartOfDay() : LocalDateTime.parse(v);
    }

    /** 时间筛选终点（开区间）：日期按次日 00:00，日期时间按原值 */
    private LocalDateTime parseTo(String to) {
        if (to == null || to.isBlank()) {
            return null;
        }
        String v = to.trim();
        return v.length() <= 10 ? LocalDate.parse(v).plusDays(1).atStartOfDay() : LocalDateTime.parse(v);
    }
}
