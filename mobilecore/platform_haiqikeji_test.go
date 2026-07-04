package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	haiqikejiApi "github.com/yatori-dev/yatori-go-mobile-core/api/haiqikeji"
)

func fakeHqkjNodeProvider(raw string) func() {
	orig := hqkjNodeProvider
	hqkjNodeProvider = func(_ *haiqikejiApi.HqkjUserCache, _ string) (string, error) {
		return raw, nil
	}
	return func() { hqkjNodeProvider = orig }
}

const hqkjFakeNodes = `{"code":200,"data":[{"id":1,"name":"第一章","children":[{"id":101,"name":"视频1","tabVideo":1,"tabFile":0,"tabExam":0,"tabWork":0},{"id":102,"name":"文档1","tabVideo":0,"tabFile":1,"tabExam":0,"tabWork":0}]},{"id":2,"name":"第二章","children":[{"id":201,"name":"考试1","tabVideo":0,"tabFile":0,"tabExam":1,"tabWork":0}]}]}`

func TestGetTasksHaiqikeji_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeHqkjNodeProvider(hqkjFakeNodes)
	defer restore()

	sessJSON := `{"platform":"haiqikeji","account":"u","token":"tok","extra":{"userId":"1","schoolId":"2","preUrl":"http://school.com"}}`
	courseJSON := `{"platform":"haiqikeji","id":"course-1"}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	raw, _ := json.Marshal(e.Data)
	var result TaskListResult
	json.Unmarshal(raw, &result)

	if result.Platform != "haiqikeji" {
		t.Fatalf("platform=%q", result.Platform)
	}
	if result.ParentID != "course-1" {
		t.Fatalf("parentId=%q", result.ParentID)
	}
	if len(result.Tasks) != 3 {
		t.Fatalf("expected 3 tasks, got %d", len(result.Tasks))
	}
	if result.Tasks[0].Type != "video" {
		t.Fatalf("task[0].type=%q, want video", result.Tasks[0].Type)
	}
	if result.Tasks[1].Type != "document" {
		t.Fatalf("task[1].type=%q, want document", result.Tasks[1].Type)
	}
	if result.Tasks[2].Type != "exam" {
		t.Fatalf("task[2].type=%q, want exam", result.Tasks[2].Type)
	}
}

func TestGetTasksHaiqikeji_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeHqkjNodeProvider(hqkjFakeNodes)
	defer restore()

	sessJSON := `{"platform":"haiqikeji","token":"tok","extra":{"userId":"1","schoolId":"2","preUrl":"http://x"}}`
	courseJSON := `{"id":"course-1"}` // platform omitted — falls back to session
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks should succeed: %s", e.Error)
	}
}

func TestGetTasksNotInitialized(t *testing.T) {
	resetState()
	e := parseEnvelope(t, GetTasks(`{"platform":"haiqikeji"}`, `{"id":"x"}`))
	if e.OK {
		t.Fatal("should fail before Init")
	}
}

func TestGetTasksInvalidJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	if e := parseEnvelope(t, GetTasks("not-json", ``)); e.OK {
		t.Fatal("invalid sessionJSON should fail")
	}
	if e := parseEnvelope(t, GetTasks(`{"platform":"haiqikeji"}`, "not-json")); e.OK {
		t.Fatal("invalid courseJSON should fail")
	}
}

func TestGetTasksUnsupportedPlatform(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetTasks(`{"platform":"mooc"}`, `{"platform":"mooc","id":"x"}`))
	if e.OK {
		t.Fatal("unsupported platform should fail")
	}
}

func TestTaskItemRoundtrip(t *testing.T) {
	ti := TaskItem{ID: "1", Name: "video node", Type: "video", Progress: 0.5}
	b, _ := json.Marshal(ti)
	var out TaskItem
	json.Unmarshal(b, &out)
	if out.Type != "video" || out.Progress != 0.5 {
		t.Fatalf("roundtrip mismatch: %+v", out)
	}
}

// --- haiqikeji GetCourseDetail tests ---

func TestGetCourseDetailHaiqikeji_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"haiqikeji","token":"tok","extra":{"schoolId":"s1","userId":"u1","preUrl":"http://school.haiqikeji.com"}}`
	courseJSON := `{"platform":"haiqikeji","id":"c1"}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail haiqikeji failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Platform != "haiqikeji" || detail.ParentID != "c1" || len(detail.Items) != 1 {
		t.Fatalf("unexpected detail: %+v", detail)
	}
}

func TestGetCourseDetailHaiqikeji_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"haiqikeji","token":"tok","extra":{"schoolId":"s1","userId":"u1","preUrl":"http://x"}}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, `{"id":"c2"}`))
	if !e.OK {
		t.Fatalf("platform from session failed: %s", e.Error)
	}
}

func TestGetCourseDetailHaiqikeji_RawCarriesTaskInputs(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"haiqikeji","token":"tok","extra":{"schoolId":"s1","userId":"u1","preUrl":"http://x"}}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, `{"platform":"haiqikeji","id":"c1"}`))
	if !e.OK {
		t.Fatalf("failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	raw := detail.Items[0].Raw
	for _, field := range []string{"courseId", "schoolId", "userId", "preUrl"} {
		if strOf(raw[field]) == "" {
			t.Errorf("item Raw missing %q", field)
		}
	}
}

func TestGetCourseDetailHaiqikeji_InvalidOrMissingCourseID(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"haiqikeji","token":"tok","extra":{}}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, `{"platform":"haiqikeji","id":"","raw":{}}`))
	if e.OK {
		t.Fatal("empty courseId should fail")
	}
}

func TestGetCourseDetailHaiqikeji_RawCourseIdFallback(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"haiqikeji","token":"tok","extra":{"schoolId":"s1","userId":"u1","preUrl":"http://x"}}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, `{"platform":"haiqikeji","id":"","raw":{"courseId":"c99"}}`))
	if !e.OK {
		t.Fatalf("raw.courseId fallback failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.ParentID != "c99" {
		t.Fatalf("parentId=%q", detail.ParentID)
	}
}

// --- haiqikeji RunTask tests ---

const hqkjRunSessJSON = `{"platform":"haiqikeji","token":"tok","extra":{"userId":"u1","schoolId":"s1","preUrl":"http://x"}}`
const hqkjRunTaskJSON = `{"platform":"haiqikeji","id":"node-1","raw":{"courseId":"c1"}}`

func fakeHqkjStart(raw string, err error) func() {
	orig := hqkjStartStudyProvider
	hqkjStartStudyProvider = func(_ *haiqikejiApi.HqkjUserCache, _, _ string) (string, error) { return raw, err }
	return func() { hqkjStartStudyProvider = orig }
}

func fakeHqkjSubmit(raw string, err error) func() {
	orig := hqkjSubmitStudyProvider
	hqkjSubmitStudyProvider = func(_ *haiqikejiApi.HqkjUserCache, _ string, _ int) (string, error) { return raw, err }
	return func() { hqkjSubmitStudyProvider = orig }
}

func TestRunTaskHaiqikeji_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"haiqikeji","id":"node-1","raw":{"courseId":"c1"},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("dry run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" {
		t.Fatalf("expected dry_run, got %q", res.Status)
	}
}

func TestRunTaskHaiqikeji_SubmitSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":200,"data":"session-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeHqkjSubmit(`{"code":200,"data":null}`, nil)
	defer restoreSubmit()

	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if !e.OK {
		t.Fatalf("submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Platform != "haiqikeji" {
		t.Fatalf("platform=%q", res.Platform)
	}
	if res.TaskID != "node-1" {
		t.Fatalf("taskId=%q", res.TaskID)
	}
}

func TestRunTaskHaiqikeji_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":200,"data":"session-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeHqkjSubmit(`{"code":200,"data":null}`, nil)
	defer restoreSubmit()

	taskJSON := `{"id":"node-1","raw":{"courseId":"c1"}}` // no platform
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestRunTaskHaiqikeji_MissingNodeId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, `{"platform":"haiqikeji","raw":{"courseId":"c1"}}`))
	if e.OK {
		t.Fatal("missing nodeId should fail")
	}
}

func TestRunTaskHaiqikeji_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, `{"platform":"haiqikeji","id":"node-1","raw":{}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestRunTaskHaiqikeji_StartProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart("", errors.New("network error"))
	defer restoreStart()
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if e.OK {
		t.Fatal("start provider error should fail")
	}
}

