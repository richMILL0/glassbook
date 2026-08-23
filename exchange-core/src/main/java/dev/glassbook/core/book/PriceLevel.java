package dev.glassbook.core.book;

public final class PriceLevel {
    final long price;
    Order head;
    Order tail;
    long totalQuantity;
    int orderCount;

    PriceLevel(long price) {
        this.price = price;
    }

    public long price() {
        return price;
    }

    public long totalQuantity() {
        return totalQuantity;
    }

    public int orderCount() {
        return orderCount;
    }

    public Order head() {
        return head;
    }

    boolean isEmpty() {
        return head == null;
    }

    void append(Order o) {
        o.level = this;
        o.prev = tail;
        o.next = null;
        if (tail == null) {
            head = o;
        } else {
            tail.next = o;
        }
        tail = o;
        totalQuantity += o.remaining;
        orderCount++;
    }

    void unlink(Order o) {
        if (o.prev == null) {
            head = o.next;
        } else {
            o.prev.next = o.next;
        }

        if (o.next == null) {
            tail = o.prev;
        } else {
            o.next.prev = o.prev;
        }

        totalQuantity -= o.remaining;
        orderCount--;

        o.prev = null;
        o.next = null;
        o.level = null;
    }
}
