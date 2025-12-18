package com.work.nonce.txmgr.web;

import com.work.nonce.txmgr.repository.entity.TxCompletionEntity;
import com.work.nonce.txmgr.service.TxService;
import com.work.nonce.txmgr.web.dto.TxCompletionView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 111最终方案.md：poll-only 的终局结果 feed（completion feed）。
 *
 * 说明：里程碑1 中仅提供查询接口与数据模型；终局写入（进入 CONFIRMED/FAILED_FINAL/STUCK）由后续里程碑接入。
 */
@RestController
@RequestMapping("/api/v1/tx/completions")
public class TxCompletionController {

    private final TxService txService;

    public TxCompletionController(TxService txService) {
        this.txService = txService;
    }

    @GetMapping
    public ResponseEntity<List<TxCompletionView>> list(@RequestParam(value = "afterSeq", required = false) Long afterSeq,
                                                       @RequestParam(value = "limit", required = false) Integer limit) {
        int l = limit == null ? 50 : limit;
        List<TxCompletionEntity> rows = txService.listCompletions(afterSeq, l);
        List<TxCompletionView> out = new ArrayList<>(rows.size());
        for (TxCompletionEntity r : rows) {
            TxCompletionView v = new TxCompletionView();
            v.setSeq(r.getSeq());
            v.setTxId(r.getTxId());
            v.setTime(r.getTime());
            v.setStatus(r.getStatus());
            out.add(v);
        }
        return ResponseEntity.ok(out);
    }
}


