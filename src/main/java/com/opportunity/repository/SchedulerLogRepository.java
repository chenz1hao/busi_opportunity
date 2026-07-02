package com.opportunity.repository;

import com.opportunity.entity.SchedulerLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchedulerLogRepository extends JpaRepository<SchedulerLog, Long> {

    List<SchedulerLog> findByTaskNameOrderByStartTimeDesc(String taskName);

    List<SchedulerLog> findTop10ByOrderByStartTimeDesc();

    List<SchedulerLog> findAllByOrderByStartTimeDesc();

    long countByTaskName(String taskName);

    long count();

    /** 判断指定任务名是否存在指定状态的记录（用于校验是否有未完成任务） */
    boolean existsByTaskNameAndStatus(String taskName, String status);
}