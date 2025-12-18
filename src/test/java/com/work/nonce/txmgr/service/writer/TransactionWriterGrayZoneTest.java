package com.work.nonce.txmgr.service.writer;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.domain.LeaseDecision;
import com.work.nonce.txmgr.repository.entity.ManagedTxEntity;
import com.work.nonce.txmgr.repository.mapper.ManagedTxMapper;
import com.work.nonce.txmgr.repository.mapper.SubmitterNonceCursorMapper;
import com.work.nonce.txmgr.service.LeaseManager;
import com.work.nonce.txmgr.support.metrics.TxMgrMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TransactionWriterGrayZoneTest {

    @Test
    public void derive_expected_txhash_when_send_throws() throws Exception {
        TxMgrProperties props = new TxMgrProperties();
        props.setResubmitInterval(Duration.ofSeconds(30));

        LeaseManager leaseManager = mock(LeaseManager.class);
        when(leaseManager.acquireOrRenew(eq("s1"))).thenReturn(new LeaseDecision(true, 5L, Instant.now().plusSeconds(10)));
        when(leaseManager.getNodeId()).thenReturn("nodeA");

        ManagedTxMapper txMapper = mock(ManagedTxMapper.class);
        SubmitterNonceCursorMapper cursorMapper = mock(SubmitterNonceCursorMapper.class);

        ChainConnector chain = mock(ChainConnector.class);
        when(chain.sendTransaction(anyString(), anyLong(), anyString())).thenThrow(new RuntimeException("timeout"));
        when(chain.deriveExpectedTxHash(eq("s1"), eq(1L), anyString())).thenReturn("expected_hash");

        TxMgrMetrics metrics = mock(TxMgrMetrics.class);

        TransactionWriter writer = new TransactionWriter(props, leaseManager, txMapper, cursorMapper, chain, metrics);

        ManagedTxEntity tx = new ManagedTxEntity();
        UUID txId = UUID.randomUUID();
        tx.setTxId(txId);
        tx.setSubmitter("s1");
        tx.setNonce(1L);
        tx.setPayload("{\"p\":1}");

        writer.submitToChainAsync(tx);
        // submitToChainAsync uses CompletableFuture.runAsync; wait deterministically
        verify(txMapper, timeout(1000).atLeastOnce()).updateTxHashFenced(eq(txId), eq("s1"), eq("expected_hash"), eq("TRACKING"),
                any(Instant.class), eq("nodeA"), eq(5L), any(Instant.class));
    }
}


