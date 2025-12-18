package com.work.nonce.txmgr.service.stuck;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.repository.mapper.TxCompletionMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class StuckResolutionServiceTest {

    @Test
    public void default_hook_is_noop_ignore() {
        StuckResolutionHook hook = StuckResolutionHook.defaultHook();
        StuckResolutionDecision d = hook.onStuckCandidate(new ManagedTxEntity(), new StuckResolutionContext(
                Instant.now(), "s", UUID.randomUUID(), 1L, "h", "TRACKING", null, 10, 5, 0L, 0L
        ));
        // 设计口径：默认 NOOP
        org.junit.jupiter.api.Assertions.assertEquals(StuckResolutionAction.IGNORE, d.getAction());
    }

    @Test
    public void ignore_updates_next_resubmit_at_not_stuck() {
        TxMgrProperties props = new TxMgrProperties();
        props.setResubmitInterval(Duration.ofSeconds(30));

        LeaseManager leaseManager = mock(LeaseManager.class);
        when(leaseManager.acquireOrRenew(eq("s1"))).thenReturn(new LeaseDecision(true, 9L, Instant.now().plusSeconds(5)));
        when(leaseManager.getNodeId()).thenReturn("nodeA");

        ManagedTxMapper txMapper = mock(ManagedTxMapper.class);
        TxCompletionMapper completionMapper = mock(TxCompletionMapper.class);
        ChainConnector chain = mock(ChainConnector.class);
        TxMgrMetrics metrics = mock(TxMgrMetrics.class);

        StuckResolutionHook hook = StuckResolutionHook.defaultHook();
        StuckResolutionService svc = new StuckResolutionService(props, leaseManager, txMapper, completionMapper, chain, hook, metrics);

        ManagedTxEntity tx = new ManagedTxEntity();
        UUID txId = UUID.randomUUID();
        tx.setTxId(txId);
        tx.setSubmitter("s1");
        tx.setNonce(1L);
        tx.setTxHash("h1");
        tx.setState("TRACKING");
        tx.setSubmitAttempts(100);

        svc.handle(tx, 1);

        verify(txMapper, atLeastOnce()).updateNextResubmitAtFenced(eq(txId), eq("s1"), any(Instant.class), anyString(), eq("nodeA"), eq(9L), any(Instant.class));
        verify(txMapper, never()).markStuckFenced(any(), any(), any(), any(), any(), anyLong(), any());
    }
}


