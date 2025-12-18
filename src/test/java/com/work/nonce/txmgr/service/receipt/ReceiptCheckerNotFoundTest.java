package com.work.nonce.txmgr.service.receipt;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.service.finality.TxFinalityService;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ReceiptCheckerNotFoundTest {

    @Test
    public void not_found_updates_last_receipt_check_at() {
        TxMgrProperties props = new TxMgrProperties();
        props.setStaleReceiptTimeout(Duration.ofSeconds(5));

        ManagedTxMapper txMapper = mock(ManagedTxMapper.class);
        ChainConnector chain = mock(ChainConnector.class);
        when(chain.getTransactionReceipt(eq("h1"))).thenReturn(null);

        TxFinalityService finality = mock(TxFinalityService.class);

        LeaseManager leaseManager = mock(LeaseManager.class);
        when(leaseManager.acquireOrRenew(eq("s1"))).thenReturn(new LeaseDecision(true, 9L, Instant.now().plusSeconds(10)));
        when(leaseManager.getNodeId()).thenReturn("nodeA");

        TxMgrMetrics metrics = mock(TxMgrMetrics.class);

        ReceiptChecker checker = new ReceiptChecker(props, txMapper, chain, finality, leaseManager, metrics);
        ReceiptChecker.ReceiptTask task = new ReceiptChecker.ReceiptTask(UUID.randomUUID(), "s1", "h1", System.currentTimeMillis());

        checker.handleTaskForTest(task);

        verify(txMapper, times(1)).updateLastReceiptCheckAtFenced(eq(task.txId), eq("s1"), any(Instant.class), eq("nodeA"), eq(9L), any(Instant.class));
        verify(metrics, times(1)).receiptCheck(eq("not_found"));
        verify(finality, never()).handleReceipt(any(), any(), any(), any());
    }
}


