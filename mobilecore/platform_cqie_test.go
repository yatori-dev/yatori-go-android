package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	cqieApi "github.com/yatori-dev/yatori-go-mobile-core/api/cqie"
)

// --- test seams ---

func fakeQsieCaptcha(imgB64, cookie, uuidStr string, err error) func() {
	orig := cqieCaptchaProvider
	cqieCaptchaProvider = func(_ string) (string, string, string, error) { return imgB64, cookie, uuidStr, err }
	return func() { cqieCaptchaProvider = orig }
}

func fakeCqieLogin(raw string, err error) func() {
	orig := cqieLoginProvider
	cqieLoginProvider = func(_ *cqieApi.CqieUserCache) (string, error) { return raw, err }
	return func() { cqieLoginProvider = orig }
}

func fakeCqieUserDetails(raw string, err error) func() {
	orig := cqieUserDetailsProvider
	cqieUserDetailsProvider = func(_ *cqieApi.CqieUserCache) (string, error) { return raw, err }
	return func() { cqieUserDetailsProvider = orig }
}

func fakeCqieCourses(raw string, err error) func() {
	orig := cqieCoursesProvider
	cqieCoursesProvider = func(_ *cqieApi.CqieUserCache) (string, error) { return raw, err }
	return func() { cqieCoursesProvider = orig }
}

func fakeCqieDetail(raw string, err error) func() {
	orig := cqieDetailProvider
	cqieDetailProvider = func(_ *cqieApi.CqieUserCache, _, _, _ string) (string, error) { return raw, err }
	return func() { cqieDetailProvider = orig }
}

func fakeCqieStartVideo(raw string, err error) func() {
	orig := cqieStartVideoProvider
	cqieStartVideoProvider = func(_ *cqieApi.CqieUserCache, _, _, _ string) (string, error) { return raw, err }
	return func() { cqieStartVideoProvider = orig }
}

func fakeCqieSaveStudy(raw string, err error) func() {
	orig := cqieSaveStudyProvider
	cqieSaveStudyProvider = func(_ *cqieApi.CqieUserCache, _, _, _, _, _, _ string, _, _ int) (string, error) {
		return raw, err
	}
	return func() { cqieSaveStudyProvider = orig }
}

func fakeCqieSubmitStudy(raw string, err error) func() {
	orig := cqieSubmitStudyProvider
	cqieSubmitStudyProvider = func(_ *cqieApi.CqieUserCache, _, _, _, _, _, _, _ string, _, _, _ int) (string, error) {
		return raw, err
	}
	return func() { cqieSubmitStudyProvider = orig }
}

const cqieSessJSON = `{"platform":"cqie","token":"tok","extra":{"studentId":"st1","userId":"u1","orgId":"o1","deptId":"d1","orgMajorId":"m1","userName":"user1"}}`

// --- StartLogin / ContinueLogin ---

