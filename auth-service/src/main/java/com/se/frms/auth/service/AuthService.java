package com.se.frms.auth.service;

import com.se.frms.auth.dto.LoginRequest;
import com.se.frms.auth.dto.LoginResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);
}
