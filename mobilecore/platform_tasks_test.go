package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	ttcdwAgg "github.com/yatori-dev/yatori-go-mobile-core/aggregation/ttcdw"
	ttcdwApi "github.com/yatori-dev/yatori-go-mobile-core/api/ttcdw"
)

// --- ttcdw GetTasks ---

func fakeTtcdwCourseProvider(courses []ttcdwAgg.TtcdwCourse) func() {
	orig := ttcdwCourseProvider
	ttcdwCourseProvider = func(_ *ttcdwApi.TtcdwUserCache, _ ttcdwAgg.TtcdwClassRoom) ([]ttcdwAgg.TtcdwCourse, error) {
		return courses, nil
	}
	return func() { ttcdwCourseProvider = orig }
}

func TestGetTasksTtcdw_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeTtcdwCourseProvider([]ttcdwAgg.TtcdwCourse{
		{CourseId: "c1", Name: "视频课程", Progress: 0.5},
		{CourseId: "c2", Name: "文档课程", Progress: 1.0},
	})
	defer restore()

	sessJSON := `{"platform":"ttcdw","cookies":"SESS=x"}`
	courseJSON := `{"platform":"ttcdw","id":"item-1","raw":{"segmentId":"seg-1"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks ttcdw failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var result TaskListResult
	json.Unmarshal(b, &result)
	if result.Platform != "ttcdw" {
		t.Fatalf("platform=%q", result.Platform)
	}
	if len(result.Tasks) != 2 {
		t.Fatalf("expected 2 tasks, got %d", len(result.Tasks))
	}
	if result.Tasks[0].Type != "course" {
		t.Fatalf("type=%q", result.Tasks[0].Type)
	}
}

func TestGetTasksTtcdw_MissingSegmentId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"ttcdw","cookies":"SESS=x"}`
	courseJSON := `{"platform":"ttcdw","id":"item-1","raw":{}}` // no segmentId
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if e.OK {
		t.Fatal("should fail without segmentId")
	}
}

