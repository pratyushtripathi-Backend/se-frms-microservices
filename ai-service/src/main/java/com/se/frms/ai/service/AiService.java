package com.se.frms.ai.service;

import com.se.frms.ai.dto.ChatRequest;
import com.se.frms.ai.dto.ChatResponse;
import com.se.frms.ai.dto.DraftTemplateRequest;
import com.se.frms.ai.dto.DraftTemplateResponse;
import com.se.frms.ai.dto.ExplainDecisionRequest;
import com.se.frms.ai.dto.ExplainDecisionResponse;
import com.se.frms.ai.dto.NlToRuleRequest;
import com.se.frms.ai.dto.NlToRuleResponse;
import com.se.frms.ai.dto.RiskScoreRequest;
import com.se.frms.ai.dto.RiskScoreResponse;
import com.se.frms.ai.dto.SummarizeTrendsRequest;
import com.se.frms.ai.dto.SummarizeTrendsResponse;

public interface AiService {

    ExplainDecisionResponse explainDecision(ExplainDecisionRequest request);

    SummarizeTrendsResponse summarizeTrends(SummarizeTrendsRequest request);

    DraftTemplateResponse draftTemplate(DraftTemplateRequest request);

    NlToRuleResponse nlToRule(NlToRuleRequest request);

    /** Disabled by default - see ai.features.risk-score.enabled. */
    RiskScoreResponse riskScore(RiskScoreRequest request);

    /** Disabled by default - see ai.features.chat.enabled. */
    ChatResponse chat(ChatRequest request);
}
