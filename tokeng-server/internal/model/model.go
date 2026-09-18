package model

import "time"

// User represents an authenticated account holder
type User struct {
	ID           string     `json:"id"`
	Email        string     `json:"email"`
	PasswordHash string     `json:"-"`
	CreatedAt    time.Time  `json:"created_at"`
	LastLoginAt  *time.Time `json:"last_login_at,omitempty"`
}

// TokenInstance represents a physical or virtual device token instance
type TokenInstance struct {
	InstanceID           string     `json:"instance_id"`
	UserID               string     `json:"user_id,omitempty"`
	Email                string     `json:"email"`
	MasterToken          string     `json:"master_token"`
	AasToken             *string    `json:"aas_token,omitempty"`
	Sid                  *string    `json:"sid,omitempty"`
	Lsid                 *string    `json:"lsid,omitempty"`
	AndroidID            *string    `json:"android_id,omitempty"`
	GsfID                *string    `json:"gsf_id,omitempty"`
	SecurityToken        *string    `json:"security_token,omitempty"`
	DeviceName           *string    `json:"device_name,omitempty"`
	DeviceModel          *string    `json:"device_model,omitempty"`
	DeviceBrand          *string    `json:"device_brand,omitempty"`
	DeviceFingerprint    *string    `json:"device_fingerprint,omitempty"`
	DeviceSDK            *int       `json:"device_sdk,omitempty"`
	AccountStatus        string     `json:"account_status"`
	LastValidatedAt      *time.Time `json:"last_validated_at,omitempty"`
	LastValidationResult *string    `json:"last_validation_result,omitempty"`
	SignedOutReason      *string    `json:"signed_out_reason,omitempty"`
	Deleted              bool       `json:"deleted"`
	CreatedAt            time.Time  `json:"created_at"`
	UpdatedAt            time.Time  `json:"updated_at"`
}

// RegisterRequest payload for /api/auth/register
type RegisterRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

// LoginRequest payload for /api/auth/login
type LoginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

// AuthResponse returns on successful register or login
type AuthResponse struct {
	UserID    string    `json:"user_id"`
	Email     string    `json:"email"`
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
}

// SyncPullResponse returns on GET /api/sync/pull
type SyncPullResponse struct {
	ServerTime time.Time       `json:"server_time"`
	Since      time.Time       `json:"since"`
	Instances  []TokenInstance `json:"instances"`
}

// SyncPushRequest payload for POST /api/sync/push
type SyncPushRequest struct {
	Instances []TokenInstance `json:"instances"`
}

// SyncPushResponse returns on POST /api/sync/push
type SyncPushResponse struct {
	ServerTime   time.Time `json:"server_time"`
	Accepted     int       `json:"accepted"`
	SkippedStale int       `json:"skipped_stale"`
}

// GlobalStatsResponse returns on GET /api/stats
type GlobalStatsResponse struct {
	TotalUsers     int64     `json:"total_users"`
	TotalInstances int64     `json:"total_instances"`
	ActiveTokens   int64     `json:"active_tokens"`
	ExpiredTokens  int64     `json:"expired_tokens"`
	DistinctGmails int64     `json:"distinct_gmails"`
	GeneratedAt    time.Time `json:"generated_at"`
}

// HealthResponse returns on GET /health
type HealthResponse struct {
	Status  string `json:"status"`
	DB      string `json:"db"`
	Version string `json:"version"`
	Time    string `json:"time"`
}

// ErrorResponse standard error envelope
type ErrorResponse struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}
