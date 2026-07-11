package mobilecore

import (
	"encoding/json"
	"errors"
	"testing"

	welearnAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/welearn"
	welearnApi "github.com/yatori-dev/yatori-go-mobile-core/api/welearn"
)

// --- test seams ---

func fakeWelearnCourseList(courses []welearnAgg.WeLearnCourse, err error) func() {
	orig := welearnCourseListProvider
	welearnCourseListProvider = func(_ *welearnApi.WeLearnUserCache) ([]welearnAgg.WeLearnCourse, error) {
		return courses, err
	}
	return func() { welearnCourseListProvider = orig }
}

func fakeWelearnCourseInfo(uid, classId string, err error) func() {
	orig := welearnCourseInfoProvider
	welearnCourseInfoProvider = func(_ *welearnApi.WeLearnUserCache, _ string) (string, string, error) {
		return uid, classId, err
	}
	return func() { welearnCourseInfoProvider = orig }
}

func fakeWelearnChapter(chapters []welearnAgg.WeLearnChapter, err error) func() {
	orig := welearnChapterProvider
	welearnChapterProvider = func(_ *welearnApi.WeLearnUserCache, _, _, _ string) ([]welearnAgg.WeLearnChapter, error) {
		return chapters, err
	}
	return func() { welearnChapterProvider = orig }
}

func fakeWelearnPoint(points []welearnAgg.WeLearnPoint, err error) func() {
	orig := welearnPointProvider
	welearnPointProvider = func(_ *welearnApi.WeLearnUserCache, _, _, _, _ string) ([]welearnAgg.WeLearnPoint, error) {
		return points, err
	}
	return func() { welearnPointProvider = orig }
}

func fakeWelearnStart(raw string, err error) func() {
	orig := welearnStartStudyProvider
	welearnStartStudyProvider = func(_ *welearnApi.WeLearnUserCache, _, _, _, _, _ string, _ bool) (string, error) {
		return raw, err
	}
	return func() { welearnStartStudyProvider = orig }
}

func fakeWelearnSubmitTime(raw string, err error) func() {
	orig := welearnSubmitStudyTimeProvider
	welearnSubmitStudyTimeProvider = func(_ *welearnApi.WeLearnUserCache, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { welearnSubmitStudyTimeProvider = orig }
}

func fakeWelearnPlan1(raw string, err error) func() {
	orig := welearnSubmitPlan1Provider
	welearnSubmitPlan1Provider = func(_ *welearnApi.WeLearnUserCache, _, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { welearnSubmitPlan1Provider = orig }
}

func fakeWelearnPlan2(raw string, err error) func() {
	orig := welearnSubmitPlan2Provider
	welearnSubmitPlan2Provider = func(_ *welearnApi.WeLearnUserCache, _, _, _, _, _ string, _ int, _ string) (string, error) {
		return raw, err
	}
	return func() { welearnSubmitPlan2Provider = orig }
}

func fakeWelearnKeep(raw string, err error) func() {
	orig := welearnKeepProvider
	welearnKeepProvider = func(_ *welearnApi.WeLearnUserCache, _, _, _, _ string, _, _ int) (string, error) {
		return raw, err
	}
	return func() { welearnKeepProvider = orig }
}

const welearnSessJSON = `{"platform":"welearn","cookies":"SESS=x"}`

// --- GetCourses ---

func TestGetCoursesWelearn_RawFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeWelearnCourseList([]welearnAgg.WeLearnCourse{
		{Cid: "c1", Scid: "sc1", Name: "课程A", Type: "1", Uid: "u1", ClassId: "cl1", TaskCount: "10", Per: 50},
	}, nil)
	defer restore()

	e := parseEnvelope(t, GetCourses(welearnSessJSON))
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
	for _, f := range []string{"cid", "scid", "type", "uid", "classId", "taskCount"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("course Raw missing %q", f)
		}
	}
	if res.Courses[0].ID != "c1" {
		t.Fatalf("course ID=%q", res.Courses[0].ID)
	}
}

// --- GetCourseDetail ---

func TestGetCourseDetailWelearn_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeWelearnChapter([]welearnAgg.WeLearnChapter{
		{Id: "ch1", Name: "第一章", Unitname: "Unit 1", UnitIdx: 0, Visible: true},
		{Id: "ch2", Name: "第二章", Unitname: "Unit 2", UnitIdx: 1, Visible: true},
	}, nil)
	defer restore()

	courseJSON := `{"platform":"welearn","id":"c1","raw":{"cid":"c1","uid":"u1","classId":"cl1"}}`
	e := parseEnvelope(t, GetCourseDetail(welearnSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Platform != "welearn" || detail.ParentID != "c1" {
		t.Fatalf("unexpected: %+v", detail)
	}
	if len(detail.Items) != 2 {
		t.Fatalf("expected 2 chapters, got %d", len(detail.Items))
	}
	raw := detail.Items[0].Raw
	for _, f := range []string{"cid", "uid", "classId", "unitIdx"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("chapter Raw missing %q", f)
		}
	}
}

