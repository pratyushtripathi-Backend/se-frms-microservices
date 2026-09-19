package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/** Wrapper for GET /api/v2/MessageStatus. Note "Data" here is a single object,
 * not a list like the SendSMS response. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Msg24x7MessageStatusResponse {
    @JsonProperty("ErrorCode") private Integer errorCode;
    @JsonProperty("ErrorDescription") private String errorDescription;
    @JsonProperty("Data") private Msg24x7MessageStatusData data;

    public boolean isSuccess() {
        return errorCode != null && errorCode == 0 && data != null;
    }
}