func TestStartLoginCqie_Challenge(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeQsieCaptcha("aGVsbG8=", "SESS=abc", "uuid-1", nil)
	defer restore()

	e := parseEnvelope(t, StartLogin("cqie", `{"account":"u","password":"p"}`))
	if !e.OK {
		t.Fatalf("StartLogin should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res StartLoginResult
	json.Unmarshal(b, &res)
	if res.Status != LoginStatusChallenge || res.Challenge == nil {
		t.Fatalf("expected challenge, got %+v", res)
	}
	if res.Challenge.ImageBase64 != "aGVsbG8=" {
		t.Fatalf("imageBase64 mismatch")
	}
}

func TestContinueLoginCqie_PasswordPropagated(t *testing.T) {
	// Regression: password must be set on cache BEFORE LoginApi is called.
	resetState()
	Init("/tmp/test")
	restoreCap := fakeQsieCaptcha("img==", "SESS=x", "uuid-1", nil)
	defer restoreCap()

	var capturedPassword string
	orig := cqieLoginProvider
	cqieLoginProvider = func(c *cqieApi.CqieUserCache) (string, error) {
		capturedPassword = c.Password
		return `{"code":200,"msg":"操作成功","data":{"access_token":"tok"}}`, nil
	}
	defer func() { cqieLoginProvider = orig }()

	restoreDetails := fakeCqieUserDetails(`{"data":{"userId":"u1","deptId":"d1","id":"st1","userName":"u","orgId":"o1","mobile":"","orgMajorId":"m1"}}`, nil)
	defer restoreDetails()

	e := parseEnvelope(t, StartLogin("cqie", `{"account":"user","password":"pass-123"}`))
	var sres StartLoginResult
	b, _ := json.Marshal(e.Data)
	json.Unmarshal(b, &sres)

	parseEnvelope(t, ContinueLogin(sres.TaskID, `{"taskId":"`+sres.TaskID+`","type":"image_ocr","text":"42"}`))

	if capturedPassword == "" {
		t.Fatal("cqieLoginProvider received empty password — password was not set before LoginApi call")
	}
	if capturedPassword != "pass-123" {
		t.Fatalf("password mismatch: got %q, want %q", capturedPassword, "pass-123")
	}
}

func TestContinueLoginCqie_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreCap := fakeQsieCaptcha("img==", "SESS=abc", "uuid-1", nil)
	defer restoreCap()
	restoreLogin := fakeCqieLogin(`{"code":200,"msg":"操作成功","data":{"access_token":"tok-x","user":{}}}`, nil)
	defer restoreLogin()
	restoreDetails := fakeCqieUserDetails(`{"data":{"userId":"u1","deptId":"d1","id":"st1","userName":"user1","orgId":"o1","mobile":"","orgMajorId":"m1"}}`, nil)
	defer restoreDetails()

	e := parseEnvelope(t, StartLogin("cqie", `{"account":"user","password":"pass"}`))
	var sres StartLoginResult
	b, _ := json.Marshal(e.Data)
	json.Unmarshal(b, &sres)

	e2 := parseEnvelope(t, ContinueLogin(sres.TaskID, `{"taskId":"`+sres.TaskID+`","type":"image_ocr","text":"1234"}`))
	if !e2.OK {
		t.Fatalf("ContinueLogin failed: %s", e2.Error)
	}
	b2, _ := json.Marshal(e2.Data)
	var cres ContinueLoginResult
	json.Unmarshal(b2, &cres)
	if cres.Status != LoginStatusDone || cres.Session == nil {
		t.Fatalf("expected done session, got %+v", cres)
	}
	if cres.Session.Platform != "cqie" {
		t.Fatalf("platform=%q", cres.Session.Platform)
	}
}

func TestStartLoginCqie_CaptchaError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeQsieCaptcha("", "", "", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, StartLogin("cqie", `{"account":"u","password":"p"}`))
	if e.OK {
		t.Fatal("captcha error should fail")
	}
}

// --- GetCourses ---

const cqieFakeCourses = `{"msg":"操作成功","data":{"records":[{"id":"c1","name":"课程A","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","learned":"50%"}]}}`

func TestGetCoursesCqie_RawFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeCqieCourses(cqieFakeCourses, nil)
	defer restore()

	e := parseEnvelope(t, GetCourses(cqieSessJSON))
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
	for _, f := range []string{"courseId", "studentCourseId", "coursewareId", "version"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("course Raw missing %q", f)
		}
	}
}

// --- GetCourseDetail ---

const cqieCourseJSON = `{"platform":"cqie","id":"c1","raw":{"courseId":"c1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1"}}`

func TestGetCourseDetailCqie_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(cqieSessJSON, cqieCourseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Platform != "cqie" || detail.ParentID != "c1" {
		t.Fatalf("unexpected: %+v", detail)
	}
	for _, f := range []string{"courseId", "studentCourseId", "coursewareId", "version"} {
		if _, ok := detail.Items[0].Raw[f]; !ok {
			t.Errorf("detail Raw missing %q", f)
		}
	}
}

func TestGetCourseDetailCqie_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(cqieSessJSON, `{"id":"c1","raw":{"courseId":"c1","studentCourseId":"sc1"}}`))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestGetCourseDetailCqie_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(cqieSessJSON, `{"platform":"cqie","id":"","raw":{}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

// --- GetTasks ---

const cqieFakeProgress = `{"msg":"操作成功","data":[{"children":[{"courseCatalogVideoVos":[{"id":"v1","courseId":"c1","unitId":"u1","name":"视频1","timeLength":300}]}]}]}`

func TestGetTasksCqie_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeCqieDetail(cqieFakeProgress, nil)
	defer restore()

	e := parseEnvelope(t, GetTasks(cqieSessJSON, cqieCourseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 1 {
		t.Fatalf("expected 1 task, got %d", len(res.Tasks))
	}
	if res.Tasks[0].Type != "video" {
		t.Fatalf("type=%q", res.Tasks[0].Type)
	}
	raw := res.Tasks[0].Raw
	for _, f := range []string{"videoId", "courseId", "unitId", "studentCourseId"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("task Raw missing %q", f)
		}
	}
}

func TestGetTasksCqie_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetTasks(cqieSessJSON, `{"platform":"cqie","raw":{"studentCourseId":"sc1"}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestGetTasksCqie_MissingStudentCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetTasks(cqieSessJSON, `{"platform":"cqie","id":"c1","raw":{"courseId":"c1"}}`))
	if e.OK {
		t.Fatal("missing studentCourseId should fail")
	}
}

func TestGetTasksCqie_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeCqieDetail("", errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, GetTasks(cqieSessJSON, cqieCourseJSON))
	if e.OK {
		t.Fatal("provider error should fail")
	}
}

