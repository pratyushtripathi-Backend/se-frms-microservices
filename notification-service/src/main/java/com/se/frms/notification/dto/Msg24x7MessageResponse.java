package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Msg24x7MessageResponse {
    @JsonProperty("MessageErrorCode") private Integer messageErrorCode;
    @JsonProperty("MessageErrorDescription") private String messageErrorDescription;
    // Needed to know which message to later confirm real delivery for via
    // checkDeliveryStatus() - not parsed anywhere before this change.
    @JsonProperty("MessageId") private String messageId;
    public boolean isSuccess() {
        return messageErrorCode != null && messageErrorCode == 0;
    }
}
