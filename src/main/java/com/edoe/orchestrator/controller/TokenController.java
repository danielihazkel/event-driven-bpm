package com.edoe.orchestrator.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dev")
@Profile("dev")
public class TokenController {

    @Value("${edoe.orchestrator.jwt.secret}")
    private String jwtSecret;

    @GetMapping("/token")
    public Map<String, String> issueToken(
            @RequestParam(defaultValue = "test-user") String subject,
            @RequestParam(defaultValue = "ROLE_ADMIN") String role) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        JWSSigner signer = new MACSigner(keyBytes);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(subject)
            .issueTime(new Date())
            .expirationTime(new Date(System.currentTimeMillis() + 3600_000L))
            .claim("roles", List.of(role))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(signer);
        return Map.of("token", jwt.serialize());
    }
}