// --- RunTask ---

const cqieTaskJSON = `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","timeLength":300}}`
const cqieFakeStartVideo = `{"msg":"操作成功","data":{"id":"study-1","coursewareId":"cw1","maxCurrentPos":0}}`
const cqieFakeSave = `{"msg":"操作成功","data":{"id":"study-1"}}`

func TestRunTaskCqie_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1"},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(cqieSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("dryRun should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" {
		t.Fatalf("expected dry_run, got %q", res.Status)
	}
}

func TestRunTaskCqie_SubmitSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeCqieStartVideo(cqieFakeStartVideo, nil)
	defer restoreStart()
	restoreSave := fakeCqieSaveStudy(cqieFakeSave, nil)
	defer restoreSave()

	e := parseEnvelope(t, RunTask(cqieSessJSON, cqieTaskJSON))
	if !e.OK {
		t.Fatalf("submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Platform != "cqie" || res.TaskID != "v1" {
		t.Fatalf("unexpected: %+v", res)
	}
}

func TestRunTaskCqie_MissingVideoId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(cqieSessJSON, `{"platform":"cqie","raw":{"courseId":"c1","studentCourseId":"sc1"}}`))
	if e.OK {
		t.Fatal("missing videoId should fail")
	}
}

func TestRunTaskCqie_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(cqieSessJSON, `{"platform":"cqie","id":"v1","raw":{"studentCourseId":"sc1"}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestRunTaskCqie_MissingStudentCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(cqieSessJSON, `{"platform":"cqie","id":"v1","raw":{"courseId":"c1"}}`))
	if e.OK {
		t.Fatal("missing studentCourseId should fail")
	}
}

func TestRunTaskCqie_StartVideoError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeCqieStartVideo("", errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, RunTask(cqieSessJSON, cqieTaskJSON))
	if e.OK {
		t.Fatal("start video error should fail")
	}
}

func TestRunTaskCqie_SaveStudyError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeCqieStartVideo(cqieFakeStartVideo, nil)
	defer restoreStart()
	restore := fakeCqieSaveStudy("", errors.New("server error"))
	defer restore()
	e := parseEnvelope(t, RunTask(cqieSessJSON, cqieTaskJSON))
	if e.OK {
		t.Fatal("save study error should fail")
	}
}

func TestRunTaskCqie_StartVideoNonSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeCqieStartVideo(`{"msg":"获取失败","data":null}`, nil)
	defer restore()
	e := parseEnvelope(t, RunTask(cqieSessJSON, cqieTaskJSON))
	if e.OK {
		t.Fatal("non-success msg should fail")
	}
	if !strings.Contains(e.Error, "start study failed") {
		t.Errorf("expected 'start study failed' in error, got %q", e.Error)
	}
}

// --- host-driven action primitives ---

func TestRunTaskCqie_ActionStart(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeCqieStartVideo(cqieFakeStartVideo, nil)
	defer restoreStart()
	// Save must NOT be called on action=start; error provider proves it.
	restoreSave := fakeCqieSaveStudy("", errors.New("save must not be called"))
	defer restoreSave()

	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","timeLength":300},"options":{"action":"start"}}`
	e := parseEnvelope(t, RunTask(cqieSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=start should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "started" {
		t.Fatalf("expected started, got %q", res.Status)
	}
	if res.Raw["studyId"] != "study-1" {
		t.Fatalf("raw.studyId=%v, want study-1", res.Raw["studyId"])
	}
	if _, ok := res.Raw["maxCurrentPos"]; !ok {
		t.Fatal("raw.maxCurrentPos must be present after start")
	}
}

func TestRunTaskCqie_ActionSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// StartVideo must NOT be called on action=submit; error provider proves it.
	restoreStart := fakeCqieStartVideo("", errors.New("start must not be called"))
	defer restoreStart()
	restoreSubmit := fakeCqieSubmitStudy(`{"msg":"操作成功"}`, nil)
	defer restoreSubmit()

	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","timeLength":300,"studyId":"study-1"},"options":{"action":"submit","startPos":0,"stopPos":3,"maxPos":3}}`
	e := parseEnvelope(t, RunTask(cqieSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Raw["maxPos"] != float64(3) {
		t.Fatalf("raw.maxPos=%v, want 3", res.Raw["maxPos"])
	}
}

func TestRunTaskCqie_ActionSubmitMissingStudyId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","studentCourseId":"sc1"},"options":{"action":"submit"}}`
	e := parseEnvelope(t, RunTask(cqieSessJSON, taskJSON))
	if e.OK {
		t.Fatal("action=submit without studyId should fail")
	}
	if !strings.Contains(e.Error, "studyId") {
		t.Errorf("expected 'studyId' in error, got %q", e.Error)
	}
}
