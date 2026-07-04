package mobilecore

import (
	"encoding/json"
	"fmt"
)

type envelope struct {
	OK    bool        `json:"ok"`
	Data  interface{} `json:"data,omitempty"`
	Error string      `json:"error"`
}

func okResp(data interface{}) string {
	b, _ := json.Marshal(envelope{OK: true, Data: data})
	return string(b)
}

func errResp(msg string) string {
	b, _ := json.Marshal(envelope{OK: false, Error: msg})
	return string(b)
}

// panicGuard is used with defer to catch panics, log them, and write into result.
func panicGuard(result *string) {
	if r := recover(); r != nil {
		msg := fmt.Sprintf("panic: %v", r)
		logError("", msg)
		*result = errResp(msg)
	}
}
