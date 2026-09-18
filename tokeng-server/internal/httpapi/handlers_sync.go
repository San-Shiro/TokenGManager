package httpapi

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/model"
	"github.com/San-Shiro/tokeng-server/internal/store"
)

type SyncHandler struct {
	instanceStore *store.InstanceStore
}

func NewSyncHandler(instanceStore *store.InstanceStore) *SyncHandler {
	return &SyncHandler{instanceStore: instanceStore}
}

func (h *SyncHandler) SyncPull(w http.ResponseWriter, r *http.Request) {
	userID := GetUserIDFromContext(r.Context())
	if userID == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized", "missing user context")
		return
	}

	sinceStr := r.URL.Query().Get("since")
	since := time.Unix(0, 0)
	if sinceStr != "" {
		parsedTime, err := time.Parse(time.RFC3339, sinceStr)
		if err == nil {
			since = parsedTime
		}
	}

	instances, err := h.instanceStore.PullDelta(r.Context(), userID, since)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "pull_failed", "failed to retrieve token instances")
		return
	}

	writeJSON(w, http.StatusOK, model.SyncPullResponse{
		ServerTime: time.Now().UTC(),
		Since:      since,
		Instances:  instances,
	})
}

func (h *SyncHandler) SyncPush(w http.ResponseWriter, r *http.Request) {
	userID := GetUserIDFromContext(r.Context())
	if userID == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized", "missing user context")
		return
	}

	var req model.SyncPushRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "request body is not valid JSON")
		return
	}

	accepted, skippedStale, err := h.instanceStore.PushDelta(r.Context(), userID, req.Instances)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "push_failed", "failed to upsert token instances")
		return
	}

	writeJSON(w, http.StatusOK, model.SyncPushResponse{
		ServerTime:   time.Now().UTC(),
		Accepted:     accepted,
		SkippedStale: skippedStale,
	})
}
