package mobilecore

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func resetLogs() {
	logBuf.mu.Lock()
	logBuf.entries = logBuf.entries[:0]
	logBuf.nextID = 0
	logBuf.level = lvlInfo
	logBuf.notifier = nil
	logBuf.notifyPending = false
	logBuf.mu.Unlock()
}

func parseGetLogsData(t *testing.T, s string) (nextCursor string, logs []LogEntry) {
	t.Helper()
	e := parseEnvelope(t, s)
	if !e.OK {
		t.Fatalf("GetLogs failed: %s", e.Error)
	}
	raw, _ := json.Marshal(e.Data)
	var data struct {
		NextCursor string     `json:"nextCursor"`
		Logs       []LogEntry `json:"logs"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		t.Fatalf("parse data: %v", err)
	}
	return data.NextCursor, data.Logs
}

func TestGetLogsInitiallyEmpty(t *testing.T) {
	resetLogs()
	_, logs := parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 0 {
		t.Fatalf("expected empty logs, got %d", len(logs))
	}
}

func TestGetLogsNoInitRequired(t *testing.T) {
	// GetLogs must work before Init
	resetState()
	resetLogs()
	e := parseEnvelope(t, GetLogs(""))
	if !e.OK {
		t.Fatal("GetLogs should succeed without Init")
	}
}

func TestGetLogsAfterWrite(t *testing.T) {
	resetLogs()
	logInfo("testplatform", "hello world")
	_, logs := parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 1 {
		t.Fatalf("expected 1 log, got %d", len(logs))
	}
	if logs[0].Message != "hello world" {
		t.Fatalf("unexpected message: %q", logs[0].Message)
	}
	if logs[0].Level != "info" {
		t.Fatalf("unexpected level: %q", logs[0].Level)
	}
	if logs[0].Platform != "testplatform" {
		t.Fatalf("unexpected platform: %q", logs[0].Platform)
	}
}

func TestLogEntryHasHighPrecisionTimestamp(t *testing.T) {
	resetLogs()
	logInfo("testplatform", "high precision")
	_, logs := parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 1 {
		t.Fatalf("expected 1 log, got %d", len(logs))
	}
	if logs[0].TimestampMicros <= 0 {
		t.Fatalf("timestampMicros=%d, want positive", logs[0].TimestampMicros)
	}
	parsed, err := time.Parse(time.RFC3339Nano, logs[0].Time)
	if err != nil {
		t.Fatalf("time=%q is not RFC3339Nano: %v", logs[0].Time, err)
	}
	if parsed.UnixMicro() != logs[0].TimestampMicros {
		t.Fatalf("time micros=%d, timestampMicros=%d", parsed.UnixMicro(), logs[0].TimestampMicros)
	}
}

type testLogNotifier struct {
	count    int
	onNotify func()
}

func (n *testLogNotifier) OnLogsAvailable() {
	n.count++
	if n.onNotify != nil {
		n.onNotify()
	}
}

func TestLogNotifierCoalescesUntilDrain(t *testing.T) {
	resetLogs()
	notifier := &testLogNotifier{}
	SetLogNotifier(notifier)
	defer SetLogNotifier(nil)

	logInfo("", "one")
	logInfo("", "two")
	if notifier.count != 1 {
		t.Fatalf("notifications=%d, want 1 before drain", notifier.count)
	}
	GetLogs("0")
	logInfo("", "three")
	if notifier.count != 2 {
		t.Fatalf("notifications=%d, want 2 after drain", notifier.count)
	}
}

func TestLogNotifierRunsOutsideBufferLock(t *testing.T) {
	resetLogs()
	notifier := &testLogNotifier{onNotify: func() { GetLogs("0") }}
	SetLogNotifier(notifier)
	defer SetLogNotifier(nil)

	done := make(chan struct{})
	go func() {
		logInfo("", "no deadlock")
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("notifier callback deadlocked on log buffer")
	}
}

func TestGetLogsCursorIncremental(t *testing.T) {
	resetLogs()
	logInfo("", "msg1")
	logInfo("", "msg2")

	cursor, logs := parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 2 {
		t.Fatalf("expected 2 logs, got %d", len(logs))
	}

	// poll with cursor — should get nothing new
	_, newLogs := parseGetLogsData(t, GetLogs(cursor))
	if len(newLogs) != 0 {
		t.Fatalf("expected 0 new logs, got %d", len(newLogs))
	}

	// add one more
	logWarn("", "msg3")
	_, newLogs = parseGetLogsData(t, GetLogs(cursor))
	if len(newLogs) != 1 {
		t.Fatalf("expected 1 new log, got %d", len(newLogs))
	}
	if newLogs[0].Message != "msg3" {
		t.Fatalf("unexpected message: %q", newLogs[0].Message)
	}
}

func TestClearLogs(t *testing.T) {
	resetLogs()
	logInfo("", "to be cleared")
	e := parseEnvelope(t, ClearLogs())
	if !e.OK {
		t.Fatal("ClearLogs failed")
	}
	_, logs := parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 0 {
		t.Fatalf("expected empty after clear, got %d", len(logs))
	}
}

func TestClearLogsNoInitRequired(t *testing.T) {
	resetState()
	e := parseEnvelope(t, ClearLogs())
	if !e.OK {
		t.Fatal("ClearLogs should succeed without Init")
	}
}

func TestSetLogLevelFiltersLower(t *testing.T) {
	resetLogs()
	e := parseEnvelope(t, SetLogLevel("error"))
	if !e.OK {
		t.Fatal("SetLogLevel failed")
	}
	logInfo("", "should be filtered")
	logWarn("", "also filtered")
	logError("", "this passes")
	_, logs := parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 1 {
		t.Fatalf("expected 1 log (error only), got %d", len(logs))
	}
	// restore default
	SetLogLevel("info")
}

func TestSetLogLevelInvalid(t *testing.T) {
	e := parseEnvelope(t, SetLogLevel("verbose"))
	if e.OK {
		t.Fatal("invalid level should fail")
	}
}

func TestSetLogLevelNoInitRequired(t *testing.T) {
	resetState()
	e := parseEnvelope(t, SetLogLevel("warn"))
	if !e.OK {
		t.Fatal("SetLogLevel should work without Init")
	}
	SetLogLevel("info") // restore
}

func TestGetLogsInvalidCursor(t *testing.T) {
	e := parseEnvelope(t, GetLogs("notanumber"))
	if e.OK {
		t.Fatal("invalid cursor should return error")
	}
}

func TestLoginErrorPathLogsRecorded(t *testing.T) {
	resetState()
	resetLogs()
	Init("/tmp/test")
	// invalid accountJSON — Login should log a warn
	Login("haiqikeji", "not-json")
	_, logs := parseGetLogsData(t, GetLogs("0"))
	foundWarn := false
	for _, l := range logs {
		if l.Level == "warn" || l.Level == "error" {
			foundWarn = true
			break
		}
	}
	if !foundWarn {
		t.Fatal("expected a warn/error log after failed Login")
	}
}

func TestGetCoursesErrorPathLogsRecorded(t *testing.T) {
	resetState()
	resetLogs()
	Init("/tmp/test")
	GetCourses("not-json")
	_, logs := parseGetLogsData(t, GetLogs("0"))
	foundWarn := false
	for _, l := range logs {
		if l.Level == "warn" || l.Level == "error" {
			foundWarn = true
			break
		}
	}
	if !foundWarn {
		t.Fatal("expected a warn/error log after failed GetCourses")
	}
}

func TestCourseDiscoveryProgressLogsAreDebugOnly(t *testing.T) {
	resetState()
	resetLogs()
	Init("/tmp/test")
	resetLogs() // discard Init's user-facing info log

	defer fakeXxtCourseList(xxtFakeCourseList, nil)()
	defer fakeXxtChapter(xxtFakeChapter, nil)()
	defer fakeXxtKnowledgeCards(`{"data":[{"card":{"data":[]}}]}`, nil)()

	runDiscovery := func() {
		if e := parseEnvelope(t, GetCourses(xxtSessJSON)); !e.OK {
			t.Fatalf("GetCourses failed: %s", e.Error)
		}
		if e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, xxtCourseJSON)); !e.OK {
			t.Fatalf("GetCourseDetail failed: %s", e.Error)
		}
		courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111}}`
		if e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON)); !e.OK {
			t.Fatalf("GetTasks failed: %s", e.Error)
		}
	}

	// Default INFO should hide low-level discovery chatter.
	runDiscovery()
	_, logs := parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 0 {
		t.Fatalf("course discovery should be silent at info level, got %+v", logs)
	}

	// The same diagnostics remain available when DEBUG is explicitly enabled.
	if e := parseEnvelope(t, SetLogLevel("debug")); !e.OK {
		t.Fatalf("SetLogLevel(debug) failed: %s", e.Error)
	}
	defer SetLogLevel("info")
	ClearLogs()
	runDiscovery()
	_, logs = parseGetLogsData(t, GetLogs("0"))
	if len(logs) != 6 {
		t.Fatalf("expected 6 discovery debug logs, got %d: %+v", len(logs), logs)
	}
	for _, entry := range logs {
		if entry.Level != "debug" {
			t.Fatalf("discovery log level=%q, want debug: %+v", entry.Level, entry)
		}
	}
}

