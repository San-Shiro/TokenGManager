// Package server provides an HTTP API for token operations.
// This allows any device (iOS, web, scripts) to fetch tokens remotely.
package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/nicksrandall/gauth/internal/auth"
	"github.com/nicksrandall/gauth/internal/config"
)

// TokenRequest is the JSON body for /api/token.
type TokenRequest struct {
	Scope      string `json:"scope"`       // OAuth2 scope or service name
	AppPackage string `json:"app_package"` // optional, defaults to Google
	AppSig     string `json:"app_sig"`     // optional, defaults to Google sig
}

// TokenResponse is the JSON response.
type TokenResponse struct {
	Token         string `json:"token"`
	Email         string `json:"email"`
	TokenType     string `json:"token_type"` // "oauth2" or "aes"
	GrantedScopes string `json:"granted_scopes,omitempty"`
	Error         string `json:"error,omitempty"`
}

// StatusResponse is the JSON response for /api/status.
type StatusResponse struct {
	Registered bool   `json:"registered"`
	LoggedIn   bool   `json:"logged_in"`
	Email      string `json:"email,omitempty"`
	AndroidID  string `json:"android_id,omitempty"`
}

// Start launches the HTTP server on the given port.
func Start(cfg *config.Config, port int) error {
	mux := http.NewServeMux()

	// Ring buffer logger (last 500 requests)
	rl := NewRingLogger(500)

	// Proxy state for browser login
	proxyState := NewProxyState()

	// Status endpoint
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		resp := StatusResponse{
			Registered: cfg.HasRegistration(),
			LoggedIn:   cfg.HasMasterToken(),
			Email:      cfg.Email,
			AndroidID:  cfg.AndroidID,
		}
		writeJSON(w, http.StatusOK, resp)
		rl.Info("/api/status", r.Method, 200, "status check", time.Since(start), r.RemoteAddr)
	})

	// Login status poll endpoint (for browser login page)
	mux.HandleFunc("/api/login-status", func(w http.ResponseWriter, r *http.Request) {
		proxyState.mu.Lock()
		resp := map[string]interface{}{
			"captured": proxyState.captured,
			"email":    proxyState.email,
			"error":    proxyState.error,
		}
		proxyState.mu.Unlock()
		writeJSON(w, http.StatusOK, resp)
	})

	// Proxy extract trigger — called by injected JS when closeView fires
	mux.HandleFunc("/api/proxy-extract", func(w http.ResponseWriter, r *http.Request) {
		if proxyState.IsCaptured() {
			writeJSON(w, http.StatusOK, map[string]interface{}{
				"success": true,
				"email":   cfg.Email,
			})
		} else {
			writeJSON(w, http.StatusOK, map[string]interface{}{
				"success": false,
				"message": "token not yet captured",
			})
		}
	})

	// Fetch token endpoint
	mux.HandleFunc("/api/token", func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		if r.Method != "POST" && r.Method != "GET" {
			writeJSON(w, http.StatusMethodNotAllowed, TokenResponse{Error: "use POST or GET"})
			rl.Error("/api/token", r.Method, 405, "method not allowed", time.Since(start), r.RemoteAddr)
			return
		}

		if !cfg.HasMasterToken() {
			writeJSON(w, http.StatusUnauthorized, TokenResponse{Error: "not logged in; use /login or run 'gauth login' first"})
			rl.Error("/api/token", r.Method, 401, "not logged in", time.Since(start), r.RemoteAddr)
			return
		}

		var req TokenRequest

		if r.Method == "POST" {
			if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
				writeJSON(w, http.StatusBadRequest, TokenResponse{Error: "invalid JSON: " + err.Error()})
				rl.Error("/api/token", r.Method, 400, "invalid JSON: "+err.Error(), time.Since(start), r.RemoteAddr)
				return
			}
		} else {
			req.Scope = r.URL.Query().Get("scope")
			req.AppPackage = r.URL.Query().Get("app")
			req.AppSig = r.URL.Query().Get("sig")
		}

		// Resolve known app shortcuts
		origScope := req.Scope
		if app, ok := auth.KnownApps[req.Scope]; ok {
			req.Scope = app.Scope
			if req.AppPackage == "" {
				req.AppPackage = app.Package
			}
		}

		if req.Scope == "" {
			writeJSON(w, http.StatusBadRequest, TokenResponse{Error: "scope is required. Try: photos, youtube, gmail, drive, or a full OAuth2 scope"})
			rl.Error("/api/token", r.Method, 400, "missing scope", time.Since(start), r.RemoteAddr)
			return
		}

		// Defaults
		if req.AppPackage == "" {
			req.AppPackage = "com.google.android.gms"
		}
		if req.AppSig == "" {
			req.AppSig = auth.GoogleSig
		}

		resp, err := auth.FetchServiceToken(cfg, req.Scope, req.AppPackage, req.AppSig)
		if err != nil {
			log.Printf("[server] Token fetch error: %v", err)
			writeJSON(w, http.StatusInternalServerError, TokenResponse{Error: err.Error()})
			rl.Error("/api/token", r.Method, 500, "fetch error: "+err.Error(), time.Since(start), r.RemoteAddr)
			return
		}

		if resp.Auth == "" {
			writeJSON(w, http.StatusInternalServerError, TokenResponse{Error: "empty token in response"})
			rl.Error("/api/token", r.Method, 500, "empty token", time.Since(start), r.RemoteAddr)
			return
		}

		tokenType := "unknown"
		if len(resp.Auth) > 7 && resp.Auth[:7] == "aas_et/" {
			tokenType = "aes"
		} else if len(resp.Auth) > 5 && resp.Auth[:5] == "ya29." {
			tokenType = "oauth2"
		}

		writeJSON(w, http.StatusOK, TokenResponse{
			Token:         resp.Auth,
			Email:         cfg.Email,
			TokenType:     tokenType,
			GrantedScopes: resp.GrantedScopes,
		})

		// Truncate token for log
		tokPreview := resp.Auth
		if len(tokPreview) > 15 {
			tokPreview = tokPreview[:10] + "..." + tokPreview[len(tokPreview)-5:]
		}
		rl.Info("/api/token", r.Method, 200, fmt.Sprintf("scope=%s type=%s token=%s", origScope, tokenType, tokPreview), time.Since(start), r.RemoteAddr)
	})

	// Known apps list
	mux.HandleFunc("/api/apps", func(w http.ResponseWriter, r *http.Request) {
		apps := make(map[string]interface{})
		for name, app := range auth.KnownApps {
			apps[name] = map[string]string{
				"package": app.Package,
				"scope":   app.Scope,
			}
		}
		writeJSON(w, http.StatusOK, apps)
		rl.Info("/api/apps", r.Method, 200, "apps list", 0, r.RemoteAddr)
	})

	// === LOGS API ===
	// Retrieve recent request logs
	mux.HandleFunc("/api/logs", func(w http.ResponseWriter, r *http.Request) {
		limit := 100 // default
		if l := r.URL.Query().Get("limit"); l != "" {
			if n, err := strconv.Atoi(l); err == nil && n > 0 {
				limit = n
			}
		}
		entries := rl.Entries(limit)
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"count":   len(entries),
			"entries": entries,
		})
	})

	// Log statistics
	mux.HandleFunc("/api/logs/stats", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, rl.Stats())
	})

	// === BROWSER LOGIN FLOW ===
	// Login landing page
	mux.HandleFunc("/login", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprint(w, loginPageHTML)
	})

	// Google login proxy
	mux.Handle("/glogin/", googleProxyHandler(cfg, proxyState, port))

	// Static resource proxy
	mux.Handle("/gproxy/", staticProxyHandler(cfg, port))

	// Web UI (existing) + catch-all for Google paths that bypass /glogin/
	// Google's JS sometimes constructs URLs relative to the origin, skipping
	// our /glogin/ prefix. We catch known Google Accounts paths and proxy them.
	googlePaths := []string{
		"/lifecycle/", "/v3/", "/signin/", "/o/",
		"/speedbump/", "/webreauth/", "/challenge/",
		"/AccountChooser", "/ServiceLogin",
		"/embedded/", "/CheckCookie",
	}
	googleProxy := googleProxyHandler(cfg, proxyState, port)

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path

		// Check if this looks like a Google Accounts path
		for _, prefix := range googlePaths {
			if strings.HasPrefix(path, prefix) {
				// Rewrite to /glogin/... and serve through proxy
				r.URL.Path = "/glogin" + path
				googleProxy.ServeHTTP(w, r)
				return
			}
		}

		if path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html")
		fmt.Fprintf(w, webUI, port)
	})

	addr := fmt.Sprintf(":%d", port)
	log.Printf("[gauth] Server starting on http://localhost%s", addr)
	log.Printf("[gauth] Endpoints:")
	log.Printf("  GET  /login          — Browser-based Google sign-in")
	log.Printf("  GET  /api/status")
	log.Printf("  POST /api/token      {\"scope\": \"photos\"}")
	log.Printf("  GET  /api/token?scope=photos")
	log.Printf("  GET  /api/apps")
	log.Printf("  GET  /api/logs       — Request logs (last 500)")
	log.Printf("  GET  /api/logs/stats — Log statistics")

	return http.ListenAndServe(addr, mux)
}

func writeJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

const webUI = `<!DOCTYPE html>
<html>
<head>
	<meta charset="utf-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>gauth — Token Server</title>
	<style>
		* { margin: 0; padding: 0; box-sizing: border-box; }
		body {
			font-family: 'Inter', -apple-system, sans-serif;
			background: #0f0f23;
			color: #e0e0e0;
			min-height: 100vh;
			padding: 2rem;
		}
		h1 { color: #4fc3f7; margin-bottom: 0.5rem; }
		.subtitle { color: #888; margin-bottom: 2rem; }
		.card {
			background: #1a1a2e;
			border: 1px solid #333;
			border-radius: 12px;
			padding: 1.5rem;
			margin-bottom: 1.5rem;
		}
		.status { display: flex; gap: 1rem; align-items: center; }
		.dot { width: 12px; height: 12px; border-radius: 50%%; }
		.dot.green { background: #4caf50; }
		.dot.red { background: #f44336; }
		label { display: block; color: #aaa; margin-bottom: 0.5rem; font-size: 0.9em; }
		select, input {
			width: 100%%; padding: 0.75rem;
			background: #0f0f23; color: #fff;
			border: 1px solid #444; border-radius: 8px;
			font-size: 1rem; margin-bottom: 1rem;
		}
		button {
			background: linear-gradient(135deg, #4fc3f7, #2196f3);
			color: #fff; border: none; padding: 0.75rem 2rem;
			border-radius: 8px; cursor: pointer; font-size: 1rem;
			transition: opacity 0.2s;
		}
		button:hover { opacity: 0.85; }
		.result {
			background: #0a0a1a; border: 1px solid #333;
			border-radius: 8px; padding: 1rem;
			word-break: break-all; font-family: monospace;
			font-size: 0.9rem; margin-top: 1rem;
			max-height: 200px; overflow-y: auto;
		}
		.result.error { border-color: #f44336; color: #f44336; }
		.result.success { border-color: #4caf50; }
	</style>
</head>
<body>
	<h1>🔐 gauth</h1>
	<p class="subtitle">Google Token Server — running on port %d</p>

	<div class="card" id="statusCard">
		<h3>Status</h3>
		<div class="status" id="statusContent">Loading...</div>
	</div>

	<div class="card">
		<h3>Fetch Token</h3>
		<label>Service</label>
		<select id="scope">
			<option value="photos">Google Photos</option>
			<option value="youtube">YouTube</option>
			<option value="gmail">Gmail</option>
			<option value="drive">Google Drive</option>
			<option value="calendar">Google Calendar</option>
			<option value="custom">Custom scope...</option>
		</select>
		<div id="customScopeDiv" style="display:none">
			<label>Custom OAuth2 Scope</label>
			<input type="text" id="customScope" placeholder="oauth2:https://www.googleapis.com/auth/...">
		</div>
		<button onclick="fetchToken()">Get Token</button>
		<div id="result" class="result" style="display:none"></div>
	</div>

	<script>
		document.getElementById('scope').onchange = function() {
			document.getElementById('customScopeDiv').style.display =
				this.value === 'custom' ? 'block' : 'none';
		};

		fetch('/api/status').then(r=>r.json()).then(s => {
			let html = '';
			html += '<span class="dot ' + (s.registered ? 'green' : 'red') + '"></span>';
			html += '<span>Registered: ' + (s.registered ? 'Yes' : 'No') + '</span>';
			html += '<span class="dot ' + (s.logged_in ? 'green' : 'red') + '"></span>';
			html += '<span>Logged in: ' + (s.logged_in ? s.email : 'No') + '</span>';
			document.getElementById('statusContent').innerHTML = html;
		});

		function fetchToken() {
			let scope = document.getElementById('scope').value;
			if (scope === 'custom') scope = document.getElementById('customScope').value;
			const el = document.getElementById('result');
			el.style.display = 'block';
			el.className = 'result';
			el.textContent = 'Fetching...';

			fetch('/api/token?scope=' + encodeURIComponent(scope))
				.then(r => r.json())
				.then(data => {
					if (data.error) {
						el.className = 'result error';
						el.textContent = 'Error: ' + data.error;
					} else {
						el.className = 'result success';
						el.textContent = data.token_type.toUpperCase() + ' Token:\n' + data.token;
					}
				})
				.catch(err => {
					el.className = 'result error';
					el.textContent = 'Network error: ' + err.message;
				});
		}
	</script>
</body>
</html>`
