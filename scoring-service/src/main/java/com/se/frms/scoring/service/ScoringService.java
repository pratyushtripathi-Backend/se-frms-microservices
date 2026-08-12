package com.se.frms.scoring.service;
import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
public interface ScoringService { ScoringResponse process(ScoringRequest request); }
