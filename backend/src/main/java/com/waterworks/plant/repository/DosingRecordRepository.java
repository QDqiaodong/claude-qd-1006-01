package com.waterworks.plant.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.waterworks.plant.entity.DosingRecord;

public interface DosingRecordRepository extends JpaRepository<DosingRecord, Long> {

    /** 悲观写锁：状态推进 / 修改 / 撤回都先锁定本行，串行化并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DosingRecord r where r.id = :id")
    Optional<DosingRecord> findByIdForUpdate(@Param("id") Long id);

    /** 槽位占用预检：同一目标工段 + 执行时间下处于占用状态（待确认 / 已确认 / 异常待复核）的批次 */
    @Query("select r from DosingRecord r where r.targetTank = :tank and r.dosedAt = :dosedAt"
            + " and r.status in :statuses order by r.id")
    List<DosingRecord> findSlotOccupants(@Param("tank") String tank,
                                         @Param("dosedAt") LocalDateTime dosedAt,
                                         @Param("statuses") List<String> statuses);

    /** 列表追溯：按目标工段 / 状态 / 执行时间范围组合筛选 */
    @Query("select r from DosingRecord r where (:tank is null or r.targetTank = :tank)"
            + " and (:status is null or r.status = :status)"
            + " and (:timeFrom is null or r.dosedAt >= :timeFrom)"
            + " and (:timeTo is null or r.dosedAt < :timeTo) order by r.id desc")
    List<DosingRecord> search(@Param("tank") String tank,
                              @Param("status") String status,
                              @Param("timeFrom") LocalDateTime timeFrom,
                              @Param("timeTo") LocalDateTime timeTo);
}
