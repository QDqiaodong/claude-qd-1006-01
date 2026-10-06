package com.waterworks.plant.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 加药批次记录：运行工按投加批次登记，状态机
 * 待确认 → 已确认 / 异常待复核 → 已确认 / 已退回；待确认 → 已撤回。
 *
 * <p>业务字段（药剂 / 投加量 / 目标工段 / 执行时间 / 操作者）是登记时的历史快照：
 * 仅「待确认」状态允许登记人本人修改；进入终态后永不改写。
 * 工艺配置调整后历史记录保持原值，读取时由服务层对比当前配置标出「配置已变更」。</p>
 *
 * <p>「目标工段 + 执行时间」唯一槽位由数据库生成列 slot_tank / slot_time +
 * 唯一索引 uk_dr_slot 保证（已退回 / 已撤回释放槽位），本类不映射生成列。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "dosing_record")
public class DosingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批次号：PB + 登记日期 + '-' + 序号，插入后由服务层补齐 */
    @Column(name = "batch_no", unique = true, length = 32)
    private String batchNo;

    /** 药剂名：登记时必须属于当前工艺配置（dosing_config 启用药剂） */
    @Column(name = "chem_name", nullable = false, length = 32)
    private String chemName;

    /** 投加量（mg/L） */
    @Column(name = "dose_mg_per_l", nullable = false, precision = 8, scale = 2)
    private BigDecimal doseMgPerL;

    /** 目标工段：登记时必须属于当前工艺配置（dosing_config 启用工段） */
    @Column(name = "target_tank", nullable = false, length = 32)
    private String targetTank;

    /** 执行时间（投加批次的现场执行时间，必填） */
    @Column(name = "dosed_at", nullable = false)
    private LocalDateTime dosedAt;

    /** 操作者（登记人）：仅本人可维护尚未确认的批次 */
    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    @Column(name = "note", length = 255)
    private String note;

    /** 状态：待确认 / 已确认 / 异常待复核 / 已退回 / 已撤回 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 最近一次复核结论 / 异常说明 / 退回原因 / 回执核对说明 */
    @Column(name = "review_note", length = 255)
    private String reviewNote;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
