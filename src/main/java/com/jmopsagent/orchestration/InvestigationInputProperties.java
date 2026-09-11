package com.jmopsagent.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** User-facing date convention, independent of connector safety limits. */
@ConfigurationProperties("jmops.input")
public class InvestigationInputProperties {
    public enum DateOrder { MONTH_DAY, DAY_MONTH }
    private DateOrder dateOrder = DateOrder.MONTH_DAY;
    public DateOrder getDateOrder() { return dateOrder; }
    public void setDateOrder(DateOrder value) {
        if (value == null) throw new IllegalArgumentException("Date order is required");
        dateOrder = value;
    }
}
