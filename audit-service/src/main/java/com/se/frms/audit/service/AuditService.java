package com.se.frms.audit.service;
import com.se.frms.audit.dto.FraudEvent;
public interface AuditService { void handleFraudEvent(FraudEvent event); }
