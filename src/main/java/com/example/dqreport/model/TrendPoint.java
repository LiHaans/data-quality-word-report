package com.example.dqreport.model;

import java.time.LocalDate;

public class TrendPoint {
    private final LocalDate date;
    private final int count;

    public TrendPoint(LocalDate date, int count) {
        this.date = date;
        this.count = count;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getCount() {
        return count;
    }
}
