package com.se.frms.rulecache.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MonolithApiResponseDTO<T> {

    private Boolean status;

    private Integer responseCode;

    private String responseMessage;

    private T responseData;
}