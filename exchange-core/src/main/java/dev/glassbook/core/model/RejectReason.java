package dev.glassbook.core.model;

public enum RejectReason {
    UNKNOWN_INSTRUMENT,         // нет такого инструмента
    INSTRUMENT_HALTED,          // торги остановлены
    UNKNOWN_ACCOUNT,            // нет такого счёта
    INVALID_QUANTITY,           // некорректное количество
    INVALID_PRICE,              // некорректная цена
    INVALID_ORDER,              // заявка некорректна (не заполнены обязательные поля, недопустимое сочетание параметров)
    TICK_SIZE_VIOLATION,        // цена не кратна шагу цены инструмента
    LOT_SIZE_VIOLATION,         // количество не кратно размеру лота
    INSUFFICIENT_FUNDS,         // недостаточно денежных средств
    INSUFFICIENT_POSITION,      // недостаточно бумаг для продажи
    DUPLICATE_CLIENT_ORDER_ID,  // заявка с таким клиентским ид уже существует
    FOK_NOT_FILLABLE,           // FOK нельзя исполнить полностью сразу
    POST_ONLY_WOULD_CROSS,      // post-only сразу пересеклась бы со встречной
    UNKNOWN_ORDER,              // заявка не найдена (при отмене или изменении)
    INVALID_AMOUNT,             // некорректная сумма
    BALANCE_OVERFLOW            // операция привела бы к переполнению баланса
}
