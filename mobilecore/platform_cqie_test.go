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

const cqieFakeCourses = `{"msg":"操作成功","data":{"records":[{"id":"c1","name":"课程A","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","learned":"50%","sumTime":"01:00","haveTime":"00:30"}]}}`

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

func TestGetCoursesCqie_SkipsNonVideoRecords(t *testing.T) {
	resetState()
	Init("/tmp/test")
	raw := `{"msg":"操作成功","data":{"records":[` +
		`{"id":"video-course","name":"视频课","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","sumTime":"01:00","haveTime":"00:00"},` +
		`{"id":"other-course","name":"非视频课","studentCourseId":"sc2","coursewareId":"cw2","version":"v1"}` +
		`]}}`
	restore := fakeCqieCourses(raw, nil)
	defer restore()

	e := parseEnvelope(t, GetCourses(cqieSessJSON))
	if !e.OK {
		t.Fatalf("GetCourses failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res CourseListResult
	json.Unmarshal(b, &res)
	if len(res.Courses) != 1 || res.Courses[0].ID != "video-course" {
		t.Fatalf("courses=%+v, want only video-course", res.Courses)
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

func TestGetTasksCqie_ParsesProgressAndSegments(t *testing.T) {
	resetState()
	Init("/tmp/test")
	raw := `{"msg":"操作成功","data":[{"children":[{"courseCatalogVideoVos":[{` +
		`"id":"v1","courseId":"c1","unitId":"u1","name":"视频1","timeLength":60,"haveTime":"00:01:00",` +
		`"courseCatalogVideoSegments":[{"courseId":"c1","unitId":"u1","segmentName":"分段1",` +
		`"videoSegmentKnowledgeTimeRangesVos":[{"id":"sk1","segmentId":"seg1","knowledgeNodeId":"kn1","startTimeStr":"00:00","endTimeStr":"00:30"}]}]` +
		`}]}]}]}`
	restore := fakeCqieDetail(raw, nil)
	defer restore()

	e := parseEnvelope(t, GetTasks(cqieSessJSON, cqieCourseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 1 {
		t.Fatalf("tasks=%d, want 1", len(res.Tasks))
	}
	task := res.Tasks[0]
	if task.Progress != 100 || task.Status != "completed" {
		t.Fatalf("progress/status=(%v,%q), want (100,completed)", task.Progress, task.Status)
	}
	if task.Raw["studyTime"] != float64(60) {
		t.Fatalf("raw.studyTime=%v, want 60", task.Raw["studyTime"])
	}
	segments, ok := task.Raw["segments"].([]interface{})
	if !ok || len(segments) != 1 {
		t.Fatalf("raw.segments=%#v, want one segment", task.Raw["segments"])
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

func TestRunTaskCqie_ActionStartUsesConsoleSaveHandshake(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeCqieStartVideo("", errors.New("studyVideo must not be called"))
	defer restoreStart()
	restoreSave := fakeCqieSaveStudy(cqieFakeSave, nil)
	defer restoreSave()

	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","timeLength":300,"studyTime":12},"options":{"action":"start"}}`
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
	if res.Raw["maxCurrentPos"] != float64(12) {
		t.Fatalf("raw.maxCurrentPos=%v, want 12", res.Raw["maxCurrentPos"])
	}
}

func TestRunTaskCqie_ActionStartSavesSegmentsAndAnswersEmbeddedWork(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreSave := fakeCqieSaveStudy(cqieFakeSave, nil)
	defer restoreSave()

	origSegment := cqieSaveSegmentProvider
	origPullWork := cqiePullVideoWorkProvider
	origSubmitWork := cqieSubmitWorkAnswerProvider
	var segmentID, maxCurrentPos, submittedAnswer string
	cqieSaveSegmentProvider = func(_ *cqieApi.CqieUserCache, _, _, _, _, _, gotSegmentID, gotMaxCurrentPos, _ string, _, _ int) (string, error) {
		segmentID, maxCurrentPos = gotSegmentID, gotMaxCurrentPos
		return `{"msg":"操作成功","data":{}}`, nil
	}
	cqiePullVideoWorkProvider = func(_ *cqieApi.CqieUserCache, knowledgeNodeID, _, _ string) (string, error) {
		if knowledgeNodeID != "sk1" {
			t.Fatalf("work lookup id=%q, want sk1", knowledgeNodeID)
		}
		return `{"code":200,"data":[{"id":"q1","courseId":"c1","unitId":"u1","knowledgeNodeId":"kn1","questionType":1,"referenceAnswer":"B","status":0}]}`, nil
	}
	cqieSubmitWorkAnswerProvider = func(_ *cqieApi.CqieUserCache, answerJSON string) (string, error) {
		submittedAnswer = answerJSON
		return `{"code":200,"msg":"操作成功"}`, nil
	}
	defer func() {
		cqieSaveSegmentProvider = origSegment
		cqiePullVideoWorkProvider = origPullWork
		cqieSubmitWorkAnswerProvider = origSubmitWork
	}()

	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","timeLength":60,"studyTime":12,"segments":[{"id":"sk1","segmentId":"seg1","knowledgeNodeId":"kn1","courseId":"c1","unitId":"u1"}]},"options":{"action":"start"}}`
	e := parseEnvelope(t, RunTask(cqieSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=start should succeed: %s", e.Error)
	}
	if segmentID != "sk1" || maxCurrentPos != "12" {
		t.Fatalf("segment save=(%q,%q), want (sk1,12)", segmentID, maxCurrentPos)
	}
	var payload struct {
		StudentCourseId    string `json:"studentCourseId"`
		UnitId             string `json:"unitId"`
		CourseId           string `json:"courseId"`
		SegmentKnowledgeId string `json:"segmentKnowledgeId"`
		StudentId          string `json:"studentId"`
		VideoId            string `json:"videoId"`
		DeptId             string `json:"deptId"`
		MajorId            string `json:"majorId"`
		Version            string `json:"version"`
		OrgId              string `json:"orgId"`
		PoList             []struct {
			SubmitAnswer       string `json:"submitAnswer"`
			ExercisesId        string `json:"exercisesId"`
			QuestionType       int    `json:"questionType"`
			ReferenceAnswer    string `json:"referenceAnswer"`
			SegmentKnowledgeId string `json:"segmentKnowledgeId"`
		} `json:"poList"`
	}
	if err := json.Unmarshal([]byte(submittedAnswer), &payload); err != nil {
		t.Fatalf("decode submitted answer payload: %v", err)
	}
	if payload.StudentCourseId != "sc1" || payload.UnitId != "u1" || payload.CourseId != "c1" ||
		payload.SegmentKnowledgeId != "sk1" || payload.StudentId != "st1" || payload.VideoId != "v1" ||
		payload.DeptId != "d1" || payload.MajorId != "m1" || payload.Version != "v1" || payload.OrgId != "o1" {
		t.Fatalf("submitted answer identity fields mismatch: %+v", payload)
	}
	if len(payload.PoList) != 1 || payload.PoList[0].SubmitAnswer != "B" ||
		payload.PoList[0].ExercisesId != "q1" || payload.PoList[0].QuestionType != 1 ||
		payload.PoList[0].ReferenceAnswer != "B" || payload.PoList[0].SegmentKnowledgeId != "kn1" {
		t.Fatalf("submitted answer question fields mismatch: %+v", payload.PoList)
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

func TestRunTaskCqie_ActionEndUsesConsoleFinalSave(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreSubmit := fakeCqieSubmitStudy("", errors.New("submit must not be called for action=end"))
	defer restoreSubmit()

	origSave := cqieSaveStudyProvider
	var gotStart, gotStop int
	cqieSaveStudyProvider = func(_ *cqieApi.CqieUserCache, _, _, _, _, _, _ string, startPos, stopPos int) (string, error) {
		gotStart, gotStop = startPos, stopPos
		return cqieFakeSave, nil
	}
	defer func() { cqieSaveStudyProvider = origSave }()

	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","timeLength":300},"options":{"action":"end","startPos":300,"stopPos":300,"maxPos":300}}`
	e := parseEnvelope(t, RunTask(cqieSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=end should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "done" {
		t.Fatalf("expected done, got %q", res.Status)
	}
	if gotStart != 300 || gotStop != 300 {
		t.Fatalf("final save positions=(%d,%d), want (300,300)", gotStart, gotStop)
	}
}

func TestRunTaskCqie_ActionSubmitRestoresSessionCookie(t *testing.T) {
	resetState()
	Init("/tmp/test")
	origSubmit := cqieSubmitStudyProvider
	var gotCookie string
	cqieSubmitStudyProvider = func(cache *cqieApi.CqieUserCache, _, _, _, _, _, _, _ string, _, _, _ int) (string, error) {
		gotCookie = cache.GetCookie()
		return `{"msg":"操作成功"}`, nil
	}
	defer func() { cqieSubmitStudyProvider = origSubmit }()

	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","studyId":"study-1"},"options":{"action":"submit","startPos":0,"stopPos":0,"maxPos":3}}`
	sessionJSON := `{"platform":"cqie","token":"tok","cookies":"CAPTCHA=abc","extra":{"studentId":"st1","userId":"u1","orgId":"o1","deptId":"d1","orgMajorId":"m1"}}`
	e := parseEnvelope(t, RunTask(sessionJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=submit should succeed: %s", e.Error)
	}
	if gotCookie != "CAPTCHA=abc" {
		t.Fatalf("cookie=%q, want CAPTCHA=abc", gotCookie)
	}
}

func TestRunTaskCqie_ActionGetProgressReadsServerCompletion(t *testing.T) {
	resetState()
	Init("/tmp/test")
	raw := `{"msg":"操作成功","data":[{"children":[{"courseCatalogVideoVos":[{"id":"v1","courseId":"c1","unitId":"u1","name":"视频1","timeLength":60,"haveTime":"00:01:00"}]}]}]}`
	restore := fakeCqieDetail(raw, nil)
	defer restore()

	taskJSON := `{"platform":"cqie","id":"v1","raw":{"courseId":"c1","unitId":"u1","studentCourseId":"sc1","coursewareId":"cw1","version":"v1","timeLength":60},"options":{"action":"getProgress"}}`
	e := parseEnvelope(t, RunTask(cqieSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=getProgress should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "done" || res.Raw["progress"] != float64(100) {
		t.Fatalf("status/progress=(%q,%v), want (done,100)", res.Status, res.Raw["progress"])
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
