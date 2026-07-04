package mobilecore

import (
	"encoding/json"
	"strings"
	"testing"
)

func resetState() { state = runtimeState{} }

func parseEnvelope(t *testing.T, s string) envelope {
	t.Helper()
	var e envelope
	if err := json.Unmarshal([]byte(s), &e); err != nil {
		t.Fatalf("unmarshal envelope: %v\nraw: %s", err, s)
	}
	return e
}

func TestHealthCheckAlwaysOK(t *testing.T) {
	resetState()
	e := parseEnvelope(t, HealthCheck())
	if !e.OK {
		t.Fatalf("HealthCheck should always return ok=true, got: %s", e.Error)
	}
}

func TestInitRequiresBaseDir(t *testing.T) {
	resetState()
	if e := parseEnvelope(t, Init("")); e.OK {
		t.Fatal("Init with empty baseDir should fail")
	}
}

func TestInitSuccess(t *testing.T) {
	resetState()
	e := parseEnvelope(t, Init("/data/data/com.example/files"))
	if !e.OK {
		t.Fatalf("Init failed: %s", e.Error)
	}
}

func TestSetConfigBeforeInit(t *testing.T) {
	resetState()
	e := parseEnvelope(t, SetConfig(`{"setting":{},"users":[]}`))
	if e.OK {
		t.Fatal("SetConfig before Init should fail")
	}
}

func TestSetConfigInvalidJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, SetConfig(`not-json`))
	if e.OK {
		t.Fatal("SetConfig with invalid JSON should fail")
	}
}

func TestSetAndGetConfig(t *testing.T) {
	resetState()
	Init("/tmp/test")

	cfg := `{
		"setting": {
			"basicSetting": {"logLevel": "info", "logModel": 1},
			"aiSetting": {"aiType": "DEEPSEEK", "model": "deepseek-chat", "API_KEY": "sk-test"}
		},
		"users": [
			{"accountType": "xuexitong", "account": "testuser", "password": "testpass"}
		]
	}`
	if e := parseEnvelope(t, SetConfig(cfg)); !e.OK {
		t.Fatalf("SetConfig failed: %s", e.Error)
	}

	e := parseEnvelope(t, GetConfig())
	if !e.OK {
		t.Fatalf("GetConfig failed: %s", e.Error)
	}
}

func TestSetXuexitongFontTables(t *testing.T) {
	resetState()
	if e := parseEnvelope(t, SetXuexitongFontTables(`{}`, `{}`)); e.OK {
		t.Fatal("SetXuexitongFontTables before Init should fail")
	}
	Init("/tmp/test")
	e := parseEnvelope(t, SetXuexitongFontTables(`{"hash":"uni4E00"}`, `{"uni4E00":19968}`))
	if !e.OK {
		t.Fatalf("SetXuexitongFontTables failed: %s", e.Error)
	}
}

func TestGetConfigBeforeSet(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetConfig())
	if e.OK {
		t.Fatal("GetConfig before SetConfig should fail")
	}
}

func TestGetConfigSchema(t *testing.T) {
	resetState()
	e := parseEnvelope(t, GetConfigSchema())
	if !e.OK {
		t.Fatalf("GetConfigSchema failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var data struct {
		Fields []struct {
			Path string `json:"path"`
		} `json:"fields"`
	}
	if err := json.Unmarshal(b, &data); err != nil {
		t.Fatalf("schema parse: %v", err)
	}
	paths := map[string]bool{}
	for _, f := range data.Fields {
		paths[f.Path] = true
	}
	for _, path := range []string{
		"users[].remarkName",
		"users[].informEmails",
		"users[].coursesCustom.studyTime",
		"users[].coursesCustom.cxNode",
		"users[].coursesCustom.cxChapterTestSw",
		"users[].coursesCustom.cxWorkSw",
		"users[].coursesCustom.cxExamSw",
		"users[].coursesCustom.shuffleSw",
		"users[].coursesCustom.examAutoSubmit",
		"users[].coursesCustom.coursesSettings[].includeExams",
		"users[].coursesCustom.coursesSettings[].excludeExams",
	} {
		if !paths[path] {
			t.Fatalf("schema missing %s", path)
		}
	}
}

func TestHealthCheckAfterInit(t *testing.T) {
	resetState()
	Init("/tmp/base")
	e := parseEnvelope(t, HealthCheck())
	if !e.OK {
		t.Fatal("HealthCheck should succeed after Init")
	}
}

func TestStubsReturnNotImplemented(t *testing.T) {
	resetState()
	for name, fn := range map[string]func() string{
		"RunTask": func() string { return RunTask("{}", "{}") },
	} {
		e := parseEnvelope(t, fn())
		if e.OK {
			t.Errorf("%s stub should return ok=false", name)
		}
	}
}

func TestLoginNotInitialized(t *testing.T) {
	resetState()
	e := parseEnvelope(t, Login("haiqikeji", `{"account":"a","password":"b","url":"http://x"}`))
	if e.OK {
		t.Fatal("Login before Init should fail")
	}
}

func TestLoginInvalidJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, Login("haiqikeji", `not-json`))
	if e.OK {
		t.Fatal("Login with invalid accountJSON should fail")
	}
}

func TestLoginUnknownPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, Login("unknownplatform", `{"account":"a","password":"b"}`))
	if e.OK {
		t.Fatal("Login with unknown platform should fail")
	}
}

func TestLoginBlockedPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, Login("weiban", `{"account":"a","password":"b"}`))
	if e.OK {
		t.Fatal("weiban login should return not-implemented (OCR blocked)")
	}
}

func TestGetCoursesNotInitialized(t *testing.T) {
	resetState()
	e := parseEnvelope(t, GetCourses(`{"platform":"haiqikeji","account":"a","token":""}`))
	if e.OK {
		t.Fatal("GetCourses before Init should fail")
	}
}

func TestGetCoursesInvalidJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourses(`not-json`))
	if e.OK {
		t.Fatal("GetCourses with invalid JSON should fail")
	}
}

func TestGetCoursesUnknownPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourses(`{"platform":"unknownplatform","account":"a","token":""}`))
	if e.OK {
		t.Fatal("GetCourses for unknown platform should fail")
	}
}

func TestSessionDataCookiesField(t *testing.T) {
	// Cookies field serialises and deserialises correctly
	sess := SessionData{
		Platform: "ketangx",
		Account:  "user1",
		Cookies:  "JSESSIONID=abc; user=x",
		Extra:    map[string]interface{}{"id": "42"},
	}
	b, err := json.Marshal(sess)
	if err != nil {
		t.Fatal(err)
	}
	var out SessionData
	if err := json.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	if out.Cookies != sess.Cookies {
		t.Fatalf("cookies roundtrip: got %q, want %q", out.Cookies, sess.Cookies)
	}
	if out.Token != "" {
		t.Fatal("Token should be empty when only Cookies set")
	}
}

func TestSessionDataCookiesFallbackToToken(t *testing.T) {
	// Old session without Cookies field — Token used as fallback in adapters
	old := `{"platform":"ttcdw","account":"u","token":"SESS=old","extra":{}}`
	var sess SessionData
	if err := json.Unmarshal([]byte(old), &sess); err != nil {
		t.Fatal(err)
	}
	if sess.Token != "SESS=old" {
		t.Fatalf("expected token SESS=old, got %q", sess.Token)
	}
	if sess.Cookies != "" {
		t.Fatalf("expected empty cookies, got %q", sess.Cookies)
	}
}

// Phase 2.4: blocked platform tests (extended in 2.5.1; qingshuxuetang moved to done in 3.1)
func TestLoginBlockedPlatforms(t *testing.T) {
	resetState()
	Init("/tmp/test")
	blocked := []string{"weiban", "mooc", "cela", "gongxue"}
	for _, platform := range blocked {
		e := parseEnvelope(t, Login(platform, `{"account":"a","password":"b"}`))
		if e.OK {
			t.Errorf("Login(%q) should fail (blocked)", platform)
		}
		if !strings.Contains(e.Error, "blocked") {
			t.Errorf("Login(%q) error should mention 'blocked', got: %q", platform, e.Error)
		}
	}
}

func TestGetCoursesBlockedPlatforms(t *testing.T) {
	resetState()
	Init("/tmp/test")
	blocked := []string{"weiban", "mooc", "cela", "gongxue"}
	for _, platform := range blocked {
		e := parseEnvelope(t, GetCourses(`{"platform":"`+platform+`","account":"a","token":""}`))
		if e.OK {
			t.Errorf("GetCourses(%q) should fail (blocked)", platform)
		}
		if !strings.Contains(e.Error, "blocked") {
			t.Errorf("GetCourses(%q) error should mention 'blocked', got: %q", platform, e.Error)
		}
	}
}

func TestWelearnDispatchRecognised(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// welearn is dispatched — expect a network error (not "unknown platform" or "unsupported")
	e := parseEnvelope(t, Login("welearn", `{"account":"","password":""}`))
	if e.OK {
		t.Fatal("Login with empty credentials should fail")
	}
	if e.Error == `platform "welearn": unsupported platform` {
		t.Fatal("welearn should be recognised in dispatch, not unsupported")
	}
}

func TestLoginUnsupportedPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, Login("notaplatform", `{"account":"a","password":"b"}`))
	if e.OK {
		t.Fatal("Login with unsupported platform should fail")
	}
	if !strings.Contains(e.Error, "unsupported") {
		t.Errorf("unknown platform error should mention 'unsupported', got: %q", e.Error)
	}
}
