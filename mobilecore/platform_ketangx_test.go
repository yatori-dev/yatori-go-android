package mobilecore

import (
	"encoding/json"
	"errors"
	"testing"

	ketangxAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/ketangx"
	ketangxApi "github.com/yatori-dev/yatori-go-mobile-core/api/ketangx"
)

// --- test seams ---

func fakeKetangxCourseList(courses []ketangxAgg.KetangxCourse) func() {
	orig := ketangxCourseListProvider
	ketangxCourseListProvider = func(_ *ketangxApi.KetangxUserCache) []ketangxAgg.KetangxCourse {
		return courses
	}
	return func() { ketangxCourseListProvider = orig }
}

func fakeKetangxNodes(nodes []ketangxAgg.KetangxNode, err error) func() {
	orig := ketangxNodeProvider
	ketangxNodeProvider = func(_ *ketangxApi.KetangxUserCache, _ string) ([]ketangxAgg.KetangxNode, error) {
		return nodes, err
	}
	return func() { ketangxNodeProvider = orig }
}

func fakeKetangxSign(raw string, err error) func() {
	orig := ketangxSignProvider
	ketangxSignProvider = func(_ *ketangxApi.KetangxUserCache, _ string) (string, error) {
		return raw, err
	}
	return func() { ketangxSignProvider = orig }
}

func fakeKetangxComplete(raw string, err error) func() {
	orig := ketangxCompleteProvider
	ketangxCompleteProvider = func(_ *ketangxApi.KetangxUserCache, _, _ string, _, _ int) (string, error) {
		return raw, err
	}
	return func() { ketangxCompleteProvider = orig }
}

const ketangxSessJSON = `{"platform":"ketangx","cookies":"SESS=x","extra":{"userId":"u1","id":"i1","userName":"un1"}}`
const ketangxCourseJSON = `{"platform":"ketangx","id":"a1","raw":{"courseId":"a1","activityId":"a1","title":"课程A","userId":"u1","id":"i1","userName":"un1"}}`
const ketangxRunTaskJSON = `{"platform":"ketangx","id":"s1","raw":{"sectId":"s1","courseId":"a1","activityId":"a1","userId":"u1"}}`

// --- GetCourses ---

func TestGetCoursesKetangx_RawFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeKetangxCourseList([]ketangxAgg.KetangxCourse{
		{Title: "课程A", ActivityId: "a1", Progress: 50},
	})
	defer restore()

	e := parseEnvelope(t, GetCourses(ketangxSessJSON))
	if !e.OK {
		t.Fatalf("GetCourses failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res CourseListResult
	json.Unmarshal(b, &res)
	if len(res.Courses) != 1 {
		t.Fatalf("expected 1 course, got %d", len(res.Courses))
	}
	raw := res.Courses[0].Raw
	for _, f := range []string{"activityId", "courseId", "title", "progress", "userId", "id", "userName"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("course Raw missing %q", f)
		}
	}
	if res.Courses[0].ID != "a1" {
		t.Fatalf("course ID=%q", res.Courses[0].ID)
	}
}

// --- GetCourseDetail ---

func TestGetCourseDetailKetangx_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(ketangxSessJSON, ketangxCourseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Platform != "ketangx" || detail.ParentID != "a1" {
		t.Fatalf("unexpected: %+v", detail)
	}
	if len(detail.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(detail.Items))
	}
	raw := detail.Items[0].Raw
	for _, f := range []string{"courseId", "activityId", "title", "userId", "id", "userName"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("detail Raw missing %q", f)
		}
	}
}

func TestGetCourseDetailKetangx_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	courseJSON := `{"id":"a1","raw":{"courseId":"a1","activityId":"a1"}}`
	e := parseEnvelope(t, GetCourseDetail(ketangxSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestGetCourseDetailKetangx_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(ketangxSessJSON, `{"platform":"ketangx","id":"","raw":{}}`))
	if e.OK {
		t.Fatal("missing courseId/activityId should fail")
	}
}

// --- GetTasks ---

