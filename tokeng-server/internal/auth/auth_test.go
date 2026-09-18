package auth

import (
	"testing"
	"time"
)

func TestPasswordHashing(t *testing.T) {
	pw := "SecretPassword123"
	hash, err := HashPassword(pw)
	if err != nil {
		t.Fatalf("HashPassword failed: %v", err)
	}
	if !CheckPassword(pw, hash) {
		t.Errorf("CheckPassword returned false for valid password")
	}
	if CheckPassword("WrongPassword", hash) {
		t.Errorf("CheckPassword returned true for invalid password")
	}
}

func TestJWTTokens(t *testing.T) {
	secret := "super-secure-secret-key-for-testing"
	token, exp, err := GenerateToken("user-123", "test@gmail.com", secret, 1*time.Hour)
	if err != nil {
		t.Fatalf("GenerateToken failed: %v", err)
	}
	if token == "" || exp.Before(time.Now()) {
		t.Fatalf("Invalid token or expiry")
	}

	claims, err := VerifyToken(token, secret)
	if err != nil {
		t.Fatalf("VerifyToken failed: %v", err)
	}
	if claims.UserID != "user-123" || claims.Email != "test@gmail.com" {
		t.Errorf("Unexpected claims: %+v", claims)
	}

	// Test invalid secret
	_, err = VerifyToken(token, "wrong-secret")
	if err == nil {
		t.Errorf("VerifyToken should fail with wrong secret")
	}
}
