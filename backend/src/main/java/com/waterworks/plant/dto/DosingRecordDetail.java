package com.waterworks.plant.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 加药批次详情：原始登记快照 + 复核结论 + 完整操作记录。
 */
@Getter
@Setter
public class DosingRecordDetail extends DosingRecordSummary {

    private List<LogItem> logs;

    /** 操作记录视图 */
    @Getter
    @Setter
    public static class LogItem {
        private Long id;
        private String action;
        private String fromStatus;
        private String toStatus;
        private String operatorName;
        private String detail;
        private LocalDateTime createdAt;
    }
}
