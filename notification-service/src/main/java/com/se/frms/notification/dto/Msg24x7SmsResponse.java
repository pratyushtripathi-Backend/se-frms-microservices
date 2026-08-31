package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Msg24x7SmsResponse {
    @JsonProperty("ErrorCode") private Integer errorCode;
    @JsonProperty("ErrorDescription") private String errorDescription;
    @JsonProperty("Data") private List<Msg24x7MessageResponse> data;

    public boolean isSuccess() {
        return errorCode != null && errorCode == 0
                && (data == null || data.isEmpty() || data.stream().allMatch(Msg24x7MessageResponse::isSuccess));
    }
}
