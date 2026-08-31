package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Msg24x7SmsRequest {
    private String senderId;
    @JsonProperty("is_Unicode") private Boolean isUnicode;
    @JsonProperty("is_Flash") private Boolean isFlash;
    private Boolean isRegisteredForDelivery;
    private String validityPeriod;
    private Integer dataCoding;
    private String schedTime;
    private String groupId;
    private String message;
    private String mobileNumbers;
    private String serviceId;
    private String coRelator;
    private String linkId;
    private String principleEntityId;
    private String templateId;
    private String apiKey;
    private String clientId;
}
