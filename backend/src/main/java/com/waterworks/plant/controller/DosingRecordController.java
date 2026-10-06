package com.waterworks.plant.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.waterworks.plant.common.ApiResponse;
import com.waterworks.plant.dto.DosingRecordDetail;
import com.waterworks.plant.dto.DosingRecordSummary;
import com.waterworks.plant.service.DosingRecordService;

/**
 * 加药批次登记与异常复核接口：待确认 → 已确认 / 异常待复核 → 已确认 / 已退回。
 *
 * <p>每个写操作都携带 role（运行工 / 化验员 / 管理员），后端按与页面按钮一致的口径校验：
 * 登记 / 维护 = 运行工、管理员（维护限本人 + 待确认）；确认 / 标异常 / 复核 = 管理员。</p>
 */
@RestController
@RequestMapping("/api/dosing-records")
public class DosingRecordController {

    private final DosingRecordService service;

    public DosingRecordController(DosingRecordService service) {
        this.service = service;
    }

    // ---------------- 请求体 ----------------

    /** 登记 / 修改：药剂、投加量、目标工段、执行时间、操作者均为必填 */
    public record RegisterReq(String chemName, BigDecimal doseMgPerL, String targetTank,
                              LocalDateTime dosedAt, String operator, String note, String role) {
    }

    public record OperatorReq(String operator, String role) {
    }

    /** 确认：现场回执核对说明必填 */
    public record ConfirmReq(String receiptNote, String operator, String role) {
    }

    /** 标异常 / 复核退回：原因必填；复核确认：结论必填（复用 reason 字段） */
    public record ReasonReq(String reason, String operator, String role) {
    }

    // ---------------- 查询 ----------------

    /** 列表：按目标工段 / 状态 / 执行时间范围（from 含、to 不含）追溯 */
    @GetMapping
    public ApiResponse<List<DosingRecordSummary>> list(@RequestParam(required = false) String targetTank,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        return ApiResponse.success(service.list(targetTank, status, from, to));
    }

    /** 详情：原始登记快照 + 复核结论 + 完整操作记录 */
    @GetMapping("/{id}")
    public ApiResponse<DosingRecordDetail> get(@PathVariable Long id) {
        return ApiResponse.success(service.getDetail(id));
    }

    // ---------------- 登记与维护 ----------------

    /** 登记批次：同一「目标工段 + 执行时间」只保留一个有效批次，重复提交返回已存在批次及状态 */
    @PostMapping
    public ApiResponse<DosingRecordDetail> register(@RequestBody RegisterReq req) {
        return ApiResponse.success("加药批次已登记，待管理员核对现场回执",
                service.register(req.chemName(), req.doseMgPerL(), req.targetTank(),
                        req.dosedAt(), req.operator(), req.note(), req.role()));
    }

    /** 修改：仅登记人本人且批次仍为「待确认」，修改内容留痕 */
    @PutMapping("/{id}")
    public ApiResponse<DosingRecordDetail> edit(@PathVariable Long id, @RequestBody RegisterReq req) {
        return ApiResponse.success("批次已更新",
                service.edit(id, req.chemName(), req.doseMgPerL(), req.targetTank(),
                        req.dosedAt(), req.operator(), req.note(), req.role()));
    }

    /** 撤回：仅登记人本人且批次仍为「待确认」，槽位释放、记录保留 */
    @PostMapping("/{id}/withdraw")
    public ApiResponse<DosingRecordDetail> withdraw(@PathVariable Long id, @RequestBody OperatorReq req) {
        return ApiResponse.success("批次已撤回", service.withdraw(id, req.operator(), req.role()));
    }

    // ---------------- 确认与复核（管理员） ----------------

    /** 待确认 → 已确认：管理员核对现场回执 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<DosingRecordDetail> confirm(@PathVariable Long id, @RequestBody ConfirmReq req) {
        return ApiResponse.success("批次已确认",
                service.confirm(id, req.receiptNote(), req.operator(), req.role()));
    }

    /** 待确认 → 异常待复核：发现投加量 / 药剂 / 目标工段异常 */
    @PostMapping("/{id}/flag")
    public ApiResponse<DosingRecordDetail> flag(@PathVariable Long id, @RequestBody ReasonReq req) {
        return ApiResponse.success("已转为异常待复核",
                service.flag(id, req.reason(), req.operator(), req.role()));
    }

    /** 异常待复核 → 已确认：复核通过 */
    @PostMapping("/{id}/review-confirm")
    public ApiResponse<DosingRecordDetail> reviewConfirm(@PathVariable Long id, @RequestBody ReasonReq req) {
        return ApiResponse.success("复核通过，批次已确认",
                service.reviewConfirm(id, req.reason(), req.operator(), req.role()));
    }

    /** 异常待复核 → 已退回：保留原始登记并记录退回原因，不覆盖成另一条记录 */
    @PostMapping("/{id}/review-return")
    public ApiResponse<DosingRecordDetail> reviewReturn(@PathVariable Long id, @RequestBody ReasonReq req) {
        return ApiResponse.success("已退回，原始登记保留",
                service.reviewReturn(id, req.reason(), req.operator(), req.role()));
    }
}
