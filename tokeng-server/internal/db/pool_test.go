package db

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/config"
)

func TestPoolConnection(t *testing.T) {
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		dbURL = "postgres://tokeng_api_user:eqO51UMjM2P6lQHJWquqJ3lYb3j73hXvuHPsYIyT@169.58.138.108:25432/TokenG?sslmode=require"
	}
	cfg := config.Config{
		DatabaseURL: dbURL,
		DBMaxConns:  5,
		DBMinConns:  1,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	pool, err := NewPool(ctx, cfg)
	if err != nil {
		t.Fatalf("Failed to create and ping pool: %v", err)
	}
	defer pool.Close()

	var currentUser string
	err = pool.QueryRow(ctx, "SELECT current_user;").Scan(&currentUser)
	if err != nil {
		t.Fatalf("QueryRow failed: %v", err)
	}
	if currentUser != "tokeng_api_user" {
		t.Errorf("Expected user tokeng_api_user, got %s", currentUser)
	}
	t.Logf("Connected successfully as: %s", currentUser)
}
