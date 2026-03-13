/*
 * OAuthRealmJWTTest.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 */

package org.bluezoo.gumdrop.auth;

import org.junit.Test;
import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Unit tests for OAuthRealm local JWT validation (RFC 7519).
 */
public class OAuthRealmJWTTest {

	private static final String TEST_SECRET = "super-secret-key-for-hmac-testing";

	// ========== Helper methods ==========

	private OAuthRealm createRealm(Properties extra) {
		Properties config = new Properties();
		config.setProperty("oauth.authorization.server.url", "https://auth.example.com");
		config.setProperty("oauth.client.id", "test-client");
		config.setProperty("oauth.client.secret", "test-secret");
		config.setProperty("oauth.jwt.enabled", "true");
		config.setProperty("oauth.jwt.secret", TEST_SECRET);
		if (extra != null) {
			config.putAll(extra);
		}
		return new OAuthRealm(config);
	}

	private String createHS256JWT(String headerJson, String payloadJson,
								  String secret) throws Exception {
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String header = enc.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + payload;

		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));

		return signingInput + "." + enc.encodeToString(sig);
	}

	// ========== HS256 tests ==========

	@Test
	public void testValidHS256Token() throws Exception {
		OAuthRealm realm = createRealm(null);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		String payload = "{\"sub\":\"testuser\",\"scope\":\"read write\",\"exp\":" + futureExp + "}";
		String token = createHS256JWT("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNotNull(result);
		assertTrue(result.valid);
		assertEquals("testuser", result.username);
		assertEquals("JWT", result.tokenType);
		assertNotNull(result.scopes);
		assertEquals(2, result.scopes.length);
		assertEquals("read", result.scopes[0]);
		assertEquals("write", result.scopes[1]);
	}

	@Test
	public void testExpiredToken() throws Exception {
		OAuthRealm realm = createRealm(null);

		long pastExp = System.currentTimeMillis() / 1000 - 3600;
		String payload = "{\"sub\":\"testuser\",\"exp\":" + pastExp + "}";
		String token = createHS256JWT("{\"alg\":\"HS256\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNull(result);
	}

	@Test
	public void testNotYetValidToken() throws Exception {
		OAuthRealm realm = createRealm(null);

		long futureNbf = System.currentTimeMillis() / 1000 + 3600;
		long futureExp = futureNbf + 7200;
		String payload = "{\"sub\":\"testuser\",\"nbf\":" + futureNbf +
				",\"exp\":" + futureExp + "}";
		String token = createHS256JWT("{\"alg\":\"HS256\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNull(result);
	}

	@Test
	public void testInvalidSignature() throws Exception {
		OAuthRealm realm = createRealm(null);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		String payload = "{\"sub\":\"testuser\",\"exp\":" + futureExp + "}";
		String token = createHS256JWT("{\"alg\":\"HS256\"}", payload, "wrong-secret");

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNull(result);
	}

	@Test
	public void testIssuerValidation() throws Exception {
		Properties extra = new Properties();
		extra.setProperty("oauth.jwt.issuer", "https://auth.example.com");
		OAuthRealm realm = createRealm(extra);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		String payloadOk = "{\"sub\":\"testuser\",\"iss\":\"https://auth.example.com\",\"exp\":" + futureExp + "}";
		String tokenOk = createHS256JWT("{\"alg\":\"HS256\"}", payloadOk, TEST_SECRET);

		Realm.TokenValidationResult resultOk = realm.validateJWT(tokenOk);
		assertNotNull(resultOk);
		assertTrue(resultOk.valid);

		String payloadBad = "{\"sub\":\"testuser\",\"iss\":\"https://evil.example.com\",\"exp\":" + futureExp + "}";
		String tokenBad = createHS256JWT("{\"alg\":\"HS256\"}", payloadBad, TEST_SECRET);

		Realm.TokenValidationResult resultBad = realm.validateJWT(tokenBad);
		assertNull(resultBad);
	}

	@Test
	public void testAudienceValidation() throws Exception {
		Properties extra = new Properties();
		extra.setProperty("oauth.jwt.audience", "my-service");
		OAuthRealm realm = createRealm(extra);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		String payloadOk = "{\"sub\":\"testuser\",\"aud\":\"my-service\",\"exp\":" + futureExp + "}";
		String tokenOk = createHS256JWT("{\"alg\":\"HS256\"}", payloadOk, TEST_SECRET);

		Realm.TokenValidationResult resultOk = realm.validateJWT(tokenOk);
		assertNotNull(resultOk);
		assertTrue(resultOk.valid);

		String payloadBad = "{\"sub\":\"testuser\",\"aud\":\"other-service\",\"exp\":" + futureExp + "}";
		String tokenBad = createHS256JWT("{\"alg\":\"HS256\"}", payloadBad, TEST_SECRET);

		Realm.TokenValidationResult resultBad = realm.validateJWT(tokenBad);
		assertNull(resultBad);
	}

	@Test
	public void testNoSubjectReturnsNull() throws Exception {
		OAuthRealm realm = createRealm(null);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		String payload = "{\"scope\":\"read\",\"exp\":" + futureExp + "}";
		String token = createHS256JWT("{\"alg\":\"HS256\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNull(result);
	}

	@Test
	public void testUsernameClaimPreferred() throws Exception {
		OAuthRealm realm = createRealm(null);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		String payload = "{\"sub\":\"subject-id\",\"username\":\"preferred-name\",\"exp\":" + futureExp + "}";
		String token = createHS256JWT("{\"alg\":\"HS256\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNotNull(result);
		assertEquals("preferred-name", result.username);
	}

	@Test
	public void testClockSkewTolerance() throws Exception {
		Properties extra = new Properties();
		extra.setProperty("oauth.jwt.clock.skew", "60");
		OAuthRealm realm = createRealm(extra);

		// Token expired 30 seconds ago — within 60s clock skew
		long recentExp = System.currentTimeMillis() / 1000 - 30;
		String payload = "{\"sub\":\"testuser\",\"exp\":" + recentExp + "}";
		String token = createHS256JWT("{\"alg\":\"HS256\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNotNull(result);
		assertTrue(result.valid);
	}

	@Test
	public void testMalformedTokenReturnsNull() throws Exception {
		OAuthRealm realm = createRealm(null);

		assertNull(realm.validateJWT("not.a.jwt"));
		assertNull(realm.validateJWT("only-one-part"));
		assertNull(realm.validateJWT("two.parts"));
	}

	@Test
	public void testNoAlgHeaderReturnsNull() throws Exception {
		OAuthRealm realm = createRealm(null);

		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String header = enc.encodeToString("{\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString("{\"sub\":\"testuser\"}".getBytes(StandardCharsets.UTF_8));
		String token = header + "." + payload + "." + enc.encodeToString(new byte[32]);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNull(result);
	}

	@Test
	public void testUnsupportedAlgorithmReturnsNull() throws Exception {
		OAuthRealm realm = createRealm(null);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		String payload = "{\"sub\":\"testuser\",\"exp\":" + futureExp + "}";
		// PS256 is not supported
		String token = createHS256JWT("{\"alg\":\"PS256\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNull(result);
	}

	// ========== RS256 tests ==========

	@Test
	public void testRS256Validation() throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(2048);
		KeyPair kp = kpg.generateKeyPair();

		Properties extra = new Properties();
		extra.setProperty("oauth.jwt.public.key",
				Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
		OAuthRealm realm = createRealm(extra);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString(
				("{\"sub\":\"rsauser\",\"exp\":" + futureExp + "}")
						.getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + payload;

		java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
		sig.initSign(kp.getPrivate());
		sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
		byte[] signature = sig.sign();

		String token = signingInput + "." + enc.encodeToString(signature);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNotNull(result);
		assertTrue(result.valid);
		assertEquals("rsauser", result.username);
	}

	// ========== ES256 tests ==========

	@Test
	public void testES256Validation() throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
		kpg.initialize(256);
		KeyPair kp = kpg.generateKeyPair();

		Properties extra = new Properties();
		extra.setProperty("oauth.jwt.public.key",
				Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
		OAuthRealm realm = createRealm(extra);

		long futureExp = System.currentTimeMillis() / 1000 + 3600;
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String header = enc.encodeToString("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString(
				("{\"sub\":\"ecuser\",\"scope\":\"admin\",\"exp\":" + futureExp + "}")
						.getBytes(StandardCharsets.UTF_8));
		String signingInput = header + "." + payload;

		java.security.Signature sig = java.security.Signature.getInstance("SHA256withECDSA");
		sig.initSign(kp.getPrivate());
		sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
		byte[] signature = sig.sign();

		String token = signingInput + "." + enc.encodeToString(signature);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNotNull(result);
		assertTrue(result.valid);
		assertEquals("ecuser", result.username);
		assertEquals(1, result.scopes.length);
		assertEquals("admin", result.scopes[0]);
	}

	// ========== Token without expiration ==========

	@Test
	public void testTokenWithoutExpiration() throws Exception {
		OAuthRealm realm = createRealm(null);

		String payload = "{\"sub\":\"testuser\",\"scope\":\"read\"}";
		String token = createHS256JWT("{\"alg\":\"HS256\"}", payload, TEST_SECRET);

		Realm.TokenValidationResult result = realm.validateJWT(token);
		assertNotNull(result);
		assertTrue(result.valid);
		assertEquals(0, result.expirationTime);
	}

	// ========== JWT disabled ==========

	@Test
	public void testJWTDisabledByDefault() {
		Properties config = new Properties();
		config.setProperty("oauth.authorization.server.url", "https://auth.example.com");
		config.setProperty("oauth.client.id", "test-client");
		config.setProperty("oauth.client.secret", "test-secret");
		OAuthRealm realm = new OAuthRealm(config);

		// When JWT is disabled, validateJWT should still work but won't be called by validateOAuthToken
		// We test validateJWT directly — it should return null since no secret is configured
		Realm.TokenValidationResult result = realm.validateJWT("not.a.real-token");
		assertNull(result);
	}
}
