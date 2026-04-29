package com.sqlgen.mcp.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class VectorStoreModeResolver {
    private static final Logger logger = LoggerFactory.getLogger(VectorStoreModeResolver.class);

    private final List<VectorStoreModeStrategy> strategies;

    public VectorStoreModeResolver(List<VectorStoreModeStrategy> strategies) {
        this.strategies = strategies;
    }

    public VectorStoreModeStrategy resolve(Environment env) {
        String provider = normalize(env.getProperty("ai.vector-store.provider", "local"));
        return strategies.stream()
                .filter(strategy -> strategy.provider().equals(provider))
                .findFirst()
                .map(strategy -> {
                    // 실제 선택된 전략을 로그로 남겨 local/chroma 분기 추적을 쉽게 한다.
                    logger.info("[VectorMode] Selected strategy: provider={}, storeType={}, strategyClass={}",
                            strategy.provider(), strategy.storeType(), strategy.getClass().getSimpleName());
                    return strategy;
                })
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported ai.vector-store.provider: " + provider + " (allowed: local, chroma)"));
    }

    public String normalize(String provider) {
        String normalized = provider == null ? "local" : provider.trim().toLowerCase();
        return switch (normalized) {
            case "local", "chroma" -> normalized;
            default -> throw new IllegalArgumentException(
                    "Unsupported ai.vector-store.provider: " + provider + " (allowed: local, chroma)");
        };
    }
}
