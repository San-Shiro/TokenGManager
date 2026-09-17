// Package server — reverse proxy for browser-based Google login.
// Proxies accounts.google.com through localhost, injecting the mm JS bridge
// and capturing the HttpOnly oauth_token from Set-Cookie headers.
package server

import (
	"compress/gzip"
	"crypto/tls"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"

	"github.com/nicksrandall/gauth/internal/auth"
	"github.com/nicksrandall/gauth/internal/config"
	"github.com/nicksrandall/gauth/internal/login"
)

// ProxyState tracks whether a token has been captured.
type ProxyState struct {
	mu         sync.Mutex
	captured   bool
	oauthToken string
	email      string
	error      string
	listeners  []chan struct{}
}

func NewProxyState() *ProxyState {
	return &ProxyState{}
}

func (s *ProxyState) SetToken(token string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.oauthToken = token
	s.captured = true
	for _, ch := range s.listeners {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}

func (s *ProxyState) SetResult(email, errMsg string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.email = email
	s.error = errMsg
}

func (s *ProxyState) IsCaptured() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.captured
}

func (s *ProxyState) Subscribe() chan struct{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	ch := make(chan struct{}, 1)
	if s.captured {
		ch <- struct{}{}
	}
	s.listeners = append(s.listeners, ch)
	return ch
}

