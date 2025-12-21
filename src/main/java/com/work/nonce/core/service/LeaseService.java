package com.work.nonce.core.service;

import com.work.nonce.core.config.NonceConfig;
import com.work.nonce.core.exception.LeaseNotOwnedException;
import com.work.nonce.core.repository.NonceRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static com.work.nonce.core.support.ValidationUtils.requireNonEmpty;
import static com.work.nonce.core.support.ValidationUtils.requireNonNull;

/**
 * signer 级租约（lease）服务：负责获取/续约租约并返回 fencing token。
 * <p>
 * 注意：此服务本身不负责重试策略，重试由上层（NonceService）统一管理。
 */
@Service
public class LeaseService {

    private final NonceRepository nonceRepository;
    private final String ownerId;
    private final Duration leaseTtl;

    public LeaseService(NonceRepository nonceRepository, NonceConfig config) {
        this.nonceRepository = requireNonNull(nonceRepository, "nonceRepository");
        requireNonNull(config, "config");
        this.ownerId = requireNonEmpty(config.getOwnerId(), "config.ownerId");
        this.leaseTtl = requireNonNull(config.getLeaseTtl(), "config.leaseTtl");
    }

    public long acquireOrThrow(String signer) {
        requireNonEmpty(signer, "signer");
        Long token = nonceRepository.acquireOrRenewLease(signer, ownerId, leaseTtl);
        if (token == null) {
            throw new LeaseNotOwnedException("未能获取 signer 租约（可能被其他节点持有）: " + signer);
        }
        return token;
    }
}


