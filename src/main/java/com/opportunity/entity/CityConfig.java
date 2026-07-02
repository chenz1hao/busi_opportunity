package com.opportunity.entity;

import java.time.LocalDateTime;

/**
 * 搜索城市配置 DTO
 * 城市配置已合并到 system_config 表的 search.cities 配置项（逗号分隔），
 * 此类仅作为前端交互的传输对象，不再映射数据库表。
 * id 为列表索引+1（仅用于前端增删改标识），enabled 固定为 true（不再支持禁用），
 * sortOrder 为列表索引。
 */
public class CityConfig {

    private Long id;

    /** 城市名称 */
    private String cityName;

    /** 是否启用（合并后固定为 true） */
    private Boolean enabled = true;

    /** 排序值（列表索引） */
    private Integer sortOrder = 0;

    /** 更新时间（取自 system_config 配置项的更新时间） */
    private LocalDateTime updatedTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
