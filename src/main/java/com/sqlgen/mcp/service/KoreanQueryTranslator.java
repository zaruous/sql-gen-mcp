package com.sqlgen.mcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * korean-dict.json을 로드하여 한국어 검색어를 영어로 변환합니다.
 * 최장 일치(longest-match greedy) 방식으로 다단어 구문을 우선 매칭하고,
 * 단일 토큰으로 fallback합니다. 사전 미등록 토큰은 원문 유지.
 *
 * 예) "설비 상태 조회" → "equipment status list search get query"
 */
@Service
public class KoreanQueryTranslator {

    private static final Logger logger = LoggerFactory.getLogger(KoreanQueryTranslator.class);
    private static final String DICT_PATH = "korean-dict.json";
    private static final int MAX_NGRAM = 4;

    private Map<String, String> dictionary = Collections.emptyMap();

    @PostConstruct
    public void load() {
        try (InputStream is = KoreanQueryTranslator.class.getClassLoader()
                .getResourceAsStream(DICT_PATH)) {
            if (is == null) {
                logger.warn("[KoreanDict] {} not found. Korean query translation disabled.", DICT_PATH);
                return;
            }
            dictionary = new ObjectMapper().readValue(is, new TypeReference<LinkedHashMap<String, String>>() {});
            logger.info("[KoreanDict] Loaded {} entries from {}", dictionary.size(), DICT_PATH);
        } catch (Exception e) {
            logger.error("[KoreanDict] Failed to load {}: {}", DICT_PATH, e.getMessage(), e);
        }
    }

    /**
     * 한국어 쿼리를 영어로 번역합니다.
     * 한국어가 없으면 원문을 그대로 반환합니다.
     */
    public String translate(String query) {
        if (dictionary.isEmpty() || query == null || query.isBlank()) return query;
        if (!containsKorean(query)) return query;

        String[] tokens = query.trim().split("\\s+");
        StringJoiner result = new StringJoiner(" ");

        int i = 0;
        while (i < tokens.length) {
            int maxLen = Math.min(MAX_NGRAM, tokens.length - i);
            boolean matched = false;
            for (int len = maxLen; len > 1; len--) {
                String phrase = String.join(" ", Arrays.copyOfRange(tokens, i, i + len));
                String translated = dictionary.get(phrase);
                if (translated != null) {
                    result.add(translated);
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                String translated = dictionary.get(tokens[i]);
                result.add(translated != null ? translated : tokens[i]);
                i++;
            }
        }

        String output = result.toString();
        logger.debug("[KoreanDict] '{}' → '{}'", query, output);
        return output;
    }

    private boolean containsKorean(String text) {
        return text.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3
                || c >= 0x3130 && c <= 0x318F);
    }

    public Map<String, String> getDictionary() {
        return Collections.unmodifiableMap(dictionary);
    }
}
