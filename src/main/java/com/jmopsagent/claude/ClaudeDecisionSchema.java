package com.jmopsagent.claude;

/** JSON schema passed to Claude Code structured output mode. */
final class ClaudeDecisionSchema {
    private ClaudeDecisionSchema() {}

    static final String JSON = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["status","summary","hypotheses","nextEvidenceRequests","rootCauseCategory","recommendedActions"],
              "properties":{
                "status":{"type":"string","enum":["NEEDS_MORE_EVIDENCE","COMPLETE","CODE_INVESTIGATION_RECOMMENDED"]},
                "summary":{"type":"string"},
                "hypotheses":{"type":"array","items":{
                  "type":"object","additionalProperties":false,"required":["cause","confidence","evidenceIds"],
                  "properties":{
                    "cause":{"type":"string"},
                    "confidence":{"type":"number"},
                    "evidenceIds":{"type":"array","items":{"type":"string"}}
                  }
                }},
                "nextEvidenceRequests":{"type":"array","items":{
                  "type":"object","additionalProperties":false,"required":["type","reason"],
                  "properties":{
                    "type":{"type":"string","enum":["WORKLOAD_HEALTH","RECENT_RUNTIME_EVENTS","RECENT_LOGS","DEPLOYMENT_METADATA","LATEST_DEPLOYMENT","RECENT_CHANGES","EFFECTIVE_CONFIGURATION","SERVICE_EVENTS","RECENT_ACTIVITY","RECENT_BUSINESS_CALLS","ERROR_PATTERNS","DEPENDENCY_EVIDENCE","HISTORICAL_INCIDENTS","RELEVANT_CODE_FILES"]},
                    "service":{"type":["string","null"]},
                    "reason":{"type":"string"}
                  }
                }},
                "rootCauseCategory":{"type":"string","enum":["CONFIG","DEPLOYMENT","RUNTIME","DEPENDENCY","CODE","UNKNOWN"]},
                "recommendedActions":{"type":"array","items":{"type":"string"}}
              }
            }
            """.replaceAll("\\s+", " ").trim();
}