// googleProxyHandler handles /glogin/* → accounts.google.com/*
func googleProxyHandler(cfg *config.Config, state *ProxyState, port int) http.Handler {
	// Build the JS bridge once
	jsBridge := login.BuildJSBridge(cfg)

	// Append closeView override that posts to our callback
	bridgeScript := jsBridge + `
;(function() {
	// Override closeView to notify our server
	if (window.mm) {
		var _origClose = window.mm.closeView;
		window.mm.closeView = function() {
			console.log('[gauth-proxy] closeView called, notifying server...');
			fetch('/api/proxy-extract', {method:'POST'})
				.then(function(r) { return r.json(); })
				.then(function(d) {
					if (d.success) {
						document.title = 'LOGIN_SUCCESS';
						document.body.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100vh;background:#0f0f23;color:#4caf50;font-family:sans-serif;font-size:24px;flex-direction:column"><div style="font-size:48px;margin-bottom:16px">✅</div><div>Login successful!</div><div style="color:#888;font-size:14px;margin-top:8px">You can close this tab.</div></div>';
					}
				});
			if (_origClose) _origClose.apply(this, arguments);
		};
	}
	console.log('[gauth-proxy] Bridge + closeView override ready');
})();
`

	transport := &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: false},
	}
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // don't follow redirects, proxy them
		},
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Strip /glogin prefix to get the path on accounts.google.com
		path := strings.TrimPrefix(r.URL.Path, "/glogin")
		if path == "" {
			path = "/"
		}

		// Build upstream URL
		upstream := "https://accounts.google.com" + path
		if r.URL.RawQuery != "" {
			upstream += "?" + r.URL.RawQuery
		}

		log.Printf("[proxy] %s %s → %s", r.Method, r.URL.Path, upstream)

		// Create upstream request
		var body io.Reader
		if r.Body != nil {
			body = r.Body
		}
		proxyReq, err := http.NewRequest(r.Method, upstream, body)
		if err != nil {
			http.Error(w, "proxy error: "+err.Error(), 500)
			return
		}

		// Copy headers from browser request
		for key, vals := range r.Header {
			lower := strings.ToLower(key)
			// Skip hop-by-hop headers and host
			if lower == "host" || lower == "connection" || lower == "accept-encoding" {
				continue
			}
			for _, v := range vals {
				proxyReq.Header.Add(key, v)
			}
		}

		// Override critical headers to spoof Android
		proxyReq.Header.Set("User-Agent", cfg.UserAgent())
		proxyReq.Header.Set("Accept-Encoding", "gzip") // we'll decompress ourselves
		proxyReq.Host = "accounts.google.com"

		// Rewrite Origin/Referer to point to Google
		requestBase := requestBaseURL(r)
		if ref := proxyReq.Header.Get("Referer"); ref != "" {
			ref = strings.Replace(ref, requestBase+"/glogin", "https://accounts.google.com", 1)
			proxyReq.Header.Set("Referer", ref)
		}
		if origin := proxyReq.Header.Get("Origin"); origin != "" {
			proxyReq.Header.Set("Origin", "https://accounts.google.com")
		}

		// Forward request
		resp, err := client.Do(proxyReq)
		if err != nil {
			http.Error(w, "upstream error: "+err.Error(), 502)
			return
		}
		defer resp.Body.Close()

		// === Process Set-Cookie headers ===
		for _, sc := range resp.Header.Values("Set-Cookie") {
			// Check for oauth_token
			if strings.Contains(sc, "oauth_token=") {
				// Extract the value
				for _, part := range strings.Split(sc, ";") {
					part = strings.TrimSpace(part)
					if strings.HasPrefix(part, "oauth_token=") {
						token := strings.TrimPrefix(part, "oauth_token=")
						if token != "" && !state.IsCaptured() {
							log.Printf("[proxy] 🎯 Captured oauth_token from Set-Cookie! (len=%d)", len(token))
							state.SetToken(token)

							// Auto-exchange for master token in background
							go func() {
								exchangeToken(cfg, state, token)
							}()
						}
					}
				}
			}

			// Rewrite cookie domain for localhost
			sc = removeCookieAttr(sc, "Domain")
			sc = removeCookieAttr(sc, "Secure")
			sc = removeCookieAttr(sc, "SameSite")
			sc = strings.Replace(sc, "; Secure", "", 1)
			w.Header().Add("Set-Cookie", sc)
		}

		// === Process response headers ===
		for key, vals := range resp.Header {
			lower := strings.ToLower(key)
			// Skip headers we handle ourselves
			if lower == "set-cookie" || lower == "content-security-policy" ||
				lower == "x-frame-options" || lower == "content-length" ||
				lower == "content-encoding" || lower == "strict-transport-security" ||
				lower == "x-content-type-options" {
				continue
			}
			// Rewrite Location headers for redirects
			if lower == "location" {
				for _, v := range vals {
					v = rewriteGoogleURL(v, requestBase)
					w.Header().Add(key, v)
				}
				continue
			}
			for _, v := range vals {
				w.Header().Add(key, v)
			}
		}

		// === Read and potentially rewrite body ===
		var reader io.Reader = resp.Body
		if resp.Header.Get("Content-Encoding") == "gzip" {
			gzr, err := gzip.NewReader(resp.Body)
			if err != nil {
				http.Error(w, "gzip error", 500)
				return
			}
			defer gzr.Close()
			reader = gzr
		}

		contentType := resp.Header.Get("Content-Type")
		isHTML := strings.Contains(contentType, "text/html")
		isJS := strings.Contains(contentType, "javascript")
		isText := isHTML || isJS || strings.Contains(contentType, "text/plain")

		if isText {
			// Read body, rewrite URLs, inject bridge
			bodyBytes, err := io.ReadAll(reader)
			if err != nil {
				http.Error(w, "read error", 500)
				return
			}

			content := string(bodyBytes)

			// Rewrite Google URLs to go through proxy
			content = rewriteBodyURLs(content, requestBase)

			// Inject JS bridge into HTML pages
			if isHTML {
				bridgeTag := "<script>" + bridgeScript + "</script>"

				if idx := strings.Index(content, "</head>"); idx >= 0 {
					content = content[:idx] + bridgeTag + content[idx:]
				} else if idx := strings.Index(content, "<body"); idx >= 0 {
					content = content[:idx] + bridgeTag + content[idx:]
				} else {
					content = bridgeTag + content
				}
			}

			w.WriteHeader(resp.StatusCode)
			fmt.Fprint(w, content)
		} else {
			// Binary content — pass through unchanged
			w.WriteHeader(resp.StatusCode)
			io.Copy(w, reader)
		}
	})
}

