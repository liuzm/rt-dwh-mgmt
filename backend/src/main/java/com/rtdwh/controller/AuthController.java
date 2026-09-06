package com.rtdwh.controller;

import com.rtdwh.config.JwtUtil;
import com.rtdwh.dto.ApiResponse;
import com.rtdwh.dto.LoginRequest;
import com.rtdwh.dto.LoginResponse;
import com.rtdwh.security.CustomUserDetailsService;
import com.rtdwh.entity.SysUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final CustomUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * POST /auth/login
     * 登录接口：验证用户名密码，返回 JWT token 和用户信息
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // Do not expose whether a username exists. Both missing users and bad
        // passwords are authentication failures (401), not server errors.
        final UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        } catch (UsernameNotFoundException e) {
            return ApiResponse.error(401, "用户名或密码错误");
        }

        // 2. 校验密码
        if (!passwordEncoder.matches(request.getPassword(), userDetails.getPassword())) {
            return ApiResponse.error(401, "用户名或密码错误");
        }
        if (!userDetails.isEnabled()) {
            return ApiResponse.error(403, "用户已被禁用");
        }

        // 3. 生成 JWT
        String token = jwtUtil.generateToken(userDetails);

        // 4. 构造响应
        SysUser user = userDetailsService.getUserByUsername(request.getUsername());
        LoginResponse resp = new LoginResponse();
        resp.setToken(token);
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setRealName(user.getRealName());
        resp.setEmail(user.getEmail());
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getRoleCode())
                .collect(Collectors.toList());
        resp.setRole(String.join(",", roles));
        resp.setPermissions(permissionCodes(user));

        return ApiResponse.success("登录成功", resp);
    }

    /**
     * GET /auth/current-user
     * 获取当前登录用户信息
     */
    @GetMapping("/current-user")
    public ApiResponse<LoginResponse> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResponse.error(401, "未登录");
        }

        String username = authentication.getName();
        SysUser user = userDetailsService.getUserByUsername(username);

        LoginResponse resp = new LoginResponse();
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setRealName(user.getRealName());
        resp.setEmail(user.getEmail());
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getRoleCode())
                .collect(Collectors.toList());
        resp.setRole(String.join(",", roles));
        resp.setPermissions(permissionCodes(user));

        return ApiResponse.success(resp);
    }

    private List<String> permissionCodes(SysUser user) {
        return user.getRoles().stream()
                .filter(Objects::nonNull)
                .flatMap(role -> role.getPermissions() == null
                        ? java.util.stream.Stream.empty()
                        : role.getPermissions().stream())
                .filter(Objects::nonNull)
                .map(permission -> permission.getPermCode())
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }
}
