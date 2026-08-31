package com.se.frms.decision.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.se.frms.decision.cache.DecisionPolicyCache;
import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionResponse;
import com.se.frms.decision.repository.DecisionRepository;
import com.se.frms.decision.service.DecisionPersistenceService;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DecisionServiceImplTest {

    @Mock
    private DecisionRepository decisionRepository;

    @Mock
    private DecisionPolicyCache decisionPolicyCache;

    @Mock
    private DecisionPersistenceService decisionPersistenceService;

    private DecisionServiceImpl decisionService;

    @BeforeEach
    void setUp() throws Exception {
        decisionService = new DecisionServiceImpl(decisionRepository, decisionPolicyCache, decisionPersistenceService);
        setField("allowMaxScore", 39);
        setField("reviewMaxScore", 69);
        when(decisionPolicyCache.getActivePolicy()).thenReturn(null);
    }

    @Test
    void shouldAllowLowRiskScore() {
        DecisionResponse response = decisionService.process(request(20));

        assertThat(response.finalDecision()).isEqualTo("ALLOW");
    }

    @Test
    void shouldReviewMediumRiskScore() {
        DecisionResponse response = decisionService.process(request(55));

        assertThat(response.finalDecision()).isEqualTo("REVIEW");
    }

    @Test
    void shouldBlockHighRiskScore() {
        DecisionResponse response = decisionService.process(request(80));

        assertThat(response.finalDecision()).isEqualTo("BLOCK");
    }

    private DecisionRequest request(Integer totalRiskScore) {
        return new DecisionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                totalRiskScore,
                Map.of()
        );
    }

    private void setField(String fieldName, Integer value) throws Exception {
        Field field = DecisionServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(decisionService, value);
    }
}
