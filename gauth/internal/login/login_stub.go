//go:build !windows

// Package login — stub for non-Windows platforms.
// WebView login requires Windows (WebView2 + CDP). On Linux/macOS,
// use the browser login flow via /login on the HTTP server instead.
package login

import (
	"fmt"

	"github.com/nicksrandall/gauth/internal/config"
)

// LoginResult contains the login outcome.
type LoginResult struct {
	OAuthToken string
	Cancelled  bool
	Error      error
}

// RunWebViewLogin is not available on this platform.
// Use the browser login flow: start the server and visit /login.
func RunWebViewLogin(cfg *config.Config) (*LoginResult, error) {
	return nil, fmt.Errorf("WebView login is Windows-only. Use browser login: start 'gauth serve' and visit http://localhost:PORT/login")
}
