package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Local projection of ai-service's DraftTemplateResponse. ai-service returns
 * one free-text draft (not separate subject/body fields), so this is
 * returned to the admin as-is for them to edit and save through the
 * existing createTemplate/updateTemplate endpoints - it's never parsed into
 * EmailTemplateContent or written to the database directly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiDraftTemplateResponse(String draft) {
}
