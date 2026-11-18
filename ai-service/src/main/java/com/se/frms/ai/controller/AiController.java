package com.se.frms.ai.controller;

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
import com.se.frms.ai.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
@Slf4j
public class AiController {

    private final AiService aiService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ai-service is running");
    }

    @PostMapping("/explain-decision")
    public ResponseEntity<ExplainDecisionResponse> explainDecision(@Valid @RequestBody ExplainDecisionRequest request) {
        return ResponseEntity.ok(aiService.explainDecision(request));
    }

    @PostMapping("/summarize-trends")
    public ResponseEntity<SummarizeTrendsResponse> summarizeTrends(@Valid @RequestBody SummarizeTrendsRequest request) {
        return ResponseEntity.ok(aiService.summarizeTrends(request));
    }

    @PostMapping("/draft-template")
    public ResponseEntity<DraftTemplateResponse> draftTemplate(@Valid @RequestBody DraftTemplateRequest request) {
        return ResponseEntity.ok(aiService.draftTemplate(request));
    }

    @PostMapping("/nl-to-rule")
    public ResponseEntity<NlToRuleResponse> nlToRule(@Valid @RequestBody NlToRuleRequest request) {
        return ResponseEntity.ok(aiService.nlToRule(request));
    }

    @PostMapping("/risk-score")
    public ResponseEntity<RiskScoreResponse> riskScore(@Valid @RequestBody RiskScoreRequest request) {
        return ResponseEntity.ok(aiService.riskScore(request));
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(aiService.chat(request));
    }
}
