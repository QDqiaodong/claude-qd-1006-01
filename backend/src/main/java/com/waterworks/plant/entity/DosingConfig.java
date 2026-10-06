package com.waterworks.plant.entity;

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
 * 加药工艺配置：当前可登记的药剂 / 目标工段清单，由管理员维护（启停 / 新增）。
 *
 * <p>登记批次时药剂与目标工段必须都在启用清单内；配置调整不回溯改写历史批次，
 * 历史记录读取时对比当前配置，失效项标「配置已变更」。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "dosing_config")
public class DosingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置类型：药剂 / 工段 */
    @Column(name = "config_type", nullable = false, length = 8)
    private String configType;

    @Column(name = "config_value", nullable = false, length = 32)
    private String configValue;

    /** 是否启用：停用后新登记不可再选，历史记录不受影响 */
    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
