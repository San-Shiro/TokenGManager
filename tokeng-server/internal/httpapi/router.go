package httpapi

import (
	"net/http"

	"github.com/San-Shiro/tokeng-server/internal/config"
	"github.com/San-Shiro/tokeng-server/internal/store"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Deps struct {
	Config        config.Config
	Pool          *pgxpool.Pool
	UserStore     *store.UserStore
	InstanceStore *store.InstanceStore
}

func NewRouter(deps Deps) http.Handler {
	mux := http.NewServeMux()

	authH := NewAuthHandler(deps.UserStore, deps.Config.JWTSecret, deps.Config.TokenTTL)
	syncH := NewSyncHandler(deps.InstanceStore)
	metaH := NewMetaHandler(deps.Pool, deps.InstanceStore)

	// Public routes
	mux.HandleFunc("GET /health", metaH.Health)
	mux.HandleFunc("GET /api/stats", metaH.Stats)
	mux.HandleFunc("POST /api/auth/register", authH.Register)
	mux.HandleFunc("POST /api/auth/login", authH.Login)

	// Protected routes (require Bearer JWT)
	requireAuth := RequireAuth(deps.Config.JWTSecret)
	mux.Handle("GET /api/sync/pull", requireAuth(http.HandlerFunc(syncH.SyncPull)))
	mux.Handle("POST /api/sync/push", requireAuth(http.HandlerFunc(syncH.SyncPush)))

	// Middleware chain: CORS -> RecoverPanic -> RateLimiter -> MaxBodySize -> Timeout -> Mux
	rateLimiter := NewRateLimiter(deps.Config.RateLimitRPS, deps.Config.RateLimitBurst)
	maxBody := MaxBodySize(1048576) // 1MB
	timeout := RequestTimeout(deps.Config.RequestTimeout)

	var handler http.Handler = mux
	handler = timeout(handler)
	handler = maxBody(handler)
	handler = rateLimiter.Middleware(handler)
	handler = RecoverPanic(handler)
	handler = CORS(handler)

	return handler
}
