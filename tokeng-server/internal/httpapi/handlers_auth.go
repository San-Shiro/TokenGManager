package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/auth"
	"github.com/San-Shiro/tokeng-server/internal/model"
	"github.com/San-Shiro/tokeng-server/internal/store"
)

var emailRegex = regexp.MustCompile(`^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`)

type AuthHandler struct {
	userStore *store.UserStore
	jwtSecret string
	tokenTTL  time.Duration
}

func NewAuthHandler(userStore *store.UserStore, jwtSecret string, tokenTTL time.Duration) *AuthHandler {
	return &AuthHandler{
		userStore: userStore,
		jwtSecret: jwtSecret,
		tokenTTL:  tokenTTL,
	}
}

func (h *AuthHandler) Register(w http.ResponseWriter, r *http.Request) {
	var req model.RegisterRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "request body is not valid JSON")
		return
	}

	email := strings.ToLower(strings.TrimSpace(req.Email))
	if !emailRegex.MatchString(email) {
		writeError(w, http.StatusBadRequest, "invalid_email", "valid email address is required")
		return
	}

	if len(req.Password) < 8 {
		writeError(w, http.StatusBadRequest, "weak_password", "password must be at least 8 characters long")
		return
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "hash_failed", "failed to hash password")
		return
	}

	userID, err := h.userStore.CreateUser(r.Context(), email, hash)
	if err != nil {
		if errors.Is(err, store.ErrUserAlreadyExists) {
			writeError(w, http.StatusConflict, "user_exists", "an account with this email already exists")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "failed to create user")
		return
	}

	token, expiresAt, err := auth.GenerateToken(userID, email, h.jwtSecret, h.tokenTTL)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "token_error", "failed to generate authentication token")
		return
	}

	writeJSON(w, http.StatusCreated, model.AuthResponse{
		UserID:    userID,
		Email:     email,
		Token:     token,
		ExpiresAt: expiresAt,
	})
}

func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
	var req model.LoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "request body is not valid JSON")
		return
	}

	email := strings.ToLower(strings.TrimSpace(req.Email))
	user, err := h.userStore.GetUserByEmail(r.Context(), email)
	if err != nil {
		// Do not leak whether user exists
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "incorrect email or password")
		return
	}

	if !auth.CheckPassword(req.Password, user.PasswordHash) {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "incorrect email or password")
		return
	}

	// Update last login in background
	go func() {
		_ = h.userStore.UpdateLastLogin(r.Context(), user.ID)
	}()

	token, expiresAt, err := auth.GenerateToken(user.ID, user.Email, h.jwtSecret, h.tokenTTL)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "token_error", "failed to generate authentication token")
		return
	}

	writeJSON(w, http.StatusOK, model.AuthResponse{
		UserID:    user.ID,
		Email:     user.Email,
		Token:     token,
		ExpiresAt: expiresAt,
	})
}