func TestGetTasksKetangx_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeKetangxNodes([]ketangxAgg.KetangxNode{
		{SectId: "s1", Title: "视频1", Type: "视频", IsComplete: true, EnterNum: "10"},
		{SectId: "s2", Title: "文档1", Type: "文档", IsComplete: false, EnterNum: "5"},
	}, nil)
	defer restore()

	e := parseEnvelope(t, GetTasks(ketangxSessJSON, ketangxCourseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 2 {
		t.Fatalf("expected 2 tasks, got %d", len(res.Tasks))
	}
	if res.Tasks[0].Type != "video" {
		t.Fatalf("task[0].type=%q, want video", res.Tasks[0].Type)
	}
	if res.Tasks[0].Status != "done" {
		t.Fatalf("task[0].status=%q, want done", res.Tasks[0].Status)
	}
	if res.Tasks[1].Type != "document" {
		t.Fatalf("task[1].type=%q, want document", res.Tasks[1].Type)
	}
	raw := res.Tasks[0].Raw
	for _, f := range []string{"sectId", "courseId", "activityId", "userId"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("task Raw missing %q", f)
		}
	}
}

func TestGetTasksKetangx_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetTasks(ketangxSessJSON, `{"platform":"ketangx","raw":{}}`))
	if e.OK {
		t.Fatal("missing courseId/activityId should fail")
	}
}

func TestGetTasksKetangx_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeKetangxNodes(nil, errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, GetTasks(ketangxSessJSON, ketangxCourseJSON))
	if e.OK {
		t.Fatal("node provider error should fail")
	}
}

// --- RunTask ---

func TestRunTaskKetangx_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	called := false
	orig := ketangxSignProvider
	ketangxSignProvider = func(_ *ketangxApi.KetangxUserCache, _ string) (string, error) {
		called = true
		return "", nil
	}
	defer func() { ketangxSignProvider = orig }()

	taskJSON := `{"platform":"ketangx","id":"s1","raw":{"userId":"u1"},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(ketangxSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("dry run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" {
		t.Fatalf("expected dry_run, got %q", res.Status)
	}
	if called {
		t.Fatal("dryRun must not call sign provider")
	}
}

func TestRunTaskKetangx_SubmitSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreSign := fakeKetangxSign(`ok`, nil)
	defer restoreSign()
	restoreComplete := fakeKetangxComplete(`ok`, nil)
	defer restoreComplete()

	e := parseEnvelope(t, RunTask(ketangxSessJSON, ketangxRunTaskJSON))
	if !e.OK {
		t.Fatalf("submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Platform != "ketangx" || res.TaskID != "s1" {
		t.Fatalf("unexpected: %+v", res)
	}
}

func TestRunTaskKetangx_MissingSectId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(ketangxSessJSON, `{"platform":"ketangx","raw":{"userId":"u1"}}`))
	if e.OK {
		t.Fatal("missing sectId should fail")
	}
}

func TestRunTaskKetangx_MissingUserId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// no userId in raw, session extra also cleared
	sessJSON := `{"platform":"ketangx","cookies":"SESS=x","extra":{}}`
	e := parseEnvelope(t, RunTask(sessJSON, `{"platform":"ketangx","id":"s1","raw":{}}`))
	if e.OK {
		t.Fatal("missing userId should fail")
	}
}

func TestRunTaskKetangx_SignProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeKetangxSign("", errors.New("sign error"))
	defer restore()
	e := parseEnvelope(t, RunTask(ketangxSessJSON, ketangxRunTaskJSON))
	if e.OK {
		t.Fatal("sign error should fail")
	}
}

func TestRunTaskKetangx_CompleteProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreSign := fakeKetangxSign(`ok`, nil)
	defer restoreSign()
	restoreComplete := fakeKetangxComplete("", errors.New("complete error"))
	defer restoreComplete()
	e := parseEnvelope(t, RunTask(ketangxSessJSON, ketangxRunTaskJSON))
	if e.OK {
		t.Fatal("complete error should fail")
	}
}

func TestRunTaskKetangx_StudyTimeOptions(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var capturedStudyTime, capturedDuration int
	orig := ketangxCompleteProvider
	ketangxCompleteProvider = func(_ *ketangxApi.KetangxUserCache, _ string, _ string, st, dur int) (string, error) {
		capturedStudyTime = st
		capturedDuration = dur
		return "ok", nil
	}
	defer func() { ketangxCompleteProvider = orig }()
	restoreSign := fakeKetangxSign(`ok`, nil)
	defer restoreSign()

	taskJSON := `{"platform":"ketangx","id":"s1","raw":{"userId":"u1"},"options":{"studyTime":200,"duration":300}}`
	e := parseEnvelope(t, RunTask(ketangxSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("should succeed: %s", e.Error)
	}
	if capturedStudyTime != 200 || capturedDuration != 300 {
		t.Fatalf("studyTime=%d duration=%d, expected 200/300", capturedStudyTime, capturedDuration)
	}
}
