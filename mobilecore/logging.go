package mobilecore

import (
	"strconv"
	"strings"
	"sync"
	"time"
)

const maxLogEntries = 1000

// LogEntry is one log record returned to Android.
type LogEntry struct {
	ID       uint64 `json:"id"`
	Time     string `json:"time"`
	Level    string `json:"level"`
	Source   string `json:"source"`
	Platform string `json:"platform,omitempty"`
	Message  string `json:"message"`
}

const (
	lvlDebug = 0
	lvlInfo  = 1
	lvlWarn  = 2
	lvlError = 3
)

type logBuffer struct {
	mu      sync.Mutex
	entries []LogEntry
	nextID  uint64
	level   int
}

var logBuf = logBuffer{level: lvlInfo}

func appendLog(levelInt int, levelStr, platform, msg string) {
	logBuf.mu.Lock()
	defer logBuf.mu.Unlock()
	if levelInt < logBuf.level {
		return
	}
	logBuf.nextID++
	e := LogEntry{
		ID:       logBuf.nextID,
		Time:     time.Now().Format(time.RFC3339),
		Level:    levelStr,
		Source:   "mobilecore",
		Platform: platform,
		Message:  msg,
	}
	logBuf.entries = append(logBuf.entries, e)
	if len(logBuf.entries) > maxLogEntries {
		logBuf.entries = logBuf.entries[len(logBuf.entries)-maxLogEntries:]
	}
}

func logDebug(platform, msg string) { appendLog(lvlDebug, "debug", platform, msg) }
func logInfo(platform, msg string)  { appendLog(lvlInfo, "info", platform, msg) }
func logWarn(platform, msg string)  { appendLog(lvlWarn, "warn", platform, msg) }
func logError(platform, msg string) { appendLog(lvlError, "error", platform, msg) }

// GetLogs returns log entries after the given cursor.
// cursor="" or "0" returns all available entries.
// cursor="N" returns entries with id > N (incremental poll).
// Response also includes oldestCursor and truncated.
// GetLogs is safe to call before Init.
func GetLogs(cursor string) (result string) {
	defer panicGuard(&result)
	var fromID uint64
	if cursor != "" && cursor != "0" {
		v, err := strconv.ParseUint(cursor, 10, 64)
		if err != nil {
			return errResp("invalid cursor: must be a numeric string")
		}
		fromID = v
	}
	logBuf.mu.Lock()
	filtered := make([]LogEntry, 0)
	for _, e := range logBuf.entries {
		if e.ID > fromID {
			filtered = append(filtered, e)
		}
	}
	nextCursor := logBuf.nextID
	var oldestID uint64
	truncated := false
	if len(logBuf.entries) > 0 {
		oldestID = logBuf.entries[0].ID
		// truncated when caller asked for entries after fromID but some were already evicted
		truncated = fromID > 0 && fromID < oldestID-1
	}
	logBuf.mu.Unlock()
	if len(filtered) > 0 {
		nextCursor = filtered[len(filtered)-1].ID
	}
	return okResp(map[string]interface{}{
		"nextCursor":   strconv.FormatUint(nextCursor, 10),
		"oldestCursor": strconv.FormatUint(oldestID, 10),
		"truncated":    truncated,
		"logs":         filtered,
	})
}

// ClearLogs empties the log buffer. The id counter continues from where it left off.
// ClearLogs is safe to call before Init.
func ClearLogs() (result string) {
	defer panicGuard(&result)
	logBuf.mu.Lock()
	logBuf.entries = logBuf.entries[:0]
	logBuf.mu.Unlock()
	return okResp(nil)
}

// SetLogLevel sets the minimum log level: debug | info | warn | error.
// Input is trimmed and lowercased, so "INFO" and " info " are accepted.
// SetLogLevel is safe to call before Init.
func SetLogLevel(level string) (result string) {
	defer panicGuard(&result)
	level = strings.TrimSpace(strings.ToLower(level))
	var l int
	switch level {
	case "debug":
		l = lvlDebug
	case "info":
		l = lvlInfo
	case "warn":
		l = lvlWarn
	case "error":
		l = lvlError
	default:
		return errResp("invalid level: must be debug|info|warn|error")
	}
	logBuf.mu.Lock()
	logBuf.level = l
	logBuf.mu.Unlock()
	return okResp(map[string]string{"level": level})
}
