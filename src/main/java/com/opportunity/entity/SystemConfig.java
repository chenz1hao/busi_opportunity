package com.opportunity.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 系统配置实体（键值对存储）
 */
@Entity
@Table(name = "system_config",
        uniqueConstraints = @UniqueConstraint(columnNames = "config_key"))
public class SystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配置键 */
    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    /** 配置值 */
    @Column(name = "config_value", nullable = false, length = 500)
    private String configValue;

    /** 配置描述 */
    @Column(name = "description", length = 200)
    private String description;

    /** 更新时间 */
    @Column(name = "updated_time")
    private LocalDateTime updatedTime;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }

    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
