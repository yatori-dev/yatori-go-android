package mobilecore

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestStartLoginUnsupportedPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, StartLogin("notaplatform", `{"account":"a","password":"b"}`))
	if e.OK {
		t.Fatal("StartLogin unknown platform should fail")
	}
	if !strings.Contains(e.Error, "unsupported") {
		t.Errorf("expected 'unsupported' in error, got: %q", e.Error)
	}
}

func TestStartLoginBlockedOCRPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// OCR-blocked: expect "not implemented" in error
	for _, p := range []string{"weiban"} {
		e := parseEnvelope(t, StartLogin(p, `{"account":"a","password":"b"}`))
		if e.OK {
			t.Errorf("StartLogin(%q) blocked platform should fail", p)
		}
		if !strings.Contains(e.Error, "not implemented") {
			t.Errorf("StartLogin(%q) OCR blocked: expected 'not implemented', got: %q", p, e.Error)
		}
	}
}

func TestStartLoginBlockedNonOCRPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	cases := []struct {
		platform string
		wantIn   string // substring expected in error
	}{
		{"mooc", "VDF"},
		{"gongxue", "gorm"},
		{"cela", "captcha"},
	}
	for _, tc := range cases {
		e := parseEnvelope(t, StartLogin(tc.platform, `{"account":"a","password":"b"}`))
		if e.OK {
			t.Errorf("StartLogin(%q) should fail", tc.platform)
		}
		if !strings.Contains(e.Error, tc.wantIn) {
			t.Errorf("StartLogin(%q): expected %q in error, got: %q", tc.platform, tc.wantIn, e.Error)
		}
		// non-OCR blocked must NOT say "OCR state machine"
		if strings.Contains(e.Error, "OCR login state machine") {
			t.Errorf("StartLogin(%q): non-OCR platform should not mention OCR state machine, got: %q", tc.platform, e.Error)
		}
	}
}

func TestStartLoginNotInitialized(t *testing.T) {
	resetState()
	e := parseEnvelope(t, StartLogin("haiqikeji", `{"account":"a","password":"b","url":"http://x"}`))
	if e.OK {
		t.Fatal("StartLogin before Init should fail")
	}
}

func TestStartLoginInvalidJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, StartLogin("haiqikeji", `not-json`))
	if e.OK {
		t.Fatal("StartLogin with invalid JSON should fail")
	}
}

func TestCancelLoginUnknownTask(t *testing.T) {
	e := parseEnvelope(t, CancelLogin("login-nonexistent-999"))
	if e.OK {
		t.Fatal("CancelLogin unknown task should fail")
	}
	if !strings.Contains(e.Error, "not found") {
		t.Errorf("expected 'not found' in error, got: %q", e.Error)
	}
}

func TestCancelLoginEmptyTaskID(t *testing.T) {
	e := parseEnvelope(t, CancelLogin(""))
	if e.OK {
		t.Fatal("CancelLogin with empty taskId should fail")
	}
}

func TestCancelLoginExistingTask(t *testing.T) {
	task := pendingLogins.create("icve", "testuser")
	e := parseEnvelope(t, CancelLogin(task.TaskID))
	if !e.OK {
		t.Fatalf("CancelLogin existing task should succeed: %s", e.Error)
	}
	raw, _ := json.Marshal(e.Data)
	var res CancelLoginResult
	json.Unmarshal(raw, &res)
	if res.Status != LoginStatusCancelled {
		t.Fatalf("expected status=%q, got %q", LoginStatusCancelled, res.Status)
	}
	if res.TaskID != task.TaskID {
		t.Fatalf("expected taskId=%q, got %q", task.TaskID, res.TaskID)
	}
	// task should be gone
	if _, ok := pendingLogins.get(task.TaskID); ok {
		t.Fatal("task should be deleted after cancel")
	}
}

func TestContinueLoginUnknownTask(t *testing.T) {
	e := parseEnvelope(t, ContinueLogin("login-nonexistent-999", `{"taskId":"t","type":"image_ocr","text":"1234"}`))
	if e.OK {
		t.Fatal("ContinueLogin unknown task should fail")
	}
	if !strings.Contains(e.Error, "not found") {
		t.Errorf("expected 'not found' in error, got: %q", e.Error)
	}
}

func TestContinueLoginEmptyTaskID(t *testing.T) {
	e := parseEnvelope(t, ContinueLogin("", `{}`))
	if e.OK {
		t.Fatal("ContinueLogin with empty taskId should fail")
	}
}

func TestContinueLoginInvalidResultJSON(t *testing.T) {
	task := pendingLogins.create("icve", "testuser")
	defer pendingLogins.delete(task.TaskID)
	e := parseEnvelope(t, ContinueLogin(task.TaskID, `not-json`))
	if e.OK {
		t.Fatal("ContinueLogin with invalid JSON should fail")
	}
}

func TestContinueLoginExistingTask(t *testing.T) {
	task := pendingLogins.create("icve", "testuser")
	defer pendingLogins.delete(task.TaskID)
	e := parseEnvelope(t, ContinueLogin(task.TaskID, `{"type":"image_ocr","text":"abcd"}`))
	// currently returns "not implemented" error (state machine not done)
	if e.OK {
		t.Fatal("ContinueLogin state machine not implemented should fail")
	}
	if !strings.Contains(e.Error, "not implemented") {
		t.Errorf("expected 'not implemented' in error, got: %q", e.Error)
	}
}

func TestStartLoginResultDoneRoundtrip(t *testing.T) {
	sess := SessionData{Platform: "haiqikeji", Account: "u", Token: "tok", Extra: map[string]interface{}{}}
	r := StartLoginResult{Status: LoginStatusDone, Session: &sess}
	b, _ := json.Marshal(r)
	var out StartLoginResult
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	if out.Status != LoginStatusDone || out.Session == nil || out.Session.Platform != "haiqikeji" {
		t.Fatalf("roundtrip mismatch: %+v", out)
	}
}

func TestCancelLoginResultRoundtrip(t *testing.T) {
	r := CancelLoginResult{Status: LoginStatusCancelled, TaskID: "login-1"}
	b, _ := json.Marshal(r)
	var out CancelLoginResult
	json.Unmarshal(b, &out)
	if out.Status != LoginStatusCancelled || out.TaskID != "login-1" {
		t.Fatalf("roundtrip mismatch: %+v", out)
	}
}
