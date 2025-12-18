package com.work.nonce.txmgr.config;

import com.work.nonce.txmgr.support.NodeIdProvider;
import com.work.nonce.txmgr.support.SimpleNodeIdProvider;
import com.work.nonce.txmgr.service.stuck.StuckResolutionHook;
import com.work.nonce.txmgr.support.metrics.NoopTxMgrMetrics;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TxMgrProperties.class)
public class TxMgrConfiguration {

    @Bean
    public NodeIdProvider nodeIdProvider() {
        return new SimpleNodeIdProvider();
    }

    @Bean
    @ConditionalOnMissingBean(StuckResolutionHook.class)
    public StuckResolutionHook stuckResolutionHook() {
        return StuckResolutionHook.defaultHook();
    }

    @Bean
    @ConditionalOnMissingBean(TxMgrMetrics.class)
    public TxMgrMetrics txMgrMetrics() {
        return new NoopTxMgrMetrics();
    }
}


