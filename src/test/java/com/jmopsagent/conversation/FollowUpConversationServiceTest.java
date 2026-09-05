package com.jmopsagent.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FollowUpConversationServiceTest {

    @Test
    void recognizesOnlyExplicitRecentTrafficIntent() {
        assertThat(FollowUpConversationService.requestsRecentBusinessCalls("Show me recent requests")).isTrue();
        assertThat(FollowUpConversationService.requestsRecentBusinessCalls("What recent calls failed?")).isTrue();
        assertThat(FollowUpConversationService.requestsRecentBusinessCalls("What calls are involved?")).isFalse();
        assertThat(FollowUpConversationService.requestsRecentBusinessCalls("Show me requests")).isFalse();
        assertThat(FollowUpConversationService.requestsRecentBusinessCalls("Why is this configuration?")).isFalse();
        assertThat(FollowUpConversationService.requestsRecentBusinessCalls("Could the database be down?")).isFalse();
    }
}
