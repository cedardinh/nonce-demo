package com.work.nonce.txmgr.service.nonce;

import com.work.nonce.txmgr.chain.ChainConnector;
import com.work.nonce.txmgr.config.TxMgrProperties;
import com.work.nonce.txmgr.repository.entity.SubmitterNonceCursorEntity;
import com.work.nonce.txmgr.repository.mapper.SubmitterNonceCursorMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class MaxNonceAllocatorTest {

    @Test
    public void uses_max_of_chain_and_db_when_no_cache() {
        ChainConnector chain = mock(ChainConnector.class);
        when(chain.getPendingNonce(eq("s1"))).thenReturn(10L);

        SubmitterNonceCursorMapper cursorMapper = mock(SubmitterNonceCursorMapper.class);
        SubmitterNonceCursorEntity c = new SubmitterNonceCursorEntity();
        c.setSubmitter("s1");
        c.setNextNonce(7L);
        when(cursorMapper.selectBySubmitter(eq("s1"))).thenReturn(c);

        TxMgrProperties props = new TxMgrProperties();
        props.setNonceStateTimeout(Duration.ofHours(1));

        NonceCache cache = new NonceCache(100);
        MaxNonceAllocator a = new MaxNonceAllocator(chain, cursorMapper, props, cache);

        long n = a.nextNonce("s1", Instant.now());
        assertEquals(10L, n);
    }
}


