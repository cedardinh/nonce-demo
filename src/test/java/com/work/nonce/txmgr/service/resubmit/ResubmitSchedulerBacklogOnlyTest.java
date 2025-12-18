package com.work.nonce.txmgr.service.resubmit;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.service.stuck.StuckResolutionService;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ResubmitSchedulerBacklogOnlyTest {

    @Test
    public void backlog_triggers_even_when_no_due_resubmits() {
        TxMgrProperties props = new TxMgrProperties();
        props.setResubmitInterval(Duration.ofSeconds(30));
        props.setPendingBacklogThreshold(1);
        props.setPendingOldestAgeThreshold(Duration.ofMinutes(5));

        ManagedTxMapper txMapper = mock(ManagedTxMapper.class);
        when(txMapper.listDueResubmits(anyInt())).thenReturn(Collections.emptyList());
        when(txMapper.listSubmittersWithTrackingPending(anyInt())).thenReturn(Collections.singletonList("s1"));
        when(txMapper.countTrackingPending(eq("s1"))).thenReturn(10L);

        ManagedTxEntity oldest = new ManagedTxEntity();
        UUID txId = UUID.randomUUID();
        oldest.setTxId(txId);
        oldest.setSubmitter("s1");
        oldest.setNonce(1L);
        oldest.setPayload("{\"p\":1}");
        oldest.setTxHash("h0");
        oldest.setState("TRACKING");
        when(txMapper.selectOldestTrackingPending(eq("s1"))).thenReturn(oldest);

        LeaseManager leaseManager = mock(LeaseManager.class);
        when(leaseManager.acquireOrRenew(eq("s1"))).thenReturn(new LeaseDecision(true, 3L, Instant.now().plusSeconds(5)));
        when(leaseManager.getNodeId()).thenReturn("nodeA");

        ChainConnector chain = mock(ChainConnector.class);
        when(chain.sendTransaction(eq("s1"), eq(1L), anyString())).thenReturn("h1");

        StuckResolutionService stuckService = mock(StuckResolutionService.class);
        TxMgrMetrics metrics = mock(TxMgrMetrics.class);

        when(txMapper.updateNextResubmitAtFenced(eq(txId), eq("s1"), any(Instant.class), isNull(), eq("nodeA"), eq(3L), any(Instant.class)))
                .thenReturn(1);

        ResubmitScheduler s = new ResubmitScheduler(props, txMapper, leaseManager, chain, stuckService, metrics);
        s.scanAndResubmit();

        verify(txMapper, times(1)).listSubmittersWithTrackingPending(anyInt());
        verify(chain, times(1)).sendTransaction(eq("s1"), eq(1L), anyString());
    }
}


