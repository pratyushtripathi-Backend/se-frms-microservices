package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Msg24x7MessageStatusData {
    @JsonProperty("MobileNumber") private String mobileNumber;
    @JsonProperty("SenderId") private String senderId;
    @JsonProperty("Message") private String message;
    @JsonProperty("SubmitDate") private String submitDate;
    @JsonProperty("DoneDate") private String doneDate;
    @JsonProperty("MessageId") private String messageId;
    // "DELIVRD" / "UNDELIV" / pending, per MSG24x7's status vocabulary.
    @JsonProperty("Status") private String status;
    // Nested ErrorCode is a string here (e.g. "000"), unlike the outer wrapper's Integer.
    @JsonProperty("ErrorCode") private String errorCode;
}
