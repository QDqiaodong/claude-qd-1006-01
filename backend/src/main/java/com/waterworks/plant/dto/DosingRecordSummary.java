package com.waterworks.plant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 加药批次列表项：登记快照 + 复核信息 + 与当前工艺配置的对比结果。
 *
 * <p>chemActive / targetActive 为 false 表示登记时的药剂 / 目标工段已不在当前工艺配置中
 * （配置在登记后发生调整）；历史记录保持登记时取值，仅标注「配置已变更」，不回溯改写。</p>
 */
@Getter
@Setter
public class DosingRecordSummary {

    private Long id;
    private String batchNo;
    private String chemName;
    private BigDecimal doseMgPerL;
    private String targetTank;
    private LocalDateTime dosedAt;
    private String operatorName;
    private String note;
    private String status;
    private String reviewNote;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private Boolean chemActive;
    private Boolean targetActive;
    private Boolean configChanged;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
