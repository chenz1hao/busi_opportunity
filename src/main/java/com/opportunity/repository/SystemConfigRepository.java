package com.opportunity.repository;

import com.opportunity.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {

    /** 根据配置键查询 */
    Optional<SystemConfig> findByConfigKey(String configKey);
}
