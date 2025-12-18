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

public class ResubmitSchedulerTest {

    @Test
    public void resubmit_claim_then_send_then_update_hash() {
        TxMgrProperties props = new TxMgrProperties();
        props.setResubmitInterval(Duration.ofSeconds(30));
        props.setResubmitMaxAttempts(5);
        props.setPendingBacklogThreshold(1);
        props.setPendingOldestAgeThreshold(Duration.ofMinutes(5));

        ManagedTxMapper txMapper = mock(ManagedTxMapper.class);
        LeaseManager leaseManager = mock(LeaseManager.class);
        ChainConnector chain = mock(ChainConnector.class);
        StuckResolutionService stuckService = mock(StuckResolutionService.class);
        TxMgrMetrics metrics = mock(TxMgrMetrics.class);

        ManagedTxEntity tx = new ManagedTxEntity();
        UUID txId = UUID.randomUUID();
        tx.setTxId(txId);
        tx.setSubmitter("s1");
        tx.setNonce(1L);
        tx.setPayload("{\"p\":1}");
        tx.setTxHash("h0");
        tx.setState("TRACKING");
        tx.setSubmitAttempts(0);

        when(txMapper.listDueResubmits(anyInt())).thenReturn(Collections.singletonList(tx));
        when(leaseManager.acquireOrRenew(eq("s1"))).thenReturn(new LeaseDecision(true, 3L, Instant.now().plusSeconds(5)));
        when(leaseManager.getNodeId()).thenReturn("nodeA");

        when(txMapper.updateNextResubmitAtFenced(eq(txId), eq("s1"), any(Instant.class), isNull(), eq("nodeA"), eq(3L), any(Instant.class)))
                .thenReturn(1);
        when(chain.sendTransaction(eq("s1"), eq(1L), anyString())).thenReturn("h1");

        ResubmitScheduler s = new ResubmitScheduler(props, txMapper, leaseManager, chain, stuckService, metrics);
        s.scanAndResubmit();

        verify(txMapper, times(1)).updateNextResubmitAtFenced(eq(txId), eq("s1"), any(Instant.class), isNull(), eq("nodeA"), eq(3L), any(Instant.class));
        verify(chain, times(1)).sendTransaction(eq("s1"), eq(1L), anyString());
        verify(txMapper, times(1)).updateTxHashFenced(eq(txId), eq("s1"), eq("h1"), eq("TRACKING"), any(Instant.class), eq("nodeA"), eq(3L), any(Instant.class));
    }
}


