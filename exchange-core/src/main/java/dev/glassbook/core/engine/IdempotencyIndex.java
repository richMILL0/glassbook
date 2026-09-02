package dev.glassbook.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;

final class IdempotencyIndex {

    private final LinkedHashMap<String, Long> map;

    IdempotencyIndex(int capacity) {
        this.map = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > capacity;
            }
        };
    }

    static String key(long participantId, String clientOrderId) {
        return participantId + ":" + clientOrderId;
    }

    Long get(String key) {
        return map.get(key);
    }

    void put(String key, long orderId) {
        map.put(key, orderId);
    }

    Map<String, Long> entries() {
        return map;
    }

    int size() {
        return map.size();
    }
}
