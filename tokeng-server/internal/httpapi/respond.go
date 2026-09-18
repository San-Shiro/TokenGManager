package httpapi

import (
	"encoding/json"
	"net/http"

	"github.com/San-Shiro/tokeng-server/internal/model"
)

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	resp := model.ErrorResponse{}
	resp.Error.Code = code
	resp.Error.Message = message
	writeJSON(w, status, resp)
}
