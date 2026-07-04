package mobilecore

import (
	"encoding/json"
	"errors"
	"testing"

	enaeaAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/enaea"
	enaeaApi "github.com/yatori-dev/yatori-go-mobile-core/api/enaea"
)

// --- test seams ---

func fakeEnaeaCourses(courses []enaeaAgg.EnaeaCourse, err error) func() {
	orig := enaeaAllCoursesProvider
	enaeaAllCoursesProvider = func(_ *enaeaApi.EnaeaUserCache) ([]enaeaAgg.EnaeaCourse, error) {
		return courses, err
	}
	return func() { enaeaAllCoursesProvider = orig }
}

func fakeEnaeaVideos(videos []enaeaAgg.EnaeaVideo, err error) func() {
	orig := enaeaVideoProvider
	enaeaVideoProvider = func(_ *enaeaApi.EnaeaUserCache, _, _, _, _, _, _ string) ([]enaeaAgg.EnaeaVideo, error) {
		return videos, err
	}
	return func() { enaeaVideoProvider = orig }
}

func fakeEnaeaStatistic(key, value string, err error) func() {
	orig := enaeaStatisticProvider
	enaeaStatisticProvider = func(_ *enaeaApi.EnaeaUserCache, _, _, _ string) (string, string, error) {
		return key, value, err
	}
	return func() { enaeaStatisticProvider = orig }
}

func fakeEnaeaSubmit(progress float64, err error) func() {
	orig := enaeaSubmitProvider
	enaeaSubmitProvider = func(_ *enaeaApi.EnaeaUserCache, _, _, _, _ string, _ int64, _ bool) (float64, error) {
		return progress, err
	}
	return func() { enaeaSubmitProvider = orig }
}

const enaeaSessJSON = `{"platform":"enaea","token":"tok"}`

// --- GetCourses ---

func TestGetCoursesEnaea_RawFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeEnaeaCourses([]enaeaAgg.EnaeaCourse{
		{CourseId: "c1", CircleId: "ci1", SyllabusId: "s1", Remark: "node1",
			CourseContentType: "video", CourseExternalType: "", CourseContentLink: "http://x",
			CourseTitle: "课程A", StudyProgress: 50},
	}, nil)
	defer restore()

	e := parseEnvelope(t, GetCourses(enaeaSessJSON))
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
	for _, f := range []string{"courseId", "circleId", "courseContentType", "courseExternalType", "courseContentLink", "remark"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("course Raw missing %q", f)
		}
	}
}

// --- GetCourseDetail ---

const enaeaCourseJSON = `{"platform":"enaea","id":"c1","raw":{"courseId":"c1","circleId":"ci1","courseContentType":"video","remark":"node1"}}`

func TestGetCourseDetailEnaea_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(enaeaSessJSON, enaeaCourseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Platform != "enaea" || detail.ParentID != "c1" {
		t.Fatalf("unexpected: %+v", detail)
	}
	if len(detail.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(detail.Items))
	}
	raw := detail.Items[0].Raw
	for _, f := range []string{"courseId", "circleId", "courseContentType"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("detail Raw missing %q", f)
		}
	}
}

