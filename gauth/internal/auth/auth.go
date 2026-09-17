// Package auth handles OAuth token exchange with Google's /auth endpoint.
package auth

import (
	"compress/gzip"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/nicksrandall/gauth/internal/config"
)

const authURL = "https://android.googleapis.com/auth"

// Response holds parsed key=value fields from the auth endpoint.
type Response struct {
	Auth          string
	Token         string
	Email         string
	Sid           string
	LSid          string
	Services      string
	FirstName     string
	LastName      string
	AccountID     string
	Expiry        int64
	IssueAdvice   string
	GrantedScopes string
	Error         string
	RawFields     map[string]string
}

// ExchangeOAuthForMaster exchanges a one-time OAuth token for a master token.
// Matches MicroG's LoginActivity.retrieveRtToken() exactly.
func ExchangeOAuthForMaster(cfg *config.Config, oauthToken string) (*Response, error) {
	// MicroG sends real androidId when available, "null" otherwise
	androidId := "null"
	if cfg.AndroidID != "" {
		androidId = cfg.AndroidID
	}

	form := url.Values{
		"androidId":                    {androidId},
		"sdk_version":                  {fmt.Sprintf("%d", cfg.Device.SDKVersion)},
		"device_country":               {"us"},
		"operatorCountry":              {"us"},
		"lang":                         {"en_US"},
		"google_play_services_version": {"255034000"},
		"accountType":                  {"HOSTED_OR_GOOGLE"},
		"service":                      {"ac2dm"},
		// MicroG does NOT send 'source' in retrieveRtToken
		"app":           {"com.google.android.gms"},
		"client_sig":    {"38918a453d07199354f8b19af05ec6562ced5788"},
		"callerPkg":     {"com.google.android.gms"},
		"callerSig":     {"38918a453d07199354f8b19af05ec6562ced5788"},
		"Token":         {oauthToken},
		"ACCESS_TOKEN":  {"1"},
		"add_account":   {"1"},
		"get_accountid": {"1"},
		// MicroG does NOT send 'is_called_from_account_manager' in retrieveRtToken
	}

	return doAuthRequest(cfg, form)
}

// FetchServiceToken gets a service-specific token using the master token.
// This is equivalent to MicroG's SpoofTokenFetcher.fetchCustomToken().
func FetchServiceToken(cfg *config.Config, scope, appPackage, appSig string) (*Response, error) {
	if !cfg.HasMasterToken() {
		return nil, fmt.Errorf("no master token; run 'gauth login' first")
	}

	form := url.Values{
		"androidId":                    {cfg.AndroidID},
		"sdk_version":                  {fmt.Sprintf("%d", cfg.Device.SDKVersion)},
		"device_country":               {"us"},
		"operatorCountry":              {"us"},
		"lang":                         {"en_US"},
		"google_play_services_version": {"255034000"},
		"accountType":                  {"HOSTED_OR_GOOGLE"},
		"Email":                        {cfg.Email},
		"service":                      {scope},
		"source":                       {"android"},
		"app":                          {appPackage},
		"client_sig":                   {appSig},
		"callerPkg":                    {appPackage},
		"callerSig":                    {appSig},
		"Token":                        {cfg.MasterToken},
		"system_partition":             {"1"},
		"has_permission":               {"1"},
	}

	return doAuthRequest(cfg, form)
}

// Common Google app signature.
const GoogleSig = "24bb24c05e47e0aefa68a58a766179d9b613a600"

// Known app definitions for convenience.
var KnownApps = map[string]struct{ Package, Scope string }{
	"photos":   {"com.google.android.apps.photos", "oauth2:openid https://www.googleapis.com/auth/mobileapps.native https://www.googleapis.com/auth/photos.native"},
	"youtube":  {"com.google.android.youtube", "oauth2:https://www.googleapis.com/auth/youtube"},
	"gmail":    {"com.google.android.gm", "oauth2:https://mail.google.com/"},
	"drive":    {"com.google.android.apps.docs", "oauth2:https://www.googleapis.com/auth/drive"},
	"calendar": {"com.google.android.calendar", "oauth2:https://www.googleapis.com/auth/calendar"},
	"gms":      {"com.google.android.gms", "ac2dm"},
}

func doAuthRequest(cfg *config.Config, form url.Values) (*Response, error) {
	log.Printf("[auth] POST %s (Token=%s...)", authURL, truncate(form.Get("Token"), 20))

	req, err := http.NewRequest("POST", authURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	// MicroG does NOT set Accept-Encoding or Connection headers
	req.Header.Set("User-Agent", cfg.AuthUserAgent())
	req.Header.Set("app", form.Get("app"))
	// device header matches the androidId in the form body
	deviceHeader := form.Get("androidId")
	req.Header.Set("device", deviceHeader)

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("auth request: %w", err)
	}
	defer resp.Body.Close()

	// Decompress
	var reader io.Reader = resp.Body
	if resp.Header.Get("Content-Encoding") == "gzip" {
		gzReader, err := gzip.NewReader(resp.Body)
		if err != nil {
			return nil, fmt.Errorf("gzip reader: %w", err)
		}
		defer gzReader.Close()
		reader = gzReader
	}

	body, err := io.ReadAll(reader)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	log.Printf("[auth] Response status=%d body_length=%d", resp.StatusCode, len(body))
	log.Printf("[auth] Response body:\n%s", string(body))

	result := parseAuthResponse(string(body))

	if resp.StatusCode != http.StatusOK {
		errMsg := result.Error
		if errMsg == "" {
			errMsg = string(body)
		}
		return result, fmt.Errorf("auth failed: status %d: %s", resp.StatusCode, errMsg)
	}

	return result, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

func parseAuthResponse(body string) *Response {
	r := &Response{RawFields: make(map[string]string)}
	body = strings.ReplaceAll(body, "\r\n", "\n")

	for _, line := range strings.Split(body, "\n") {
		line = strings.TrimSpace(line)
		idx := strings.Index(line, "=")
		if idx <= 0 {
			continue
		}
		key := line[:idx]
		value := line[idx+1:]
		r.RawFields[key] = value

		switch key {
		case "Auth":
			r.Auth = value
		case "Token":
			r.Token = value
		case "Email":
			r.Email = value
		case "SID":
			r.Sid = value
		case "LSID":
			r.LSid = value
		case "services":
			r.Services = value
		case "firstName":
			r.FirstName = value
		case "lastName":
			r.LastName = value
		case "accountId":
			r.AccountID = value
		case "issueAdvice":
			r.IssueAdvice = value
		case "grantedScopes":
			r.GrantedScopes = value
		case "Error":
			r.Error = value
		}
	}

	return r
}
