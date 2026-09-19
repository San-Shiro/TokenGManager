package httpapi

import (
	"context"
	"net/http"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/model"
	"github.com/San-Shiro/tokeng-server/internal/store"
	"github.com/jackc/pgx/v5/pgxpool"
)

type MetaHandler struct {
	pool          *pgxpool.Pool
	instanceStore *store.InstanceStore
}

func NewMetaHandler(pool *pgxpool.Pool, instanceStore *store.InstanceStore) *MetaHandler {
	return &MetaHandler{
		pool:          pool,
		instanceStore: instanceStore,
	}
}

const ServerVersion = "7.1.0"

func (h *MetaHandler) Health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	if err := h.pool.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, model.HealthResponse{
			Status:  "degraded",
			DB:      "down",
			Version: ServerVersion,
			Time:    time.Now().UTC().Format(time.RFC3339),
		})
		return
	}

	writeJSON(w, http.StatusOK, model.HealthResponse{
		Status:  "ok",
		DB:      "up",
		Version: ServerVersion,
		Time:    time.Now().UTC().Format(time.RFC3339),
	})
}

func (h *MetaHandler) Stats(w http.ResponseWriter, r *http.Request) {
	stats, err := h.instanceStore.GetStats(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "stats_error", "failed to calculate system stats")
		return
	}

	writeJSON(w, http.StatusOK, stats)
}
