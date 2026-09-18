package httpapi

import (
	"context"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/auth"
	"golang.org/x/time/rate"
)

type contextKey string

const (
	ctxKeyUserID contextKey = "userID"
	ctxKeyEmail  contextKey = "email"
)

// GetUserIDFromContext retrieves authenticated user ID
func GetUserIDFromContext(ctx context.Context) string {
	if val, ok := ctx.Value(ctxKeyUserID).(string); ok {
		return val
	}
	return ""
}

// GetEmailFromContext retrieves authenticated email
func GetEmailFromContext(ctx context.Context) string {
	if val, ok := ctx.Value(ctxKeyEmail).(string); ok {
		return val
	}
	return ""
}

// RequireAuth middleware verifies Bearer JWT token
func RequireAuth(jwtSecret string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			authHeader := r.Header.Get("Authorization")
			if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
				writeError(w, http.StatusUnauthorized, "unauthorized", "missing or invalid authorization header")
				return
			}

			tokenStr := strings.TrimPrefix(authHeader, "Bearer ")
			claims, err := auth.VerifyToken(tokenStr, jwtSecret)
			if err != nil {
				writeError(w, http.StatusUnauthorized, "unauthorized", "token is invalid or expired")
				return
			}

			ctx := context.WithValue(r.Context(), ctxKeyUserID, claims.UserID)
			ctx = context.WithValue(ctx, ctxKeyEmail, claims.Email)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// RateLimiter implements IP-based rate limiting
type RateLimiter struct {
	mu      sync.Mutex
	limiters map[string]*rate.Limiter
	r       rate.Limit
	b       int
}

func NewRateLimiter(rps float64, burst int) *RateLimiter {
	rl := &RateLimiter{
		limiters: make(map[string]*rate.Limiter),
		r:        rate.Limit(rps),
		b:        burst,
	}

	// Periodically cleanup idle limiters
	go func() {
		for {
			time.Sleep(10 * time.Minute)
			rl.mu.Lock()
			rl.limiters = make(map[string]*rate.Limiter)
			rl.mu.Unlock()
		}
	}()

	return rl
}

func (rl *RateLimiter) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			ip = r.RemoteAddr
		}

		rl.mu.Lock()
		limiter, exists := rl.limiters[ip]
		if !exists {
			limiter = rate.NewLimiter(rl.r, rl.b)
			rl.limiters[ip] = limiter
		}
		rl.mu.Unlock()

		if !limiter.Allow() {
			writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests, please slow down")
			return
		}

		next.ServeHTTP(w, r)
	})
}

// MaxBodySize limits incoming request payload
func MaxBodySize(limitBytes int64) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			r.Body = http.MaxBytesReader(w, r.Body, limitBytes)
			next.ServeHTTP(w, r)
		})
	}
}

// RequestTimeout bounds request duration
func RequestTimeout(d time.Duration) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.TimeoutHandler(next, d, `{"error":{"code":"timeout","message":"request timed out"}}`)
	}
}

// RecoverPanic recovers from panics and logs the incident
func RecoverPanic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("[PANIC RECOVERED] %v", rec)
				writeError(w, http.StatusInternalServerError, "internal_error", "an unexpected server error occurred")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// CORS adds CORS headers
func CORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key")

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next.ServeHTTP(w, r)
	})
}