// --- Phase 2.3 additions ---

func parseGetLogsDataFull(t *testing.T, s string) (nextCursor string, logs []LogEntry, truncated bool, oldestCursor string) {
	t.Helper()
	e := parseEnvelope(t, s)
	if !e.OK {
		t.Fatalf("GetLogs failed: %s", e.Error)
	}
	raw, _ := json.Marshal(e.Data)
	var data struct {
		NextCursor   string     `json:"nextCursor"`
		OldestCursor string     `json:"oldestCursor"`
		Truncated    bool       `json:"truncated"`
		Logs         []LogEntry `json:"logs"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		t.Fatalf("parse data: %v", err)
	}
	return data.NextCursor, data.Logs, data.Truncated, data.OldestCursor
}

func doPanicFunc() (result string) {
	defer panicGuard(&result)
	panic("test panic message")
}

func TestPanicGuardLogsError(t *testing.T) {
	resetLogs()
	result := doPanicFunc()
	e := parseEnvelope(t, result)
	if e.OK {
		t.Fatal("panic should produce error response")
	}
	_, logs := parseGetLogsData(t, GetLogs("0"))
	found := false
	for _, l := range logs {
		if l.Level == "error" && strings.Contains(l.Message, "panic") {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("expected error log with 'panic' in message after panic")
	}
}

func TestSetLogLevelCaseInsensitive(t *testing.T) {
	e := parseEnvelope(t, SetLogLevel("INFO"))
	if !e.OK {
		t.Fatalf("SetLogLevel('INFO') should succeed: %s", e.Error)
	}
	e = parseEnvelope(t, SetLogLevel(" warn "))
	if !e.OK {
		t.Fatalf("SetLogLevel(' warn ') should succeed: %s", e.Error)
	}
	SetLogLevel("info") // restore
}

func TestGetLogsTruncatedFlag(t *testing.T) {
	resetLogs()
	// Simulate buffer that has been trimmed: entries start at ID 100
	logBuf.mu.Lock()
	logBuf.nextID = 102
	logBuf.entries = []LogEntry{
		{ID: 100, Level: "info", Source: "mobilecore", Message: "a"},
		{ID: 101, Level: "info", Source: "mobilecore", Message: "b"},
		{ID: 102, Level: "info", Source: "mobilecore", Message: "c"},
	}
	logBuf.mu.Unlock()

	_, logs, truncated, oldest := parseGetLogsDataFull(t, GetLogs("50"))
	if !truncated {
		t.Fatal("expected truncated=true: cursor 50 is below oldest available id 100")
	}
	if len(logs) != 3 {
		t.Fatalf("expected 3 logs, got %d", len(logs))
	}
	if oldest != "100" {
		t.Fatalf("expected oldestCursor 100, got %q", oldest)
	}
}

func TestGetLogsNotTruncatedForCursorZero(t *testing.T) {
	resetLogs()
	logInfo("", "msg")
	_, _, truncated, _ := parseGetLogsDataFull(t, GetLogs("0"))
	if truncated {
		t.Fatal("cursor=0 should never report truncated")
	}
}
