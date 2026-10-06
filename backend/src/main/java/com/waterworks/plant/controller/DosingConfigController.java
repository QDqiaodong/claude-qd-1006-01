package com.waterworks.plant.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.waterworks.plant.common.ApiResponse;
import com.waterworks.plant.entity.DosingConfig;
import com.waterworks.plant.service.DosingRecordService;

/**
 * 加药工艺配置接口：当前可登记的药剂 / 目标工段清单。
 *
 * <p>调整（新增 / 启停）仅管理员；调整只影响后续登记的校验，
 * 历史批次保持登记时取值并标「配置已变更」，不回溯改写。</p>
 */
@RestController
@RequestMapping("/api/dosing-config")
public class DosingConfigController {

    private final DosingRecordService service;

    public DosingConfigController(DosingRecordService service) {
        this.service = service;
    }

    /** 配置调整：configType 药剂 / 工段；已存在则更新启停状态，不存在则新增 */
    public record SaveReq(String configType, String configValue, Boolean active,
                          String operator, String role) {
    }

    @GetMapping
    public ApiResponse<List<DosingConfig>> list() {
        return ApiResponse.success(service.listConfig());
    }

    @PostMapping("/save")
    public ApiResponse<DosingConfig> save(@RequestBody SaveReq req) {
        return ApiResponse.success("工艺配置已保存（历史批次保持登记时取值，不回溯改写）",
                service.saveConfig(req.configType(), req.configValue(), req.active(),
                        req.operator(), req.role()));
    }
}
