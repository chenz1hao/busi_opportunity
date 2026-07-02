package com.opportunity.repository;

import com.opportunity.entity.WebPageInfo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WebPageInfoRepository extends JpaRepository<WebPageInfo, Long> {

    /** 查询所有已计算 SimHash 的正文指纹（用于内容级去重，跳过历史无指纹记录） */
    @Query("SELECT w.simHash FROM WebPageInfo w WHERE w.simHash IS NOT NULL")
    List<Long> findAllSimHashes();

    /** 查询评分和开始分析时间均为空的记录 */
    @Query("SELECT w FROM WebPageInfo w WHERE w.score IS NULL AND w.analysisStartTime IS NULL")
    List<WebPageInfo> findUnscoredRecords();

    /** 分页查询评分和开始分析时间均为空的记录（按批次处理用） */
    @Query("SELECT w FROM WebPageInfo w WHERE w.score IS NULL AND w.analysisStartTime IS NULL ORDER BY w.id ASC")
    List<WebPageInfo> findUnscoredRecordsPage(Pageable pageable);

    /** 统计未评分且未开始分析的记录数 */
    @Query("SELECT COUNT(w) FROM WebPageInfo w WHERE w.score IS NULL AND w.analysisStartTime IS NULL")
    long countUnscoredRecords();

    /** 根据URL查询（可能存在历史重复数据，返回 List 避免 NonUniqueResultException） */
    List<WebPageInfo> findByUrl(String url);

    /** 根据来源城市查询 */
    List<WebPageInfo> findBySourceCity(String sourceCity);

    /** 根据评分范围查询 */
    List<WebPageInfo> findByScoreBetween(Integer minScore, Integer maxScore);

    /** 统计总数 */
    long count();

    /** 统计已评分数量 */
    long countByScoreIsNotNull();

    /** 按城市统计 */
    @Query("SELECT w.sourceCity, COUNT(w) FROM WebPageInfo w GROUP BY w.sourceCity")
    List<Object[]> countByCity();

    /** 查询线索中实际存在的去重城市列表（用于前端筛选下拉，按城市名排序） */
    @Query("SELECT DISTINCT w.sourceCity FROM WebPageInfo w WHERE w.sourceCity IS NOT NULL AND w.sourceCity <> '' ORDER BY w.sourceCity")
    List<String> findDistinctCities();

    /** 按类型统计 */
    @Query("SELECT w.type, COUNT(w) FROM WebPageInfo w WHERE w.type IS NOT NULL GROUP BY w.type")
    List<Object[]> countByType();

    /** 按日期范围查询 */
    @Query("SELECT w FROM WebPageInfo w WHERE w.createdTime BETWEEN :start AND :end ORDER BY w.createdTime DESC")
    List<WebPageInfo> findByDateRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 分页查询并排序 */
    @Query("SELECT w FROM WebPageInfo w ORDER BY w.createdTime DESC")
    List<WebPageInfo> findRecentRecords();

    /** 根据ID列表更新分析开始时间 */
    @Query("UPDATE WebPageInfo w SET w.analysisStartTime = :time WHERE w.id IN :ids")
    void updateAnalysisStartTime(@Param("ids") List<Long> ids, @Param("time") LocalDateTime time);
}
