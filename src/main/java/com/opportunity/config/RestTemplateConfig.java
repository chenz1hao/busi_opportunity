package com.opportunity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class RestTemplateConfig implements AsyncConfigurer {

    @Bean
    public RestTemplate restTemplate() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        return new RestTemplate(factory);
    }

    /**
     * 自定义 TaskScheduler，用于支持运行时动态 cron 调度
     * （线程池大小设为 4，避免搜索与分析任务时间重叠时互相阻塞）
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("opportunity-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * @Async 用的线程池（用于手动触发的长耗时搜索/分析任务）
     * 实现 AsyncConfigurer.getAsyncExecutor() 让 @Async 使用此池
     */
    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("opportunity-async-");
        // 由调用方线程执行任务，避免被静默丢弃；队列满时抛异常更易发现问题
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }
}