package mobilecore

import (
	"encoding/json"
	"errors"
	"testing"

	ttcdwApi "github.com/yatori-dev/yatori-go-mobile-core/api/ttcdw"
)

func fakeSubmitProvider(wantErr error) func() {
	orig := ttcdwStudySubmitProvider
	ttcdwStudySubmitProvider = func(_ *ttcdwApi.TtcdwUserCache, _ ttcdwApi.StudyProgressSubmitOptions) (string, error) {
		if wantErr != nil {
			return "", wantErr
		}
		return `{"resultCode":0,"message":"ok"}`, nil
	}
	return func() { ttcdwStudySubmitProvider = orig }
}

func fakeTtcdwTickerSubmit(raw string, wantErr error) func() {
	orig := ttcdwTickerSubmitProvider
	ttcdwTickerSubmitProvider = func(_ *ttcdwApi.TtcdwUserCache, _, _, _ string) (string, error) {
		if wantErr != nil {
			return "", wantErr
		}
		return raw, nil
	}
	return func() { ttcdwTickerSubmitProvider = orig }
}

func fakeTtcdwCourseParam(param ttcdwApi.CourseParam, wantErr error) func() {
	orig := ttcdwCourseParamProvider
	ttcdwCourseParamProvider = func(_ *ttcdwApi.TtcdwUserCache, _, _ string) (ttcdwApi.CourseParam, error) {
		if wantErr != nil {
			return ttcdwApi.CourseParam{}, wantErr
		}
		return param, nil
	}
	return func() { ttcdwCourseParamProvider = orig }
}

const ttcdwVideoTaskJSON = `{"platform":"ttcdw","id":"v1","type":"video","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"}}`

func TestRunTaskNotInitialized(t *testing.T) {
	resetState()
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw"}`, ttcdwVideoTaskJSON))
	if e.OK {
		t.Fatal("should fail before Init")
	}
}

func TestRunTaskInvalidJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(`not-json`, ttcdwVideoTaskJSON))
	if e.OK {
		t.Fatal("invalid sessionJSON should fail")
	}
	e = parseEnvelope(t, RunTask(`{"platform":"ttcdw"}`, `not-json`))
	if e.OK {
		t.Fatal("invalid taskJSON should fail")
	}
}

func TestRunTaskUnsupportedPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(`{"platform":"ketangx","cookies":"x"}`, `{"platform":"ketangx","id":"v1","raw":{}}`))
	if e.OK {
		t.Fatal("unsupported RunTask platform should fail")
	}
}

func TestRunTaskTtcdwDryRunSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"ttcdw","id":"v1","type":"video","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("dry run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" {
		t.Fatalf("expected status=dry_run, got %q", res.Status)
	}
}

func TestRunTaskTtcdwSubmitSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeSubmitProvider(nil)
	defer restore()

	taskJSON := `{"platform":"ttcdw","id":"v1","type":"video","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"},"options":{"realSubmit":true,"progress":45}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("RunTask should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected status=submitted, got %q", res.Status)
	}
	if res.Platform != "ttcdw" {
		t.Fatalf("platform=%q", res.Platform)
	}
}

func TestRunTaskTtcdwMissingRawField(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Missing orgId
	taskJSON := `{"platform":"ttcdw","id":"v1","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","projectId":"p1","clockInRule":"0","timeLimit":"-1"}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if e.OK {
		t.Fatal("missing orgId should fail")
	}
}

func TestRunTaskTtcdwProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeSubmitProvider(errors.New("server error"))
	defer restore()

	taskJSON := `{"platform":"ttcdw","id":"v1","type":"video","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"},"options":{"realSubmit":true,"progress":45}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if e.OK {
		t.Fatal("provider error should fail")
	}
}

func TestRunTaskTtcdwPlatformFromSession(t *testing.T) {
	// platform omitted from task — falls back to session.platform
	resetState()
	Init("/tmp/test")
	restore := fakeSubmitProvider(nil)
	defer restore()

	taskJSON := `{"id":"v1","type":"video","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"},"options":{"realSubmit":true,"progress":45}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestRunTaskTtcdwTickerDryRunDefault(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"ttcdw","id":"v1","type":"video","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"3","timeLimit":"99"},"options":{"action":"tick","progress":30}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("progress ticker dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" {
		t.Fatalf("status=%q, want dry_run", res.Status)
	}
	if res.Raw["tickerUrl"] == "" || res.Raw["submitMode"] != "progress" {
		t.Fatalf("progress raw missing: %+v", res.Raw)
	}
	if res.Raw["playProgress"] != float64(30) {
		t.Fatalf("playProgress=%v, want 30", res.Raw["playProgress"])
	}
}

func TestRunTaskTtcdwTickerRealSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var captured ttcdwApi.StudyProgressSubmitOptions
	orig := ttcdwStudySubmitProvider
	ttcdwStudySubmitProvider = func(_ *ttcdwApi.TtcdwUserCache, opts ttcdwApi.StudyProgressSubmitOptions) (string, error) {
		captured = opts
		return `{"resultCode":0,"message":"ok"}`, nil
	}
	defer func() { ttcdwStudySubmitProvider = orig }()
	taskJSON := `{"platform":"ttcdw","id":"v1","type":"video","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"},"options":{"action":"tick","progress":60,"realSubmit":true}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("progress ticker submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("status=%q, want submitted", res.Status)
	}
	if res.Raw["realSubmit"] != true {
		t.Fatalf("realSubmit=%v, want true", res.Raw["realSubmit"])
	}
	if captured.CourseID != "c1" || captured.SourceID != "p1" || captured.PlayProgress != 60 || captured.EventType != "study" {
		t.Fatalf("unexpected submitted options: %+v", captured)
	}
}

func TestRunTaskTtcdwTickerRealSubmitRequiresProgress(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"ttcdw","id":"v1","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"},"options":{"action":"tick","realSubmit":true}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if e.OK {
		t.Fatal("real submit without progress should fail")
	}
}

func TestRunTaskTtcdwPrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"ttcdw","id":"v1","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1","clockInRule":"0","timeLimit":"-1"},"options":{"action":"prepare"}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("prepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.Raw["intervalSeconds"] != float64(30) || res.Raw["submitMode"] != "progress" {
		t.Fatalf("unexpected prepare result: %+v", res)
	}
}

func TestRunTaskTtcdwPrepareFetchesCourseParam(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeTtcdwCourseParam(ttcdwApi.CourseParam{ClockInRule: "3", TimeLimit: "120"}, nil)
	defer restore()
	taskJSON := `{"platform":"ttcdw","id":"v1","raw":{"videoId":"v1","courseId":"c1","itemId":"i1","segmentId":"s1","orgId":"o1","projectId":"p1"},"options":{"action":"prepare"}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("prepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["clockInRule"] != "3" || res.Raw["timeLimit"] != "120" {
		t.Fatalf("course param not propagated: %+v", res.Raw)
	}
}

func TestRunTaskTtcdwDESLegacyMode(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"ttcdw","id":"263918","type":"video","raw":{"videoId":"263918","companyCode":"D387ED042DF13283","userId":"u:class","tickerCourseId":"3086","courseType":"share"},"options":{"action":"tick","submitMode":"des","playedEnd":30,"tickerTime":1734963388575}}`
	e := parseEnvelope(t, RunTask(`{"platform":"ttcdw","cookies":"SESS=x"}`, taskJSON))
	if !e.OK {
		t.Fatalf("DES ticker dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["tickerData"] == "" || res.Raw["playedRanges"] != "0-30" {
		t.Fatalf("unexpected DES raw: %+v", res.Raw)
	}
}
