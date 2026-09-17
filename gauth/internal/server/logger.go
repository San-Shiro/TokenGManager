// Package server — ring buffer logger for API request monitoring.
package server

import (
	"fmt"
	"sync"
	"time"
)

// LogEntry represents a single API request/event log.
type LogEntry struct {
	Timestamp string `json:"timestamp"`
	Level     string `json:"level"`    // "info", "warn", "error"
	Endpoint  string `json:"endpoint"` // e.g. "/api/token"
	Method    string `json:"method"`   // HTTP method
	Status    int    `json:"status"`   // HTTP status code
	Message   string `json:"message"`
	Duration  string `json:"duration,omitempty"` // request duration
	ClientIP  string `json:"client_ip,omitempty"`
}

// RingLogger is a fixed-size circular buffer for log entries.
type RingLogger struct {
	mu      sync.Mutex
	entries []LogEntry
	maxSize int
	pos     int
	total   int
}

// NewRingLogger creates a logger with the given capacity.
func NewRingLogger(maxSize int) *RingLogger {
	return &RingLogger{
		entries: make([]LogEntry, maxSize),
		maxSize: maxSize,
	}
}

// Log adds a new entry to the ring buffer.
func (rl *RingLogger) Log(level, endpoint, method string, status int, message string, duration time.Duration, clientIP string) {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	entry := LogEntry{
		Timestamp: time.Now().Format("2006-01-02 15:04:05"),
		Level:     level,
		Endpoint:  endpoint,
		Method:    method,
		Status:    status,
		Message:   message,
		ClientIP:  clientIP,
	}
	if duration > 0 {
		entry.Duration = fmt.Sprintf("%.0fms", float64(duration.Microseconds())/1000)
	}

	rl.entries[rl.pos] = entry
	rl.pos = (rl.pos + 1) % rl.maxSize
	rl.total++
}

// Info logs at info level.
func (rl *RingLogger) Info(endpoint, method string, status int, msg string, dur time.Duration, ip string) {
	rl.Log("info", endpoint, method, status, msg, dur, ip)
}

// Error logs at error level.
func (rl *RingLogger) Error(endpoint, method string, status int, msg string, dur time.Duration, ip string) {
	rl.Log("error", endpoint, method, status, msg, dur, ip)
}

// Entries returns all log entries in chronological order.
func (rl *RingLogger) Entries(limit int) []LogEntry {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	count := rl.total
	if count > rl.maxSize {
		count = rl.maxSize
	}
	if limit > 0 && limit < count {
		count = limit
	}

	result := make([]LogEntry, 0, count)

	// Read in chronological order
	start := rl.pos - count
	if start < 0 {
		start = 0
		if rl.total > rl.maxSize {
			start = rl.pos
		}
	}

	for i := 0; i < count; i++ {
		idx := (start + i) % rl.maxSize
		if rl.entries[idx].Timestamp != "" {
			result = append(result, rl.entries[idx])
		}
	}

	return result
}

// Stats returns summary statistics.
func (rl *RingLogger) Stats() map[string]interface{} {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	errors := 0
	endpoints := make(map[string]int)
	for _, e := range rl.entries {
		if e.Timestamp == "" {
			continue
		}
		if e.Level == "error" {
			errors++
		}
		endpoints[e.Endpoint]++
	}

	return map[string]interface{}{
		"total_requests": rl.total,
		"errors":         errors,
		"buffer_size":    rl.maxSize,
		"endpoints":      endpoints,
	}
}
