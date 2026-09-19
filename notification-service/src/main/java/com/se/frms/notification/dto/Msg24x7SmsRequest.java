package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Trimmed down to exactly the 5 fields MSG24x7 confirmed are required
 * (matching PascalCase key names) - the extra fields (templateId,
 * principleEntityId, isUnicode/isFlash, coRelator, etc.) were accepted by the
 * API but the SMS silently never reached the phone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Msg24x7SmsRequest {
    @JsonProperty("SenderId") private String senderId;
    @JsonProperty("Message") private String message;
    @JsonProperty("MobileNumbers") private String mobileNumbers;
    @JsonProperty("ApiKey") private String apiKey;
    @JsonProperty("ClientId") private String clientId;
}
