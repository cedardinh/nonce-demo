package com.work.nonce.txmgr.service.finality;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.chain.TxReceipt;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.domain.TxState;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.repository.mapper.TxCompletionMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FinalityManagerFencedRejectTest {

    @Test
    public void fenced_reject_does_not_write_completion() {
        TxMgrProperties props = new TxMgrProperties();
        props.setFinalityConfirmations(1);

        LeaseManager leaseManager = mock(LeaseManager.class);
        ManagedTxMapper txMapper = mock(ManagedTxMapper.class);
        TxCompletionMapper completionMapper = mock(TxCompletionMapper.class);
        ChainConnector chain = mock(ChainConnector.class);
        TxMgrMetrics metrics = mock(TxMgrMetrics.class);

        ManagedTxEntity tx = new ManagedTxEntity();
        UUID txId = UUID.randomUUID();
        tx.setTxId(txId);
        tx.setSubmitter("s1");
        tx.setTxHash("h1");
        tx.setState("TRACKING");
        tx.setReceipt("{\"ok\":true}");
        tx.setConfirmedAt(null);

        when(txMapper.listTrackingWithReceipt(anyInt())).thenReturn(Collections.singletonList(tx));
        when(leaseManager.acquireOrRenew(eq("s1"))).thenReturn(new LeaseDecision(true, 7L, Instant.now().plusSeconds(5)));
        when(leaseManager.getNodeId()).thenReturn("nodeA");
        when(chain.getTransactionReceipt(eq("h1"))).thenReturn(new TxReceipt(10L, "block_10", true));
        when(chain.getBlockHashByNumber(eq(10L))).thenReturn("block_10");
        when(chain.getLatestBlockNumber()).thenReturn(10L);

        // simulate fenced rejection (e.g., lease changed)
        when(txMapper.updateFinalStateFenced(eq(txId), eq("s1"), eq(TxState.CONFIRMED.name()), any(Instant.class), eq("nodeA"), eq(7L), any(Instant.class)))
                .thenReturn(0);

        FinalityManager mgr = new FinalityManager(props, leaseManager, txMapper, completionMapper, chain, metrics);
        mgr.scanAndFinalize();

        verify(completionMapper, never()).insertCompletion(any(), any(), anyString());
        verify(metrics, times(1)).fencedWriteRejected(eq("finality_update"));
    }
}