func TestRunTaskHaiqikeji_StartCodeNon200(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":401,"msg":"unauthorized"}`, nil)
	defer restoreStart()
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if e.OK {
		t.Fatal("start code!=200 should fail")
	}
}

func TestRunTaskHaiqikeji_StartMissingSessionId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":200,"data":""}`, nil)
	defer restoreStart()
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if e.OK {
		t.Fatal("start missing sessionId should fail")
	}
}

func TestRunTaskHaiqikeji_SubmitProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":200,"data":"session-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeHqkjSubmit("", errors.New("network error"))
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if e.OK {
		t.Fatal("submit provider error should fail")
	}
}

func TestRunTaskHaiqikeji_SubmitCodeNon200(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":200,"data":"session-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeHqkjSubmit(`{"code":500,"msg":"server error"}`, nil)
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if e.OK {
		t.Fatal("submit code!=200 should fail")
	}
}

func TestRunTaskHaiqikeji_AuthErrOnStart(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":401,"msg":"令牌不匹配"}`, nil)
	defer restoreStart()
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if e.OK {
		t.Fatal("auth error should fail")
	}
}

func TestRunTaskHaiqikeji_AuthErrOnSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":200,"data":"session-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeHqkjSubmit(`{"code":401,"msg":"认证失败"}`, nil)
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, hqkjRunTaskJSON))
	if e.OK {
		t.Fatal("auth error on submit should fail")
	}
}