func TestGetCourseDetailWelearn_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeWelearnChapter([]welearnAgg.WeLearnChapter{{Id: "ch1", Name: "ch", UnitIdx: 0}}, nil)
	defer restore()

	courseJSON := `{"id":"c1","raw":{"cid":"c1","uid":"u1","classId":"cl1"}}`
	e := parseEnvelope(t, GetCourseDetail(welearnSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestGetCourseDetailWelearn_MissingCid(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(welearnSessJSON, `{"platform":"welearn","id":"","raw":{}}`))
	if e.OK {
		t.Fatal("missing cid should fail")
	}
}

func TestGetCourseDetailWelearn_CourseInfoFallback(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreInfo := fakeWelearnCourseInfo("u-fallback", "cl-fallback", nil)
	defer restoreInfo()
	restoreCh := fakeWelearnChapter([]welearnAgg.WeLearnChapter{{Id: "ch1", Name: "ch", UnitIdx: 0}}, nil)
	defer restoreCh()

	// uid/classId omitted — should be fetched via courseInfoProvider
	courseJSON := `{"platform":"welearn","id":"c1","raw":{"cid":"c1"}}`
	e := parseEnvelope(t, GetCourseDetail(welearnSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("courseInfo fallback failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Items[0].Raw["uid"] != "u-fallback" {
		t.Fatalf("uid fallback not applied: %v", detail.Items[0].Raw["uid"])
	}
}

func TestGetCourseDetailWelearn_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeWelearnChapter(nil, errors.New("server error"))
	defer restore()

	courseJSON := `{"platform":"welearn","id":"c1","raw":{"cid":"c1","uid":"u1","classId":"cl1"}}`
	e := parseEnvelope(t, GetCourseDetail(welearnSessJSON, courseJSON))
	if e.OK {
		t.Fatal("chapter provider error should fail")
	}
}

// --- GetTasks ---

const welearnTaskCourseJSON = `{"platform":"welearn","id":"ch1","raw":{"cid":"c1","uid":"u1","classId":"cl1","unitIdx":0}}`

func TestGetTasksWelearn_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeWelearnPoint([]welearnAgg.WeLearnPoint{
		{Id: "p1", Name: "视频1", Crate: "100", IsComplete: "已完成", LearnCount: 2, Location: "loc1"},
		{Id: "p2", Name: "视频2", Crate: "100", IsComplete: "未完成", LearnCount: 0, Location: "loc2"},
	}, nil)
	defer restore()

	e := parseEnvelope(t, GetTasks(welearnSessJSON, welearnTaskCourseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 2 {
		t.Fatalf("expected 2 tasks, got %d", len(res.Tasks))
	}
	if res.Tasks[0].Status != "done" {
		t.Fatalf("task[0].status=%q, want done", res.Tasks[0].Status)
	}
	raw := res.Tasks[0].Raw
	for _, f := range []string{"cid", "uid", "classId", "scoId", "crate", "isVisible"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("task Raw missing %q", f)
		}
	}
}

func TestGetTasksWelearn_MissingFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	cases := []string{
		`{"platform":"welearn","id":"ch1","raw":{"uid":"u1","classId":"cl1","unitIdx":0}}`, // no cid
		`{"platform":"welearn","id":"ch1","raw":{"cid":"c1","classId":"cl1","unitIdx":0}}`, // no uid
		`{"platform":"welearn","id":"ch1","raw":{"cid":"c1","uid":"u1","unitIdx":0}}`,      // no classId
		`{"platform":"welearn","id":"ch1","raw":{"cid":"c1","uid":"u1","classId":"cl1"}}`,  // no unitIdx
	}
	for i, cj := range cases {
		e := parseEnvelope(t, GetTasks(welearnSessJSON, cj))
		if e.OK {
			t.Fatalf("case %d: missing field should fail", i)
		}
	}
}

func TestGetTasksWelearn_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeWelearnPoint(nil, errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, GetTasks(welearnSessJSON, welearnTaskCourseJSON))
	if e.OK {
		t.Fatal("point provider error should fail")
	}
}

// --- RunTask ---

const welearnRunTaskJSON = `{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1","classId":"cl1","crate":"100"}}`