func TestGetTasksTtcdw_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := ttcdwCourseProvider
	ttcdwCourseProvider = func(_ *ttcdwApi.TtcdwUserCache, _ ttcdwAgg.TtcdwClassRoom) ([]ttcdwAgg.TtcdwCourse, error) {
		return nil, errors.New("network error")
	}
	defer func() { ttcdwCourseProvider = orig }()

	sessJSON := `{"platform":"ttcdw","cookies":"SESS=x"}`
	courseJSON := `{"platform":"ttcdw","id":"i","raw":{"segmentId":"s"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if e.OK {
		t.Fatal("should fail on provider error")
	}
}

func TestGetTasksTtcdw_CourseRawCarriesVideoInputs(t *testing.T) {
	// Course tasks' Raw must include all fields needed for video expansion
	resetState()
	Init("/tmp/test")
	restore := fakeTtcdwCourseProvider([]ttcdwAgg.TtcdwCourse{
		{CourseId: "c1", Name: "课程", Progress: 0.5},
	})
	defer restore()

	sessJSON := `{"platform":"ttcdw","cookies":"SESS=x"}`
	// Classroom input includes projectId/orgId (propagated from GetCourseDetail)
	courseJSON := `{"platform":"ttcdw","id":"item-1","raw":{"segmentId":"seg-1","projectId":"proj-1","orgId":"org-1"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var result TaskListResult
	json.Unmarshal(b, &result)
	if len(result.Tasks) != 1 {
		t.Fatalf("expected 1 task")
	}
	raw := result.Tasks[0].Raw
	for _, field := range []string{"courseId", "itemId", "segmentId", "projectId", "orgId"} {
		if strOf(raw[field]) == "" {
			t.Errorf("course task Raw missing %q", field)
		}
	}
	if strOf(raw["kind"]) != "course" {
		t.Errorf("kind should be 'course', got %q", strOf(raw["kind"]))
	}
}

func TestGetTasksTtcdw_CourseVideosSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := ttcdwVideoProvider
	ttcdwVideoProvider = func(_ *ttcdwApi.TtcdwUserCache, _ ttcdwAgg.TtcdwProject, _ ttcdwAgg.TtcdwClassRoom, _ ttcdwAgg.TtcdwCourse) ([]ttcdwAgg.TtcdwVideo, error) {
		return []ttcdwAgg.TtcdwVideo{
			{VideoId: "v1", Name: "第一节视频", Progress: 0.0, CourseWareType: 1},
			{VideoId: "v2", Name: "第二节视频", Progress: 1.0, CourseWareType: 1},
		}, nil
	}
	defer func() { ttcdwVideoProvider = orig }()

	sessJSON := `{"platform":"ttcdw","cookies":"SESS=x"}`
	courseJSON := `{"platform":"ttcdw","id":"c1","raw":{"courseId":"c1","itemId":"item-1","segmentId":"seg-1","projectId":"proj-1","orgId":"org-1","companyCode":"D387ED042DF13283","userId":"u:class","shortCourseId":"3086","courseType":"share"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks videos failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var result TaskListResult
	json.Unmarshal(b, &result)
	if len(result.Tasks) != 2 {
		t.Fatalf("expected 2 videos, got %d", len(result.Tasks))
	}
	if result.Tasks[0].Type != "video" {
		t.Fatalf("type=%q", result.Tasks[0].Type)
	}
	// RunTask fields present
	if strOf(result.Tasks[0].Raw["videoId"]) == "" {
		t.Error("videoId missing from video task Raw")
	}
	for _, field := range []string{"companyCode", "userId", "shortCourseId", "tickerCourseId", "courseType"} {
		if strOf(result.Tasks[0].Raw[field]) == "" {
			t.Errorf("%s missing from video task Raw", field)
		}
	}
}

func TestGetTasksTtcdw_CourseMissingRawFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"ttcdw","cookies":"SESS=x"}`
	// Has courseId (triggers video path) but missing itemId/segmentId/projectId/orgId
	courseJSON := `{"platform":"ttcdw","id":"c1","raw":{"courseId":"c1"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if e.OK {
		t.Fatal("should fail with missing raw fields")
	}
	if !strings.Contains(e.Error, "required") {
		t.Errorf("error should mention 'required', got: %q", e.Error)
	}
}

func TestGetTasksTtcdw_CourseVideoProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := ttcdwVideoProvider
	ttcdwVideoProvider = func(_ *ttcdwApi.TtcdwUserCache, _ ttcdwAgg.TtcdwProject, _ ttcdwAgg.TtcdwClassRoom, _ ttcdwAgg.TtcdwCourse) ([]ttcdwAgg.TtcdwVideo, error) {
		return nil, errors.New("server error")
	}
	defer func() { ttcdwVideoProvider = orig }()

	sessJSON := `{"platform":"ttcdw","cookies":"SESS=x"}`
	courseJSON := `{"platform":"ttcdw","id":"c1","raw":{"courseId":"c1","itemId":"i","segmentId":"s","projectId":"p","orgId":"o"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if e.OK {
		t.Fatal("should fail on provider error")
	}
}

// --- qingshuxuetang GetTasks ---

func fakeQsxtNodeProvider(raw string) func() {
	orig := qsxtNodeProvider
	qsxtNodeProvider = func(_, _ string) (string, error) { return raw, nil }
	return func() { qsxtNodeProvider = orig }
}

const qsxtFakeNodes = `{"hr":0,"data":[{"id":"n1","name":"第一节","iscomplete":"已完成"},{"id":"n2","name":"第二节","iscomplete":"未完成"}]}`

func TestGetTasksQsxt_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeQsxtNodeProvider(qsxtFakeNodes)
	defer restore()

	// primary path: coursewareUrl (from GetCourseDetail.Raw)
	sessJSON := `{"platform":"qingshuxuetang","token":"tok"}`
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{"coursewareUrl":"https://api.qingshuxuetang.com/v25_10/course/coursewareTree?id=abc"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks qsxt (coursewareUrl) failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var result TaskListResult
	json.Unmarshal(b, &result)
	if len(result.Tasks) != 2 {
		t.Fatalf("expected 2 tasks, got %d", len(result.Tasks))
	}
	if result.Tasks[0].Status != "done" {
		t.Fatalf("task[0].status=%q", result.Tasks[0].Status)
	}
}

func TestGetTasksQsxt_NodeUrlFallback(t *testing.T) {
	// legacy raw.nodeUrl still works
	resetState()
	Init("/tmp/test")
	restore := fakeQsxtNodeProvider(qsxtFakeNodes)
	defer restore()

	sessJSON := `{"platform":"qingshuxuetang","token":"tok"}`
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{"nodeUrl":"https://api.example.com/coursewareTree?id=abc"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks qsxt (nodeUrl fallback) failed: %s", e.Error)
	}
}

func TestGetTasksQsxt_MissingUrl(t *testing.T) {
	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"qingshuxuetang","token":"tok"}`
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if e.OK {
		t.Fatal("should fail without coursewareUrl or nodeUrl")
	}
	if !strings.Contains(e.Error, "coursewareUrl") {
		t.Errorf("error should mention coursewareUrl, got: %q", e.Error)
	}
}

func TestGetTasksQsxt_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := qsxtNodeProvider
	qsxtNodeProvider = func(_, _ string) (string, error) { return "", errors.New("network error") }
	defer func() { qsxtNodeProvider = orig }()

	sessJSON := `{"platform":"qingshuxuetang","token":"tok"}`
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{"nodeUrl":"http://x"}}`
	e := parseEnvelope(t, GetTasks(sessJSON, courseJSON))
	if e.OK {
		t.Fatal("should fail on provider error")
	}
}