func TestGetCourseDetailEnaea_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	courseJSON := `{"id":"c1","raw":{"courseId":"c1","circleId":"ci1"}}`
	e := parseEnvelope(t, GetCourseDetail(enaeaSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestGetCourseDetailEnaea_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(enaeaSessJSON, `{"platform":"enaea","id":"","raw":{"circleId":"ci1"}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestGetCourseDetailEnaea_MissingCircleId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(enaeaSessJSON, `{"platform":"enaea","id":"c1","raw":{}}`))
	if e.OK {
		t.Fatal("missing circleId should fail")
	}
}

// --- GetTasks ---

const enaeaTaskCourseJSON = `{"platform":"enaea","id":"c1","raw":{"courseId":"c1","circleId":"ci1","courseContentType":"video"}}`

func TestGetTasksEnaea_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeEnaeaVideos([]enaeaAgg.EnaeaVideo{
		{Id: "v1", CourseId: "c1", CircleId: "ci1", CourseContentStr: "视频1", CourseName: "node1", VideoLength: 300, StudyProgress: 100},
		{Id: "v2", CourseId: "c1", CircleId: "ci1", CourseContentStr: "视频2", CourseName: "node1", VideoLength: 600, StudyProgress: 0},
	}, nil)
	defer restore()

	e := parseEnvelope(t, GetTasks(enaeaSessJSON, enaeaTaskCourseJSON))
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
	if res.Tasks[0].Type != "video" {
		t.Fatalf("task[0].type=%q, want video", res.Tasks[0].Type)
	}
	raw := res.Tasks[0].Raw
	for _, f := range []string{"videoId", "courseId", "circleId", "videoLength"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("task Raw missing %q", f)
		}
	}
}

func TestGetTasksEnaea_MissingFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// no id, no raw.courseId → missing courseId
	e := parseEnvelope(t, GetTasks(enaeaSessJSON, `{"platform":"enaea","raw":{"circleId":"ci1"}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
	// has courseId but no circleId
	e = parseEnvelope(t, GetTasks(enaeaSessJSON, `{"platform":"enaea","id":"c1","raw":{"courseId":"c1"}}`))
	if e.OK {
		t.Fatal("missing circleId should fail")
	}
}

func TestGetTasksEnaea_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeEnaeaVideos(nil, errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, GetTasks(enaeaSessJSON, enaeaTaskCourseJSON))
	if e.OK {
		t.Fatal("video provider error should fail")
	}
}

// --- RunTask ---

const enaeaRunTaskJSON = `{"platform":"enaea","id":"v1","raw":{"courseId":"c1","circleId":"ci1"}}`

func TestRunTaskEnaea_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	called := false
	orig := enaeaStatisticProvider
	enaeaStatisticProvider = func(_ *enaeaApi.EnaeaUserCache, _, _, _ string) (string, string, error) {
		called = true
		return "", "", nil
	}
	defer func() { enaeaStatisticProvider = orig }()

	taskJSON := `{"platform":"enaea","id":"v1","raw":{"courseId":"c1","circleId":"ci1"},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(enaeaSessJSON, taskJSON))
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
		t.Fatal("dryRun must not call statistic provider")
	}
}

func TestRunTaskEnaea_NormalModeSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStat := fakeEnaeaStatistic("key1", "val1", nil)
	defer restoreStat()
	restoreSubmit := fakeEnaeaSubmit(42, nil)
	defer restoreSubmit()

	e := parseEnvelope(t, RunTask(enaeaSessJSON, enaeaRunTaskJSON))
	if !e.OK {
		t.Fatalf("normal mode should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Platform != "enaea" || res.TaskID != "v1" {
		t.Fatalf("unexpected: %+v", res)
	}
	if res.Raw["progress"] != float64(42) {
		t.Fatalf("raw.progress=%v, want 42 (host needs it to stop at 100)", res.Raw["progress"])
	}
}

func TestRunTaskEnaea_FastModeSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStat := fakeEnaeaStatistic("key1", "val1", nil)
	defer restoreStat()
	restoreSubmit := fakeEnaeaSubmit(100, nil)
	defer restoreSubmit()

	taskJSON := `{"platform":"enaea","id":"v1","raw":{"courseId":"c1","circleId":"ci1"},"options":{"fast":true}}`
	e := parseEnvelope(t, RunTask(enaeaSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("fast mode should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
}

func TestRunTaskEnaea_MissingFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	cases := []string{
		`{"platform":"enaea","raw":{"courseId":"c1","circleId":"ci1"}}`,      // no videoId
		`{"platform":"enaea","id":"v1","raw":{"circleId":"ci1"}}`,             // no courseId
		`{"platform":"enaea","id":"v1","raw":{"courseId":"c1"}}`,              // no circleId
	}
	for i, tj := range cases {
		e := parseEnvelope(t, RunTask(enaeaSessJSON, tj))
		if e.OK {
			t.Fatalf("case %d: missing field should fail", i)
		}
	}
}

func TestRunTaskEnaea_StatisticProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeEnaeaStatistic("", "", errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, RunTask(enaeaSessJSON, enaeaRunTaskJSON))
	if e.OK {
		t.Fatal("statistic error should fail")
	}
}

func TestRunTaskEnaea_SubmitProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStat := fakeEnaeaStatistic("key1", "val1", nil)
	defer restoreStat()
	restoreSubmit := fakeEnaeaSubmit(0, errors.New("server error"))
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(enaeaSessJSON, enaeaRunTaskJSON))
	if e.OK {
		t.Fatal("submit error should fail")
	}
}
