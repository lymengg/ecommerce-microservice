package com.ecommerce.common.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicsTest {

    @Test
    void mapsAggregateTypesToDomainTopics() {
        assertThat(Topics.forAggregateType("order")).isEqualTo("order.events");
        assertThat(Topics.forAggregateType("payment")).isEqualTo("payment.events");
        assertThat(Topics.forAggregateType("inventory")).isEqualTo("inventory.events");
        assertThat(Topics.forAggregateType("catalog")).isEqualTo("catalog.events");
        assertThat(Topics.forAggregateType("cart")).isEqualTo("cart.events");
    }

    @Test
    void unknownAggregateTypeFailsLoudlyRatherThanPublishingToADefaultTopic() {
        assertThatThrownBy(() -> Topics.forAggregateType("shipping"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shipping");
    }

    @Test
    void deadLetterTopicIsTheDomainTopicPlusSuffix() {
        assertThat(Topics.deadLetter(Topics.PAYMENT)).isEqualTo("payment.events.DLT");
        assertThat(Topics.deadLetter(Topics.ORDER)).isEqualTo("order.events.DLT");
    }
}
