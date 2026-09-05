package com.jmopsagent.ui;

public record ServiceChainNode(String service, String status, Integer statusCode, String detail) {
}
