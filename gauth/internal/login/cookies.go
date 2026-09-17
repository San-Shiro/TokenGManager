//go:build windows

// Package login implements a cookie manager for WebView2 using the
// Chrome DevTools Protocol (CDP) via the ICoreWebView2 COM vtable.
// This reads HttpOnly cookies that JavaScript's document.cookie cannot access.
//
// Key design decisions:
// - Uses a SINGLETON cdpCompletedHandler kept alive via global reference (prevents GC)
// - Uses a mutex to prevent concurrent CDP calls from stacking
// - All COM calls must happen on the UI thread (via webview2.Dispatch)
package login

import (
	"encoding/json"
	"log"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// cdpCookieResponse is the response from Network.getAllCookies
type cdpCookieResponse struct {
	Cookies []cdpCookie `json:"cookies"`
}

type cdpCookie struct {
	Name     string  `json:"name"`
	Value    string  `json:"value"`
	Domain   string  `json:"domain"`
	Path     string  `json:"path"`
	HTTPOnly bool    `json:"httpOnly"`
	Secure   bool    `json:"secure"`
	Expires  float64 `json:"expires"`
}

// tokenCallback is called when the oauth_token is found
type tokenCallback func(token string)

// --- COM vtable mirrors ---

type iCoreWebView2Vtbl struct {
	QueryInterface                         uintptr
	AddRef                                 uintptr
	Release                                uintptr
	GetSettings                            uintptr
	GetSource                              uintptr
	Navigate                               uintptr
	NavigateToString                       uintptr
	AddNavigationStarting                  uintptr
	RemoveNavigationStarting               uintptr
	AddContentLoading                      uintptr
	RemoveContentLoading                   uintptr
	AddSourceChanged                       uintptr
	RemoveSourceChanged                    uintptr
	AddHistoryChanged                      uintptr
	RemoveHistoryChanged                   uintptr
	AddNavigationCompleted                 uintptr
	RemoveNavigationCompleted              uintptr
	AddFrameNavigationStarting             uintptr
	RemoveFrameNavigationStarting          uintptr
	AddFrameNavigationCompleted            uintptr
	RemoveFrameNavigationCompleted         uintptr
	AddScriptDialogOpening                 uintptr
	RemoveScriptDialogOpening              uintptr
	AddPermissionRequested                 uintptr
	RemovePermissionRequested              uintptr
	AddProcessFailed                       uintptr
	RemoveProcessFailed                    uintptr
	AddScriptToExecuteOnDocumentCreated    uintptr
	RemoveScriptToExecuteOnDocumentCreated uintptr
	ExecuteScript                          uintptr
	CapturePreview                         uintptr
	Reload                                 uintptr
	PostWebMessageAsJSON                   uintptr
	PostWebMessageAsString                 uintptr
	AddWebMessageReceived                  uintptr
	RemoveWebMessageReceived               uintptr
	CallDevToolsProtocolMethod             uintptr
}

type iCoreWebView2 struct {
	vtbl *iCoreWebView2Vtbl
}

// --- Struct layout mirrors for unsafe access ---

type chromiumLayout struct {
	hwnd        uintptr
	focusOnInit bool
	_pad        [7]byte
	controller  uintptr
	webview     uintptr // *ICoreWebView2
}

type webviewLayout struct {
	hwnd        uintptr
	mainthread  uintptr
	browserType uintptr
	browserData uintptr // → *Chromium
}

// --- CDP Completion Handler (COM interface implementation) ---

type cdpCompletedHandler struct {
	vtbl *cdpCompletedHandlerVtbl
}

type cdpCompletedHandlerVtbl struct {
	QueryInterface uintptr
	AddRef         uintptr
	Release        uintptr
	Invoke         uintptr
}

// Global state — all protected by mutex
var (
	globalMu       sync.Mutex
	globalOnToken  tokenCallback
	globalInFlight bool // prevents concurrent CDP calls

	// Singleton handler — created once, never GC'd
	globalHandler     *cdpCompletedHandler
	globalHandlerOnce sync.Once

	// The vtable — also singleton, never GC'd
	globalVtbl     *cdpCompletedHandlerVtbl
	globalVtblOnce sync.Once
)

func getGlobalVtbl() *cdpCompletedHandlerVtbl {
	globalVtblOnce.Do(func() {
		globalVtbl = &cdpCompletedHandlerVtbl{
			QueryInterface: windows.NewCallback(cdpQI),
			AddRef:         windows.NewCallback(cdpAddRef),
			Release:        windows.NewCallback(cdpRelease),
			Invoke:         windows.NewCallback(cdpInvoke),
		}
	})
	return globalVtbl
}

func getGlobalHandler() *cdpCompletedHandler {
	globalHandlerOnce.Do(func() {
		globalHandler = &cdpCompletedHandler{
			vtbl: getGlobalVtbl(),
		}
	})
	return globalHandler
}

func cdpQI(this, refiid, object uintptr) uintptr { return 0 }
func cdpAddRef(this uintptr) uintptr             { return 1 }
func cdpRelease(this uintptr) uintptr            { return 1 }

// cdpInvoke is called by WebView2 when the CDP method completes.
// Runs on the UI/message loop thread.
func cdpInvoke(this uintptr, errorCode uintptr, resultJSON uintptr) uintptr {
	globalMu.Lock()
	globalInFlight = false
	cb := globalOnToken
	globalMu.Unlock()

	if errorCode != 0 {
		log.Printf("[gauth] CDP error: 0x%x", errorCode)
		return 0
	}

	var jsonStr string
	if resultJSON != 0 {
		jsonStr = windows.UTF16PtrToString((*uint16)(unsafe.Pointer(resultJSON)))
	}
	if jsonStr == "" {
		log.Printf("[gauth] CDP: empty response")
		return 0
	}

	var resp cdpCookieResponse
	if err := json.Unmarshal([]byte(jsonStr), &resp); err != nil {
		log.Printf("[gauth] CDP parse error: %v", err)
		return 0
	}

	log.Printf("[gauth] CDP: got %d cookies", len(resp.Cookies))

	for _, c := range resp.Cookies {
		if c.Name == "oauth_token" {
			log.Printf("[gauth] 🎯 Found oauth_token! (httpOnly=%v, len=%d)", c.HTTPOnly, len(c.Value))
			if cb != nil {
				cb(c.Value)
			}
			return 0
		}
	}

	log.Printf("[gauth] oauth_token not found yet (%d cookies checked)", len(resp.Cookies))
	return 0
}

// RequestCookiesAsync fires CDP Network.getAllCookies.
// MUST be called from the UI thread (via webview2.Dispatch or Bind callback).
// Uses a singleton handler to prevent GC issues, and a mutex to prevent stacking.
func RequestCookiesAsync(w interface{}, onToken tokenCallback) {
	globalMu.Lock()
	if globalInFlight {
		globalMu.Unlock()
		log.Printf("[gauth] CDP call already in flight, skipping")
		return
	}
	globalInFlight = true
	globalOnToken = onToken
	globalMu.Unlock()

	wvPtr := extractWebView2Ptr(w)
	if wvPtr == 0 {
		log.Printf("[gauth] Failed to extract ICoreWebView2 pointer")
		globalMu.Lock()
		globalInFlight = false
		globalMu.Unlock()
		return
	}

	webview := (*iCoreWebView2)(unsafe.Pointer(wvPtr))
	handler := getGlobalHandler()

	methodName, _ := windows.UTF16PtrFromString("Network.getAllCookies")
	params, _ := windows.UTF16PtrFromString("{}")

	r, _, _ := syscall.SyscallN(
		webview.vtbl.CallDevToolsProtocolMethod,
		wvPtr,
		uintptr(unsafe.Pointer(methodName)),
		uintptr(unsafe.Pointer(params)),
		uintptr(unsafe.Pointer(handler)),
	)

	if r != 0 {
		log.Printf("[gauth] CDP call failed: HRESULT=0x%08x", r)
		globalMu.Lock()
		globalInFlight = false
		globalMu.Unlock()
	}
}

// extractWebView2Ptr uses unsafe pointer arithmetic to reach ICoreWebView2.
func extractWebView2Ptr(w interface{}) uintptr {
	type iface struct {
		typ  uintptr
		data uintptr
	}
	ifaceVal := (*iface)(unsafe.Pointer(&w))
	if ifaceVal.data == 0 {
		return 0
	}

	wv := (*webviewLayout)(unsafe.Pointer(ifaceVal.data))
	if wv.browserData == 0 {
		return 0
	}

	chromium := (*chromiumLayout)(unsafe.Pointer(wv.browserData))
	return chromium.webview
}