func TestRunTaskWelearn_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	called := false
	orig := welearnStartStudyProvider
	welearnStartStudyProvider = func(_ *welearnApi.WeLearnUserCache, _, _, _, _, _ string, _ bool) (string, error) {
		called = true
		return "", nil
	}
	defer func() { welearnStartStudyProvider = orig }()

	taskJSON := `{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1","classId":"cl1"},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(welearnSessJSON, taskJSON))
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
		t.Fatal("dryRun must not call provider")
	}
}

func TestRunTaskWelearn_TimeModeSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeWelearnStart(`{"ret":0}`, nil)
	defer restoreStart()
	restoreSubmit := fakeWelearnSubmitTime(`{"ret":0}`, nil)
	defer restoreSubmit()

	e := parseEnvelope(t, RunTask(welearnSessJSON, welearnRunTaskJSON))
	if !e.OK {
		t.Fatalf("time mode submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.TaskID != "p1" {
		t.Fatalf("taskId=%q", res.TaskID)
	}
}

func TestRunTaskWelearn_CompleteModePlan1Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeWelearnStart(`{"ret":0}`, nil)
	defer restoreStart()
	restorePlan1 := fakeWelearnPlan1(`{"ret":0}`, nil)
	defer restorePlan1()

	taskJSON := `{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1","classId":"cl1"},"options":{"complete":true}}`
	e := parseEnvelope(t, RunTask(welearnSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("complete mode plan1 should succeed: %s", e.Error)
	}
}

func TestRunTaskWelearn_CompleteModePlan2Fallback(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeWelearnStart(`{"ret":0}`, nil)
	defer restoreStart()
	restorePlan1 := fakeWelearnPlan1(`{"ret":-1}`, nil) // plan1 fails
	defer restorePlan1()
	restorePlan2 := fakeWelearnPlan2(`{"ret":0}`, nil) // plan2 succeeds
	defer restorePlan2()

	taskJSON := `{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1","classId":"cl1"},"options":{"complete":true}}`
	e := parseEnvelope(t, RunTask(welearnSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("complete mode plan2 fallback should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
}

func TestRunTaskWelearn_MissingFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	cases := []string{
		`{"platform":"welearn","raw":{"cid":"c1","uid":"u1","classId":"cl1"}}`, // no scoId
		`{"platform":"welearn","id":"p1","raw":{"uid":"u1","classId":"cl1"}}`,  // no cid
		`{"platform":"welearn","id":"p1","raw":{"cid":"c1","classId":"cl1"}}`,  // no uid
		`{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1"}}`,       // no classId
	}
	for i, tj := range cases {
		e := parseEnvelope(t, RunTask(welearnSessJSON, tj))
		if e.OK {
			t.Fatalf("case %d: missing field should fail", i)
		}
	}
}

func TestRunTaskWelearn_StartProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeWelearnStart("", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, RunTask(welearnSessJSON, welearnRunTaskJSON))
	if e.OK {
		t.Fatal("start provider error should fail")
	}
}

func TestRunTaskWelearn_SubmitProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeWelearnStart(`{"ret":0}`, nil)
	defer restoreStart()
	restoreSubmit := fakeWelearnSubmitTime("", errors.New("network error"))
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(welearnSessJSON, welearnRunTaskJSON))
	if e.OK {
		t.Fatal("submit provider error should fail")
	}
}

// --- host-driven action primitives ---

func TestRunTaskWelearn_ActionKeep(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Start/SubmitTime must NOT be called on action=keep; error providers prove it.
	restoreStart := fakeWelearnStart("", errors.New("start must not be called"))
	defer restoreStart()
	restoreSubmit := fakeWelearnSubmitTime("", errors.New("submitTime must not be called"))
	defer restoreSubmit()
	restoreKeep := fakeWelearnKeep(`{"ret":0}`, nil)
	defer restoreKeep()

	taskJSON := `{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1","classId":"cl1","crate":"100"},"options":{"action":"keep","sessionTime":120,"totalTime":120}}`
	e := parseEnvelope(t, RunTask(welearnSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=keep should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Raw["sessionTime"] != float64(120) {
		t.Fatalf("raw.sessionTime=%v, want 120", res.Raw["sessionTime"])
	}
}

func TestRunTaskWelearn_ActionFinalize(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Only Plan2 should be called on action=finalize.
	restoreStart := fakeWelearnStart("", errors.New("start must not be called"))
	defer restoreStart()
	restorePlan2 := fakeWelearnPlan2(`{"ret":0}`, nil)
	defer restorePlan2()

	taskJSON := `{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1","classId":"cl1","crate":"100"},"options":{"action":"finalize"}}`
	e := parseEnvelope(t, RunTask(welearnSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=finalize should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
}

func TestRunTaskWelearn_ActionStartReturnsServerScormBaseline(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreSubmit := fakeWelearnSubmitTime(`{"ret":0,"comment":"{\"cmi\":{\"session_time\":120,\"total_time\":300,\"progress_measure\":\"40\",\"score\":{\"scaled\":\"88\"}}}"}`, nil)
	defer restoreSubmit()
	restoreStart := fakeWelearnStart(`{"ret":0}`, nil)
	defer restoreStart()

	taskJSON := `{"platform":"welearn","id":"p1","raw":{"cid":"c1","uid":"u1","classId":"cl1","crate":"100"},"options":{"action":"start"}}`
	e := parseEnvelope(t, RunTask(welearnSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=start failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["sessionTime"] != float64(120) || res.Raw["totalTime"] != float64(300) || res.Raw["progressMeasure"] != float64(40) || res.Raw["scaled"] != "88" {
		t.Fatalf("raw=%v, want server SCORM baseline", res.Raw)
	}
}
