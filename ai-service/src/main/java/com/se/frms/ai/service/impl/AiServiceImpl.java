package com.se.frms.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.ai.client.ClaudeClient;
import com.se.frms.ai.dto.ChatRequest;
import com.se.frms.ai.dto.ChatResponse;
import com.se.frms.ai.dto.ChatTurn;
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
import com.se.frms.ai.entity.AiAuditLog;
import com.se.frms.ai.exception.ClaudeCallException;
import com.se.frms.ai.exception.FeatureDisabledException;
import com.se.frms.ai.repository.AiAuditLogRepository;
import com.se.frms.ai.service.AiService;
import com.se.frms.ai.util.PiiRedactor;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final ClaudeClient claudeClient;
    private final PiiRedactor piiRedactor;
    private final AiAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${ai.features.risk-score.enabled:false}")
    private boolean riskScoreEnabled;

    @Value("${ai.features.chat.enabled:false}")
    private boolean chatEnabled;

    @Override
    public ExplainDecisionResponse explainDecision(ExplainDecisionRequest request) {
        String prompt = """
                Explain in one or two plain-English sentences why this transaction was flagged. \
                Write only the explanation, no preamble.
                Matched rules: %s
                Transaction detail: %s
                Risk score: %d
                Final decision: %s
                """.formatted(
                toJson(request.matchedRules()),
                toJson(request.transactionDetail()),
                request.score(),
                request.finalDecision()
        );
        return new ExplainDecisionResponse(runPrompt("explain-decision", prompt));
    }

    @Override
    public SummarizeTrendsResponse summarizeTrends(SummarizeTrendsRequest request) {
        String prompt = """
                Summarize what moved and why, in a short plain-English paragraph. \
                Write only the summary, no preamble.
                Fraud trend data: %s
                Rule performance data: %s
                """.formatted(toJson(request.fraudTrend()), toJson(request.rulePerformance()));
        return new SummarizeTrendsResponse(runPrompt("summarize-trends", prompt));
    }

    @Override
    public DraftTemplateResponse draftTemplate(DraftTemplateRequest request) {
        String prompt = """
                Draft a %s notification message body for this intent. \
                Write only the message body, no preamble. If the channel is email, prefix the \
                first line with "Subject: ".
                Intent: %s
                """.formatted(request.channel(), request.intent());
        return new DraftTemplateResponse(runPrompt("draft-template", prompt));
    }

    @Override
    public NlToRuleResponse nlToRule(NlToRuleRequest request) {
        String prompt = """
                Translate this plain-English fraud rule description into a JSON object with \
                exactly these three fields: "field", "operator", "value". Output only the raw \
                JSON object, nothing else - no markdown, no preamble.
                Description: %s
                """.formatted(request.description());
        String text = runPrompt("nl-to-rule", prompt);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rule = objectMapper.readValue(text, Map.class);
            return new NlToRuleResponse(rule);
        } catch (Exception ex) {
            throw new ClaudeCallException("Claude did not return a parseable rule JSON object", ex);
        }
    }

    /**
     * Prompt-based score for evaluation only. Not the trained gradient-boosted model the
     * proposal's Phase 3 specifies for a production blend into totalRiskScore - this exists so
     * the quality of an LLM-judged score can be compared against real outcomes before deciding
     * whether building that model is worth it.
     */
    @Override
    public RiskScoreResponse riskScore(RiskScoreRequest request) {
        if (!riskScoreEnabled) {
            throw new FeatureDisabledException(
                    "AI risk-score is disabled - no trained model is deployed yet (ai.features.risk-score.enabled=false)");
        }
        String prompt = """
                Score the fraud risk of this transaction from 0 to 100, where 0 is certainly \
                legitimate and 100 is certainly fraudulent. Output a JSON object with exactly \
                these two fields: "score" (a number 0-100) and "reasoning" (one or two plain- \
                English sentences). Output only the raw JSON object, nothing else - no markdown, \
                no preamble.
                Transaction features: %s
                """.formatted(toJson(request.features()));
        String text = runPrompt("risk-score", prompt);
        try {
            Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
            Double score = ((Number) parsed.get("score")).doubleValue();
            String reasoning = (String) parsed.get("reasoning");
            return new RiskScoreResponse(score, reasoning);
        } catch (Exception ex) {
            throw new ClaudeCallException("Claude did not return a parseable risk-score JSON object", ex);
        }
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (!chatEnabled) {
            throw new FeatureDisabledException(
                    "AI chat is disabled (ai.features.chat.enabled=false)");
        }
        List<ChatTurn> redactedHistory = redactHistory(request.history());
        String redactedMessage = piiRedactor.redact(request.message());
        String responseText = claudeClient.completeConversation(redactedHistory, redactedMessage);
        String redactedResponse = piiRedactor.redact(responseText);
        logAudit("chat", redactedMessage, redactedResponse);
        return new ChatResponse(redactedResponse);
    }

    private List<ChatTurn> redactHistory(List<ChatTurn> history) {
        if (history == null) {
            return List.of();
        }
        return history.stream()
                .map(turn -> new ChatTurn(turn.role(), piiRedactor.redact(turn.content())))
                .toList();
    }

    private String runPrompt(String endpoint, String prompt) {
        String redactedPrompt = piiRedactor.redact(prompt);
        String responseText = claudeClient.complete(redactedPrompt);
        String redactedResponse = piiRedactor.redact(responseText);
        logAudit(endpoint, redactedPrompt, redactedResponse);
        return redactedResponse;
    }

    /** Best-effort: a failed audit write must not fail the AI response itself. */
    private void logAudit(String endpoint, String requestPayload, String responsePayload) {
        try {
            AiAuditLog entry = new AiAuditLog();
            entry.setEndpoint(endpoint);
            entry.setModel(claudeClient.getModel());
            entry.setRequestPayload(requestPayload);
            entry.setResponsePayload(responsePayload);
            auditLogRepository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist AI audit log entry for endpoint={}", endpoint, ex);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
