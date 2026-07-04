package ttcdw

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestEncDataMatchesPlayerSample(t *testing.T) {
	data := `{"companyCode":"D387ED042DF13283","userId":"527283745945702400:876955629390692352","resId":263696,"courseId":"3086","courseType":"share","tickerTime":1734944953704,"md5":"[\"mGKNmQbR9KynQGQV83UgaA==\"]"}`
	got, err := EncData(data)
	if err != nil {
		t.Fatalf("EncData returned error: %v", err)
	}
	want := `["3y41F5QeTMvbZt+njam/cne6F8LV3K6vKaU8D5hioQe8ZHprx+dPoRvaafaKs2tf+QJzdsWlQsRYdK0yChRi4aXAV0YEEq+FYxQJyw+CfrPtuvm6nDh+92pXbCeetY/MD/2f0zdbFh0=","/Xs6cIlSR7i43HDbNBjcgt6vC30boHdwQZqf8+bTkpPbyFxKe157zsGLv0TqFABTkL2uJINT0FWCk6q5XDo71PPCA4i+11L+mSrER1rhL/SrIwkH9o94hnZRoT0XZUNSMYE4HWQmwf4=","AUPB8KqydTA="]`
	if got != want {
		t.Fatalf("EncData mismatch\n got: %s\nwant: %s", got, want)
	}
	var chunks []string
	if err := json.Unmarshal([]byte(got), &chunks); err != nil {
		t.Fatalf("EncData must be a JSON string array: %v", err)
	}
	if len(chunks) != 3 {
		t.Fatalf("chunks=%d, want 3", len(chunks))
	}
}

func TestBuildTickerData(t *testing.T) {
	tickerData, payload, err := BuildTickerData("D387ED042DF13283", "u:class", int64(263918), "3086", "share", 1734963388575, "0-30")
	if err != nil {
		t.Fatalf("BuildTickerData returned error: %v", err)
	}
	if payload.CompanyCode != "D387ED042DF13283" || payload.UserID != "u:class" || payload.CourseID != "3086" {
		t.Fatalf("unexpected payload: %+v", payload)
	}
	if payload.MD5 == "" || !strings.HasPrefix(payload.MD5, "[\"") {
		t.Fatalf("payload.MD5 should be encrypted JSON array, got %q", payload.MD5)
	}
	var chunks []string
	if err := json.Unmarshal([]byte(tickerData), &chunks); err != nil {
		t.Fatalf("tickerData must be encrypted JSON array: %v", err)
	}
	if len(chunks) == 0 {
		t.Fatal("tickerData chunks should not be empty")
	}
}

func TestParseStudySubmitResult(t *testing.T) {
	ok, msg, err := ParseStudySubmitResult(`{"resultCode":0,"message":"ok"}`)
	if err != nil || !ok || msg != "ok" {
		t.Fatalf("resultCode success got ok=%v msg=%q err=%v", ok, msg, err)
	}
	ok, msg, err = ParseStudySubmitResult(`{"success":false,"msg":"expired"}`)
	if err != nil || ok || msg != "expired" {
		t.Fatalf("success false got ok=%v msg=%q err=%v", ok, msg, err)
	}
	if _, _, err = ParseStudySubmitResult(`not-json`); err == nil {
		t.Fatal("malformed JSON should fail")
	}
}

func TestParseCourseParam(t *testing.T) {
	raw := `{"success":true,"data":{"timeLimit":120,"clockInConfig":{"clockInRule":3}}}`
	param, err := ParseCourseParam(raw)
	if err != nil {
		t.Fatalf("ParseCourseParam returned error: %v", err)
	}
	if param.ClockInRule != "3" || param.TimeLimit != "120" {
		t.Fatalf("unexpected param: %+v", param)
	}
}

func TestStudyProgressSubmitApiPostsCurrentForm(t *testing.T) {
	var gotCookie string
	var gotForm url.Values
	var gotEncryptionValue string
	var gotPlatform string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotCookie = r.Header.Get("Cookie")
		gotEncryptionValue = r.Header.Get("EncryptionValue")
		gotPlatform = r.Header.Get("U-Platformid")
		if err := r.ParseForm(); err != nil {
			t.Fatalf("ParseForm: %v", err)
		}
		gotForm = r.PostForm
		w.Header().Set("Set-Cookie", "NEXT=1")
		_, _ = w.Write([]byte(`{"success":true,"message":"ok"}`))
	}))
	defer server.Close()

	cache := &TtcdwUserCache{Cookies: []*http.Cookie{{Name: "SESS", Value: "x"}}}
	raw, err := cache.StudyProgressSubmitApi(StudyProgressSubmitOptions{
		ProgressURL:  server.URL + "/progress?orgId=o1",
		OrgID:        "o1",
		CourseID:     "c1",
		ItemID:       "i1",
		VideoID:      "v1",
		PlayProgress: 60,
		SegID:        "s1",
		IsFinish:     false,
		SourceID:     "p1",
		ClockInRule:  "3",
		TimeLimit:    "120",
		EventType:    "study",
	}, 0, nil)
	if err != nil {
		t.Fatalf("StudyProgressSubmitApi returned error: %v", err)
	}
	if raw == "" || gotForm.Get("playProgress") != "60" || gotForm.Get("sourceId") != "p1" {
		t.Fatalf("form not posted correctly: raw=%q form=%v", raw, gotForm)
	}
	if gotForm.Get("clockInRule") != "3" || gotForm.Get("timeLimit") != "120" || gotForm.Get("eventType") != "study" {
		t.Fatalf("current progress fields missing: %v", gotForm)
	}
	if gotEncryptionValue != "c1" {
		t.Fatalf("EncryptionValue=%q, want courseId c1", gotEncryptionValue)
	}
	if gotPlatform == "" {
		t.Fatal("U-Platformid header missing")
	}
	if !strings.Contains(gotCookie, "SESS=x") {
		t.Fatalf("cookie not sent: %q", gotCookie)
	}
	if len(cache.Cookies) != 2 {
		t.Fatalf("Set-Cookie not merged, cookies=%v", cache.Cookies)
	}
}

func TestTickerSubmitApiPostsEncryptedForm(t *testing.T) {
	var gotCookie string
	var gotForm url.Values
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotCookie = r.Header.Get("Cookie")
		if err := r.ParseForm(); err != nil {
			t.Fatalf("ParseForm: %v", err)
		}
		gotForm = r.PostForm
		w.Header().Set("Set-Cookie", "NEXT=1")
		_, _ = w.Write([]byte(`{"resultCode":0,"message":"ok"}`))
	}))
	defer server.Close()

	cache := &TtcdwUserCache{Cookies: []*http.Cookie{{Name: "SESS", Value: "x"}}}
	raw, err := cache.TickerSubmitApi(server.URL, "tickerData", `["cipher"]`, 0, nil)
	if err != nil {
		t.Fatalf("TickerSubmitApi returned error: %v", err)
	}
	if raw == "" || gotForm.Get("tickerData") != `["cipher"]` {
		t.Fatalf("form not posted correctly: raw=%q form=%v", raw, gotForm)
	}
	if !strings.Contains(gotCookie, "SESS=x") {
		t.Fatalf("cookie not sent: %q", gotCookie)
	}
	if len(cache.Cookies) != 2 {
		t.Fatalf("Set-Cookie not merged, cookies=%v", cache.Cookies)
	}
}
