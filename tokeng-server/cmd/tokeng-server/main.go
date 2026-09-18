package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/config"
	"github.com/San-Shiro/tokeng-server/internal/db"
	"github.com/San-Shiro/tokeng-server/internal/httpapi"
	"github.com/San-Shiro/tokeng-server/internal/store"
)

const Version = "6.1.0"

func main() {
	healthCheckFlag := flag.Bool("healthcheck", false, "run healthcheck query against localhost and exit")
	flag.Parse()

	cfg := config.Load()

	// Docker healthcheck probe mode
	if *healthCheckFlag {
		url := fmt.Sprintf("http://127.0.0.1:%s/health", cfg.Port)
		client := http.Client{Timeout: 3 * time.Second}
		resp, err := client.Get(url)
		if err != nil || resp.StatusCode != http.StatusOK {
			os.Exit(1)
		}
		os.Exit(0)
	}

	log.Printf("[TOKEN-G] Starting tokeng-server v%s on port %s...", Version, cfg.Port)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Initialize DB Pool
	log.Printf("[TOKEN-G] Connecting to PostgreSQL database...")
	pool, err := db.NewPool(ctx, cfg)
	if err != nil {
		log.Fatalf("[TOKEN-G FATAL] Database connection failed: %v", err)
	}
	defer pool.Close()
	log.Printf("[TOKEN-G] Connected to PostgreSQL successfully (MaxConns: %d, MinConns: %d)", cfg.DBMaxConns, cfg.DBMinConns)

	userStore := store.NewUserStore(pool)
	instanceStore := store.NewInstanceStore(pool)

	router := httpapi.NewRouter(httpapi.Deps{
		Config:        cfg,
		Pool:          pool,
		UserStore:     userStore,
		InstanceStore: instanceStore,
	})

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      router,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Printf("[TOKEN-G] Server listening on http://0.0.0.0:%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[TOKEN-G FATAL] ListenAndServe failed: %v", err)
		}
	}()

	// Graceful shutdown listener
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, os.Interrupt, syscall.SIGTERM)
	<-quit

	log.Println("[TOKEN-G] Shutting down server gracefully...")
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("[TOKEN-G ERROR] Server forced to shutdown: %v", err)
	}
	log.Println("[TOKEN-G] Server exited cleanly.")
}
