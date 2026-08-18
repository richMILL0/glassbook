package dev.glassbook.core.model;


public record Instrument(int id, String symbol, long tickSize, long lotSize, int priceScale) {

    public static final long MAX_PRICE = 1_000_000_000_000L;
    public static final long MAX_QUANTITY = 1_000_000L;

    public Instrument {
        if (id < 1) {
            throw new IllegalArgumentException("id инструкмента должен быть >= 1");
        }

        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Требуется ввести symbol");
        }

        if (tickSize < 1 || lotSize < 1) {
            throw new IllegalArgumentException("Размер тика и кратное по объему должны быть положительными");
        }

        if (priceScale < 0 || priceScale > 9) {
            throw new IllegalArgumentException("Количество знаков после запятой должно пинадлежать [0, 9]");
        }
    }

    public boolean isPriceInRange(long price) {
        return price >= 1 && price <= MAX_PRICE;
    }

    public boolean isOnTick(long price) {
        return price % tickSize == 0;
    }

    public boolean isQuantityInRange(long quantity) {
        return quantity >= 1 && quantity <= MAX_QUANTITY;
    }

    public boolean isOnLot(long quantity) {
        return quantity % lotSize == 0;
    }
}
