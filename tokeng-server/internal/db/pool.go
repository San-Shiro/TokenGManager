package db

import (
	"context"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/config"
	"github.com/jackc/pgx/v5/pgxpool"
)

// NewPool initializes and tests a pgx connection pool
func NewPool(ctx context.Context, cfg config.Config) (*pgxpool.Pool, error) {
	poolConfig, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return nil, err
	}

	poolConfig.MaxConns = cfg.DBMaxConns
	poolConfig.MinConns = cfg.DBMinConns
	poolConfig.MaxConnLifetime = 30 * time.Minute
	poolConfig.MaxConnIdleTime = 5 * time.Minute
	poolConfig.HealthCheckPeriod = 30 * time.Second

	// Enforce defense-in-depth timeouts at the connection level
	if poolConfig.ConnConfig.RuntimeParams == nil {
		poolConfig.ConnConfig.RuntimeParams = make(map[string]string)
	}
	poolConfig.ConnConfig.RuntimeParams["statement_timeout"] = "5000"                     // 5s
	poolConfig.ConnConfig.RuntimeParams["idle_in_transaction_session_timeout"] = "10000" // 10s
	poolConfig.ConnConfig.RuntimeParams["search_path"] = "tokeng,public"

	pool, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return nil, err
	}

	// Immediate connectivity check
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, err
	}

	return pool, nil
}
