package config

import (
	"os"
	"strconv"
	"time"
)

// Config holds all runtime application configurations
type Config struct {
	Port           string
	DatabaseURL    string
	JWTSecret      string
	TokenTTL       time.Duration
	RateLimitRPS   float64
	RateLimitBurst int
	DBMaxConns     int32
	DBMinConns     int32
	RequestTimeout time.Duration
}

// Load loads configuration from environment variables with safe defaults
func Load() Config {
	port := getEnv("PORT", "8088")
	dbURL := getEnv("DATABASE_URL", "postgres://tokeng_api_user:ChangeMeSecurePass@169.58.138.108:25432/TokenG?sslmode=require")
	jwtSecret := getEnv("JWT_SECRET", "tokeng-default-development-jwt-secret-key-32bytes!")

	tokenTTLStr := getEnv("TOKEN_TTL", "720h")
	tokenTTL, err := time.ParseDuration(tokenTTLStr)
	if err != nil {
		tokenTTL = 720 * time.Hour
	}

	rpsStr := getEnv("RATE_LIMIT_RPS", "10")
	rps, err := strconv.ParseFloat(rpsStr, 64)
	if err != nil {
		rps = 10.0
	}

	burstStr := getEnv("RATE_LIMIT_BURST", "20")
	burst, err := strconv.Atoi(burstStr)
	if err != nil {
		burst = 20
	}

	maxConnsStr := getEnv("DB_MAX_CONNS", "15")
	maxConns, err := strconv.Atoi(maxConnsStr)
	if err != nil {
		maxConns = 15
	}

	minConnsStr := getEnv("DB_MIN_CONNS", "2")
	minConns, err := strconv.Atoi(minConnsStr)
	if err != nil {
		minConns = 2
	}

	reqTimeoutStr := getEnv("REQUEST_TIMEOUT", "10s")
	reqTimeout, err := time.ParseDuration(reqTimeoutStr)
	if err != nil {
		reqTimeout = 10 * time.Second
	}

	return Config{
		Port:           port,
		DatabaseURL:    dbURL,
		JWTSecret:      jwtSecret,
		TokenTTL:       tokenTTL,
		RateLimitRPS:   rps,
		RateLimitBurst: burst,
		DBMaxConns:     int32(maxConns),
		DBMinConns:     int32(minConns),
		RequestTimeout: reqTimeout,
	}
}

func getEnv(key, defaultVal string) string {
	if val, ok := os.LookupEnv(key); ok && val != "" {
		return val
	}
	return defaultVal
}