func TestGetTasksHaiqikeji_DetailEchoWorks(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeHqkjNodeProvider(hqkjFakeNodes)
	defer restore()

	sessJSON := `{"platform":"haiqikeji","token":"tok","extra":{"schoolId":"s1","userId":"u1","preUrl":"http://x"}}`
	// Echo GetCourseDetail item (raw contains courseId, schoolId, userId, preUrl)
	courseJSON := `{"platform":"haiqikeji","id":"c1","raw":{"courseId":"c1","schoolId":"s1","userId":"u1","preUrl":"http://x"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks with echo detail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var result TaskListResult
	json.Unmarshal(b, &result)
	if len(result.Tasks) != 3 {
		t.Fatalf("expected 3 tasks, got %d", len(result.Tasks))
	}
}

// --- host-driven action primitives ---

func TestRunTaskHaiqikeji_ActionStart(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeHqkjStart(`{"code":200,"data":"session-77"}`, nil)
	defer restoreStart()
	// Submit must NOT be called on action=start; error provider proves it.
	restoreSubmit := fakeHqkjSubmit("", errors.New("submit must not be called"))
	defer restoreSubmit()

	taskJSON := `{"platform":"haiqikeji","id":"node-1","raw":{"courseId":"c1"},"options":{"action":"start"}}`
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=start should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "started" {
		t.Fatalf("expected started, got %q", res.Status)
	}
	if res.Raw["sessionId"] != "session-77" {
		t.Fatalf("raw.sessionId=%v, want session-77", res.Raw["sessionId"])
	}
}

func TestRunTaskHaiqikeji_ActionSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Start must NOT be called on action=submit; error provider proves it.
	restoreStart := fakeHqkjStart("", errors.New("start must not be called"))
	defer restoreStart()
	restoreSubmit := fakeHqkjSubmit(`{"code":200,"data":null}`, nil)
	defer restoreSubmit()

	taskJSON := `{"platform":"haiqikeji","id":"node-1","raw":{"sessionId":"session-9"},"options":{"action":"submit","progress":50}}`
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Raw["sessionId"] != "session-9" {
		t.Fatalf("raw.sessionId=%v, want session-9", res.Raw["sessionId"])
	}
	if res.Raw["progress"] != float64(50) {
		t.Fatalf("raw.progress=%v, want 50", res.Raw["progress"])
	}
}

func TestRunTaskHaiqikeji_ActionSubmitMissingSessionId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"haiqikeji","id":"node-1","raw":{"courseId":"c1"},"options":{"action":"submit"}}`
	e := parseEnvelope(t, RunTask(hqkjRunSessJSON, taskJSON))
	if e.OK {
		t.Fatal("action=submit without sessionId should fail")
	}
	if !strings.Contains(e.Error, "sessionId") {
		t.Errorf("expected 'sessionId' in error, got %q", e.Error)
	}
}
