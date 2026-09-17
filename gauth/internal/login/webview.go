//go:build windows

// Package login provides WebView-based Google login.
// Uses jchv/go-webview2 (pure Go, Windows only) for WebView2 support.
package login

import (
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"github.com/jchv/go-webview2"
	"github.com/nicksrandall/gauth/internal/config"
)

// LoginResult contains the login outcome.
type LoginResult struct {
	OAuthToken string
	Cancelled  bool
	Error      error
}

// RunWebViewLogin opens a WebView window for Google sign-in.
// After the user signs in, they click the floating "Extract Token" button
// which triggers CDP Network.getAllCookies to read the HttpOnly oauth_token.
func RunWebViewLogin(cfg *config.Config) (*LoginResult, error) {
	result := &LoginResult{}
	var once sync.Once

	w := webview2.NewWithOptions(webview2.WebViewOptions{
		Debug:     true,
		AutoFocus: true,
		WindowOptions: webview2.WindowOptions{
			Title:  "Google Sign-In — gauth",
			Width:  420,
			Height: 720,
			Center: true,
		},
	})
	if w == nil {
		return nil, fmt.Errorf("failed to create webview (is WebView2 runtime installed?)")
	}
	defer w.Destroy()

	onTokenFound := func(token string) {
		once.Do(func() {
			result.OAuthToken = token
			log.Printf("[gauth] ✅ Token captured (length: %d)", len(token))
			w.Dispatch(func() { w.Terminate() })
		})
	}

	// Inject the Android JS bridge
	jsBridge := BuildJSBridge(cfg)
	w.Init(jsBridge)

	// Bind token callback
	w.Bind("__gauthCallback", func(action string, data string) {
		log.Printf("[gauth] JS callback: action=%s data_len=%d", action, len(data))
		switch action {
		case "token":
			onTokenFound(data)
		case "cancel":
			once.Do(func() {
				result.Cancelled = true
				w.Dispatch(func() { w.Terminate() })
			})
		}
	})

	// Bind CDP cookie extraction — called when user clicks the button
	w.Bind("__gauthExtractCookiesNow", func() {
		log.Printf("[gauth] 🔍 Extracting cookies via CDP...")
		RequestCookiesAsync(w, onTokenFound)
	})

	// Inject the floating "Extract Token" button + auto-detection as backup
	w.Init(`
		(function() {
			if (window.__gauthBtnInjected) return;
			window.__gauthBtnInjected = true;

			var _done = false;

			// --- FLOATING BUTTON ---
			function injectButton() {
				if (document.getElementById('gauth-extract-btn')) return;

				var btn = document.createElement('div');
				btn.id = 'gauth-extract-btn';
				btn.innerHTML = '🔑 Extract Token';
				btn.style.cssText = [
					'position: fixed',
					'bottom: 16px',
					'right: 16px',
					'z-index: 999999',
					'background: #1a73e8',
					'color: white',
					'padding: 12px 20px',
					'border-radius: 28px',
					'font-family: "Google Sans", Roboto, Arial, sans-serif',
					'font-size: 14px',
					'font-weight: 500',
					'cursor: pointer',
					'box-shadow: 0 2px 8px rgba(0,0,0,0.3)',
					'user-select: none',
					'transition: all 0.2s ease',
					'display: flex',
					'align-items: center',
					'gap: 8px'
				].join(';');

				btn.onmouseenter = function() {
					btn.style.background = '#1557b0';
					btn.style.boxShadow = '0 4px 12px rgba(0,0,0,0.4)';
					btn.style.transform = 'scale(1.05)';
				};
				btn.onmouseleave = function() {
					btn.style.background = '#1a73e8';
					btn.style.boxShadow = '0 2px 8px rgba(0,0,0,0.3)';
					btn.style.transform = 'scale(1)';
				};

				btn.onclick = function() {
					if (_done) return;
					btn.innerHTML = '⏳ Extracting...';
					btn.style.background = '#555';
					btn.style.cursor = 'wait';
					_done = true;
					window.__gauthExtractCookiesNow();
				};

				document.body.appendChild(btn);
			}

			// Re-inject button after navigations (SPA-style)
			var observer = new MutationObserver(function() {
				if (document.body && !document.getElementById('gauth-extract-btn')) {
					injectButton();
				}
			});

			if (document.body) {
				injectButton();
				observer.observe(document.body, { childList: true, subtree: true });
			} else {
				document.addEventListener('DOMContentLoaded', function() {
					injectButton();
					observer.observe(document.body, { childList: true, subtree: true });
				});
			}

			// --- AUTO-DETECTION (backup) ---
			function tryDocCookie() {
				var cookies = document.cookie;
				var parts = cookies.split(';');
				for (var i = 0; i < parts.length; i++) {
					var c = parts[i].trim();
					if (c.indexOf('oauth_token=') === 0) {
						return c.substring('oauth_token='.length);
					}
				}
				return '';
			}

			function tryExtract() {
				if (_done) return;
				var token = tryDocCookie();
				if (token) {
					_done = true;
					window.__gauthCallback('token', token);
					return;
				}
				_done = true;
				window.__gauthExtractCookiesNow();
			}

			// URL-based close signal detection
			setInterval(function() {
				if (_done) return;
				var url = window.location.href;
				if (url.indexOf('/signin/continue') >= 0
					|| url.indexOf('/o/oauth2/programmatic_auth') >= 0
					|| window.location.hash === '#close') {
					tryExtract();
				}
			}, 500);

			// Override closeView if mm bridge exists
			if (window.mm) {
				window.mm.closeView = function() {
					console.log('[gauth] closeView fired');
					tryExtract();
				};
			}

			console.log('[gauth] Button + monitors installed');
		})();
	`)

	loginURL := BuildLoginURL()
	log.Printf("[gauth] Opening login page: %s", loginURL)
	w.Navigate(loginURL)

	// Safety timeout
	go func() {
		time.Sleep(5 * time.Minute)
		once.Do(func() {
			result.Error = fmt.Errorf("login timeout (5 minutes)")
			w.Dispatch(func() { w.Terminate() })
		})
	}()

	w.Run()

	if result.Error != nil {
		return nil, result.Error
	}
	if result.Cancelled {
		return nil, fmt.Errorf("login cancelled by user")
	}
	if result.OAuthToken == "" {
		return nil, fmt.Errorf("no oauth_token received; login may have failed")
	}

	log.Printf("[gauth] ✅ OAuth token received (length: %d)", len(result.OAuthToken))
	return result, nil
}

// ParseOAuthFromCookies extracts oauth_token from a raw cookie string.
func ParseOAuthFromCookies(cookieStr string) string {
	for _, cookie := range strings.Split(cookieStr, ";") {
		cookie = strings.TrimSpace(cookie)
		if strings.HasPrefix(cookie, "oauth_token=") {
			return strings.TrimPrefix(cookie, "oauth_token=")
		}
	}
	return ""
}
