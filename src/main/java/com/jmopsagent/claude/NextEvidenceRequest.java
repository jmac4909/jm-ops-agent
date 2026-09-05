package com.jmopsagent.claude;

public record NextEvidenceRequest(EvidenceRequestType type, String service, String reason) {
}
