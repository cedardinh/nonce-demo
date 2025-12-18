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

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class TransactionWriterIdempotencyTest {

    @Test
    public void conflict_requestid_does_not_insert_or_consume_nonce() throws Exception {
        TxMgrProperties props = new TxMgrProperties();
        LeaseManager leaseManager = mock(LeaseManager.class);
        when(leaseManager.acquireOrRenew(eq("s1"))).thenReturn(new LeaseDecision(true, 1L, Instant.now().plusSeconds(10)));
        when(leaseManager.getNodeId()).thenReturn("nodeA");

        ManagedTxMapper txMapper = mock(ManagedTxMapper.class);
        SubmitterNonceCursorMapper cursorMapper = mock(SubmitterNonceCursorMapper.class);
        ChainConnector chain = mock(ChainConnector.class);
        TxMgrMetrics metrics = mock(TxMgrMetrics.class);

        ManagedTxEntity existing = new ManagedTxEntity();
        UUID existingId = UUID.randomUUID();
        existing.setTxId(existingId);
        existing.setSubmitter("s1");
        existing.setRequestId("r1");
        when(txMapper.selectBySubmitterAndRequestId(eq("s1"), eq("r1"))).thenReturn(existing);

        TransactionWriter writer = new TransactionWriter(props, leaseManager, txMapper, cursorMapper, chain, metrics);

        TransactionWriter.CreateTxOp op = new TransactionWriter.CreateTxOp("s1", "r1", "{\"p\":1}");
        writer.processBatchTx(Collections.singletonList(op));

        ManagedTxEntity out = op.future.get();
        assertSame(existing, out);

        verify(txMapper, never()).insertManagedTx(any(), any(), any(), anyLong(), any(), any(), anyLong(), any(), any());
        verify(cursorMapper, times(1)).insertIfNotExists(eq("s1"), anyLong(), anyLong(), any(Instant.class));
        verify(cursorMapper, never()).updateNextNonceFenced(any(), anyLong(), any(), anyLong(), any());
    }
}