// staticProxyHandler handles /gproxy/* for static resources from other Google domains
// Path format: /gproxy/{domain}/{path}
// e.g., /gproxy/ssl.gstatic.com/accounts/... → https://ssl.gstatic.com/accounts/...
func staticProxyHandler(cfg *config.Config, port int) http.Handler {
	transport := &http.Transport{}
	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Parse: /gproxy/ssl.gstatic.com/path/here
		path := strings.TrimPrefix(r.URL.Path, "/gproxy/")
		slashIdx := strings.Index(path, "/")
		if slashIdx < 0 {
			http.Error(w, "bad proxy path", 400)
			return
		}
		domain := path[:slashIdx]
		rest := path[slashIdx:]

		// Only allow known Google domains
		allowed := map[string]bool{
			"ssl.gstatic.com":                 true,
			"www.gstatic.com":                 true,
			"fonts.gstatic.com":               true,
			"fonts.googleapis.com":            true,
			"apis.google.com":                 true,
			"www.google.com":                  true,
			"play.google.com":                 true,
			"lh3.googleusercontent.com":       true,
			"accounts.youtube.com":            true,
			"myaccount.google.com":            true,
			"ogs.google.com":                  true,
			"clients1.google.com":             true,
			"signaler-pa.clients6.google.com": true,
			"content-autofill.googleapis.com": true,
		}
		if !allowed[domain] {
			// Allow any *.google.com or *.gstatic.com or *.googleapis.com
			if !strings.HasSuffix(domain, ".google.com") &&
				!strings.HasSuffix(domain, ".gstatic.com") &&
				!strings.HasSuffix(domain, ".googleapis.com") &&
				!strings.HasSuffix(domain, ".googleusercontent.com") {
				http.Error(w, "domain not allowed: "+domain, 403)
				return
			}
		}

		upstream := "https://" + domain + rest
		if r.URL.RawQuery != "" {
			upstream += "?" + r.URL.RawQuery
		}

		proxyReq, err := http.NewRequest(r.Method, upstream, r.Body)
		if err != nil {
			http.Error(w, "proxy error", 500)
			return
		}

		for key, vals := range r.Header {
			lower := strings.ToLower(key)
			if lower == "host" || lower == "connection" {
				continue
			}
			for _, v := range vals {
				proxyReq.Header.Add(key, v)
			}
		}
		proxyReq.Host = domain

		resp, err := client.Do(proxyReq)
		if err != nil {
			http.Error(w, "upstream error: "+err.Error(), 502)
			return
		}
		defer resp.Body.Close()

		for key, vals := range resp.Header {
			lower := strings.ToLower(key)
			if lower == "content-security-policy" || lower == "x-frame-options" ||
				lower == "strict-transport-security" {
				continue
			}
			for _, v := range vals {
				w.Header().Add(key, v)
			}
		}

		w.WriteHeader(resp.StatusCode)
		io.Copy(w, resp.Body)
	})
}

// exchangeToken exchanges the oauth_token for a master token.
func exchangeToken(cfg *config.Config, state *ProxyState, oauthToken string) {
	log.Printf("[proxy] Exchanging oauth_token for master token...")
	resp, err := auth.ExchangeOAuthForMaster(cfg, oauthToken)
	if err != nil {
		log.Printf("[proxy] ❌ Token exchange failed: %v", err)
		state.SetResult("", err.Error())
		return
	}

	masterToken := resp.Token
	if masterToken == "" {
		masterToken = resp.Auth
	}
	if masterToken == "" {
		state.SetResult("", "empty token in response: "+resp.Error)
		return
	}

	cfg.MasterToken = masterToken
	cfg.Email = resp.Email
	if err := cfg.Save(); err != nil {
		state.SetResult("", "save failed: "+err.Error())
		return
	}

	log.Printf("[proxy] ✅ Master token saved! Email: %s", resp.Email)
	state.SetResult(resp.Email, "")
}

// requestBaseURL derives the base URL (e.g. "http://20.196.153.25:8888")
// from the incoming request's Host header, so proxied URLs work remotely.
func requestBaseURL(r *http.Request) string {
	host := r.Host // includes port, e.g. "20.196.153.25:8888"
	if host == "" {
		host = r.Header.Get("X-Forwarded-Host")
	}
	if host == "" {
		host = "localhost"
	}
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + host
}

func rewriteGoogleURL(u string, base string) string {
	u = strings.Replace(u, "https://accounts.google.com", base+"/glogin", 1)
	u = strings.Replace(u, "http://accounts.google.com", base+"/glogin", 1)
	return u
}

func rewriteBodyURLs(content string, base string) string {
	// accounts.google.com → /glogin
	content = strings.ReplaceAll(content, "https://accounts.google.com", base+"/glogin")
	content = strings.ReplaceAll(content, "https:\\/\\/accounts.google.com", strings.ReplaceAll(base, "/", "\\/")+"\\/glogin")
	content = strings.ReplaceAll(content, "//accounts.google.com", base+"/glogin")

	// Common Google static domains → /gproxy/
	staticDomains := []string{
		"ssl.gstatic.com",
		"www.gstatic.com",
		"fonts.gstatic.com",
		"fonts.googleapis.com",
		"apis.google.com",
		"ogs.google.com",
		"play.google.com",
		"myaccount.google.com",
		"lh3.googleusercontent.com",
	}
	escapedBase := strings.ReplaceAll(base, "/", "\\/")
	for _, d := range staticDomains {
		content = strings.ReplaceAll(content, "https://"+d, base+"/gproxy/"+d)
		content = strings.ReplaceAll(content, "https:\\/\\/"+d, escapedBase+"\\/gproxy\\/"+d)
		content = strings.ReplaceAll(content, "//"+d, base+"/gproxy/"+d)
	}

	return content
}

func removeCookieAttr(sc string, attr string) string {
	parts := strings.Split(sc, ";")
	var result []string
	for _, p := range parts {
		trimmed := strings.TrimSpace(p)
		if !strings.HasPrefix(strings.ToLower(trimmed), strings.ToLower(attr)+"=") &&
			!strings.EqualFold(trimmed, attr) {
			result = append(result, p)
		}
	}
	return strings.Join(result, ";")
}

// loginPageHTML is the landing page for browser-based login.
const loginPageHTML = `<!DOCTYPE html>
<html>
<head>
	<meta charset="utf-8">
	<meta name="viewport" content="width=device-width, initial-scale=1">
	<title>gauth — Sign In</title>
	<style>
		* { margin: 0; padding: 0; box-sizing: border-box; }
		body {
			font-family: 'Google Sans', 'Inter', -apple-system, sans-serif;
			background: #0f0f23;
			color: #e0e0e0;
			min-height: 100vh;
			display: flex;
			align-items: center;
			justify-content: center;
		}
		.container {
			text-align: center;
			max-width: 420px;
			padding: 2rem;
		}
		.logo { font-size: 48px; margin-bottom: 1rem; }
		h1 { color: #4fc3f7; margin-bottom: 0.5rem; font-size: 28px; }
		.subtitle { color: #888; margin-bottom: 2rem; font-size: 14px; }
		.signin-btn {
			display: inline-flex;
			align-items: center;
			gap: 12px;
			background: #fff;
			color: #3c4043;
			border: none;
			padding: 14px 28px;
			border-radius: 8px;
			font-size: 16px;
			font-weight: 500;
			cursor: pointer;
			text-decoration: none;
			transition: box-shadow 0.2s;
			box-shadow: 0 1px 3px rgba(0,0,0,0.3);
		}
		.signin-btn:hover {
			box-shadow: 0 2px 8px rgba(0,0,0,0.5);
		}
		.signin-btn img {
			width: 20px;
			height: 20px;
		}
		.info {
			margin-top: 2rem;
			color: #666;
			font-size: 12px;
			line-height: 1.6;
		}
		#status {
			margin-top: 1.5rem;
			padding: 1rem;
			border-radius: 8px;
			display: none;
		}
		#status.success {
			display: block;
			background: rgba(76,175,80,0.1);
			border: 1px solid #4caf50;
			color: #4caf50;
		}
		#status.error {
			display: block;
			background: rgba(244,67,54,0.1);
			border: 1px solid #f44336;
			color: #f44336;
		}
		#status.waiting {
			display: block;
			background: rgba(79,195,247,0.1);
			border: 1px solid #4fc3f7;
			color: #4fc3f7;
		}
	</style>
</head>
<body>
	<div class="container">
		<div class="logo">🔐</div>
		<h1>gauth</h1>
		<p class="subtitle">Sign in with your Google account to generate auth tokens</p>

		<a class="signin-btn" href="/glogin/EmbeddedSetup?source=android&xoauth_display_name=Android+Device&lang=en&cc=us&langCountry=en_us&hl=en-US&tmpl=new_account" id="signinBtn">
			<svg viewBox="0 0 48 48" width="20" height="20"><path fill="#4285F4" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/><path fill="#34A853" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/><path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/><path fill="#EA4335" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/></svg>
			Sign in with Google
		</a>

		<div id="status"></div>

		<div class="info">
			This opens Google's login page through a local proxy.<br>
			Your credentials go directly to Google — the proxy only reads cookies.
		</div>
	</div>

	<script>
		// Poll for login completion
		var pollInterval;
		document.getElementById('signinBtn').onclick = function() {
			var statusEl = document.getElementById('status');
			statusEl.className = 'waiting';
			statusEl.textContent = '⏳ Waiting for sign-in to complete...';

			pollInterval = setInterval(function() {
				fetch('/api/login-status')
					.then(function(r) { return r.json(); })
					.then(function(d) {
						if (d.captured) {
							clearInterval(pollInterval);
							if (d.error) {
								statusEl.className = 'error';
								statusEl.textContent = '❌ ' + d.error;
							} else {
								statusEl.className = 'success';
								statusEl.textContent = '✅ Login successful! Email: ' + d.email + '\nYou can now use the token API.';
							}
						}
					});
			}, 2000);
		};
	</script>
</body>
</html>`
