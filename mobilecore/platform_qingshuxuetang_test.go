package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

// fakeQsxtCaptcha installs a fake captcha provider for tests, returns restore func.
func fakeQsxtCaptcha(sessionID, imageB64 string) func() {
	orig := qsxtCaptchaProvider
	qsxtCaptchaProvider = func(account string) (string, string, error) {
		return sessionID, imageB64, nil
	}
	return func() { qsxtCaptchaProvider = orig }
}

// fakeQsxtLogin installs a fake login provider for tests, returns restore func.
func fakeQsxtLogin(token string) func() {
	orig := qsxtLoginProvider
	qsxtLoginProvider = func(account, password, sessionID, verCode string) (string, error) {
		return token, nil
	}
	return func() { qsxtLoginProvider = orig }
}

func TestStartLoginQsxt_Challenge(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeQsxtCaptcha("sess-abc", "aGVsbG8=")
	defer restore()

	e := parseEnvelope(t, StartLogin("qingshuxuetang", `{"account":"user","password":"pass"}`))
	if !e.OK {
		t.Fatalf("StartLogin qingshuxuetang should succeed: %s", e.Error)
	}
	raw, _ := json.Marshal(e.Data)
	var res StartLoginResult
	json.Unmarshal(raw, &res)

	if res.Status != LoginStatusChallenge {
		t.Fatalf("expected status=challenge, got %q", res.Status)
	}
	if res.TaskID == "" {
		t.Fatal("taskId should be set")
	}
	if res.Challenge == nil {
		t.Fatal("challenge should be set")
	}
	if res.Challenge.TaskID != res.TaskID {
		t.Fatalf("challenge.taskId %q != data.taskId %q", res.Challenge.TaskID, res.TaskID)
	}
	if res.Challenge.Platform != "qingshuxuetang" {
		t.Fatalf("challenge.platform = %q", res.Challenge.Platform)
	}
	if res.Challenge.Type != ChallengeTypeImageOCR {
		t.Fatalf("challenge.type = %q", res.Challenge.Type)
	}
	if res.Challenge.ImageBase64 != "aGVsbG8=" {
		t.Fatalf("imageBase64 mismatch: %q", res.Challenge.ImageBase64)
	}
	if !strings.Contains(res.Challenge.Hint, "验证码") {
		t.Fatalf("hint should mention 验证码, got %q", res.Challenge.Hint)
	}

	// task should be in store
	if _, ok := pendingLogins.get(res.TaskID); !ok {
		t.Fatal("pending task should exist after StartLogin")
	}
}

func TestStartLoginQsxt_CaptchaError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := qsxtCaptchaProvider
	defer func() { qsxtCaptchaProvider = orig }()
	qsxtCaptchaProvider = func(account string) (string, string, error) {
		return "", "", errors.New("captcha server unreachable")
	}
	e := parseEnvelope(t, StartLogin("qingshuxuetang", `{"account":"u","password":"p"}`))
	if e.OK {
		t.Fatal("StartLogin should fail when captcha errors")
	}
}

func TestContinueLoginQsxt_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreCaptcha := fakeQsxtCaptcha("sess-1", "img==")
	defer restoreCaptcha()
	restoreLogin := fakeQsxtLogin("tok-xyz")
	defer restoreLogin()

	// Issue challenge
	e := parseEnvelope(t, StartLogin("qingshuxuetang", `{"account":"u","password":"p"}`))
	if !e.OK {
		t.Fatalf("StartLogin failed: %s", e.Error)
	}
	var res StartLoginResult
	raw, _ := json.Marshal(e.Data)
	json.Unmarshal(raw, &res)
	taskID := res.TaskID

	// Submit OCR result
	resultJSON := `{"taskId":"` + taskID + `","type":"image_ocr","text":"42"}`
	e2 := parseEnvelope(t, ContinueLogin(taskID, resultJSON))
	if !e2.OK {
		t.Fatalf("ContinueLogin failed: %s", e2.Error)
	}
	var res2 ContinueLoginResult
	raw2, _ := json.Marshal(e2.Data)
	json.Unmarshal(raw2, &res2)

	if res2.Status != LoginStatusDone {
		t.Fatalf("expected status=done, got %q", res2.Status)
	}
	if res2.Session == nil {
		t.Fatal("session should be set on done")
	}
	if res2.Session.Platform != "qingshuxuetang" {
		t.Fatalf("session.platform = %q", res2.Session.Platform)
	}
	if res2.Session.Token != "tok-xyz" {
		t.Fatalf("session.token = %q", res2.Session.Token)
	}

	// task should be cleaned up
	if _, ok := pendingLogins.get(taskID); ok {
		t.Fatal("task should be deleted after successful login")
	}
}

func TestContinueLoginQsxt_TaskIDMismatch(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeQsxtCaptcha("sess", "img")
	defer restore()

	e := parseEnvelope(t, StartLogin("qingshuxuetang", `{"account":"u","password":"p"}`))
	var res StartLoginResult
	raw, _ := json.Marshal(e.Data)
	json.Unmarshal(raw, &res)

	mismatch := `{"taskId":"wrong-id","type":"image_ocr","text":"1"}`
	e2 := parseEnvelope(t, ContinueLogin(res.TaskID, mismatch))
	if e2.OK {
		t.Fatal("ContinueLogin with mismatched taskId should fail")
	}
	if !strings.Contains(e2.Error, "mismatch") {
		t.Errorf("expected 'mismatch' in error, got: %q", e2.Error)
	}
	// cleanup
	pendingLogins.delete(res.TaskID)
}

func TestCancelLoginDeletesQsxtTask(t *testing.T) {
	restore := fakeQsxtCaptcha("s", "i")
	defer restore()
	resetState()
	Init("/tmp/test")

	e := parseEnvelope(t, StartLogin("qingshuxuetang", `{"account":"u","password":"p"}`))
	var res StartLoginResult
	raw, _ := json.Marshal(e.Data)
	json.Unmarshal(raw, &res)

	e2 := parseEnvelope(t, CancelLogin(res.TaskID))
	if !e2.OK {
		t.Fatalf("CancelLogin failed: %s", e2.Error)
	}
	if _, ok := pendingLogins.get(res.TaskID); ok {
		t.Fatal("task should be gone after cancel")
	}
}

func TestGetCoursesQsxt_RawFields(t *testing.T) {
	// Verify expanded Raw includes schoolId/periodId/courseId for GetCourseDetail
	raw := `{"hr":0,"data":[{"classId":"c1","courseName":"课程A","progress":0.5,"schoolName":"学校","schoolId":"s1","semesterId":"p1","courseId":"co1"}]}`
	orig := qsxtCoursesProvider
	defer func() { qsxtCoursesProvider = orig }()
	qsxtCoursesProvider = func(_ string) (string, error) { return raw, nil }

	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"qingshuxuetang","token":"tok"}`
	e := parseEnvelope(t, GetCourses(sessJSON))
	if !e.OK {
		t.Fatalf("GetCourses failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res CourseListResult
	json.Unmarshal(b, &res)
	if len(res.Courses) != 1 {
		t.Fatalf("expected 1 course, got %d", len(res.Courses))
	}
	c := res.Courses[0]
	if c.ID != "c1" {
		t.Fatalf("id=%q", c.ID)
	}
	if c.Raw["schoolId"] != "s1" {
		t.Fatalf("schoolId=%v", c.Raw["schoolId"])
	}
	if c.Raw["periodId"] != "p1" {
		t.Fatalf("periodId=%v", c.Raw["periodId"])
	}
	if c.Raw["courseId"] != "co1" {
		t.Fatalf("courseId=%v", c.Raw["courseId"])
	}
}

func TestGetCourseDetailQsxt_Success(t *testing.T) {
	detailRaw := `{"hr":0,"data":{"courseName":"课程详情","desc":"介绍内容"}}`
	origDetail := qsxtCourseDetailProvider
	defer func() { qsxtCourseDetailProvider = origDetail }()
	qsxtCourseDetailProvider = func(_, _, _, _, _ string) (string, error) {
		return detailRaw, nil
	}

	resetState()
	Init("/tmp/test")
	sessJSON := `{"platform":"qingshuxuetang","token":"tok"}`
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{"schoolId":"s1","periodId":"p1","courseId":"co1"}}`
	e := parseEnvelope(t, GetCourseDetail(sessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Platform != "qingshuxuetang" {
		t.Fatalf("platform=%q", detail.Platform)
	}
	if detail.ParentID != "c1" {
		t.Fatalf("parentId=%q", detail.ParentID)
	}
	if len(detail.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(detail.Items))
	}
}

func TestGetCourseDetailQsxt_MissingRawFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Missing required raw fields
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{"schoolName":"x"}}`
	e := parseEnvelope(t, GetCourseDetail(`{"platform":"qingshuxuetang","token":"tok"}`, courseJSON))
	if e.OK {
		t.Fatal("should fail with missing raw fields")
	}
	if !strings.Contains(e.Error, "schoolId") && !strings.Contains(e.Error, "periodId") {
		t.Errorf("error should mention missing fields, got: %q", e.Error)
	}
}

func TestGetCourseDetailQsxt_ProviderError(t *testing.T) {
	origDetail := qsxtCourseDetailProvider
	defer func() { qsxtCourseDetailProvider = origDetail }()
	qsxtCourseDetailProvider = func(_, _, _, _, _ string) (string, error) {
		return "", errors.New("network error")
	}

	resetState()
	Init("/tmp/test")
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{"schoolId":"s1","periodId":"p1","courseId":"co1"}}`
	e := parseEnvelope(t, GetCourseDetail(`{"platform":"qingshuxuetang","token":"tok"}`, courseJSON))
	if e.OK {
		t.Fatal("should fail on provider error")
	}
}

func TestGetCourseDetailQsxt_RawIncludesCoursewareUrl(t *testing.T) {
	// Phase 3.6.1 contract test: coursewareUrl from API must survive into Raw
	detailRaw := `{"hr":0,"data":{"courseName":"课程A","coursewareUrl":"https://api.qingshuxuetang.com/v25_10/course/coursewareTree?id=abc&sign=xyz"}}`
	origDetail := qsxtCourseDetailProvider
	defer func() { qsxtCourseDetailProvider = origDetail }()
	qsxtCourseDetailProvider = func(_, _, _, _, _ string) (string, error) { return detailRaw, nil }

	resetState()
	Init("/tmp/test")
	courseJSON := `{"platform":"qingshuxuetang","id":"c1","raw":{"schoolId":"s1","periodId":"p1","courseId":"co1"}}`
	e := parseEnvelope(t, GetCourseDetail(`{"platform":"qingshuxuetang","token":"tok"}`, courseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if len(detail.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(detail.Items))
	}
	url, _ := detail.Items[0].Raw["coursewareUrl"].(string)
	if url == "" {
		t.Fatal("coursewareUrl must be present in Items[0].Raw")
	}
	if !strings.Contains(url, "coursewareTree") {
		t.Fatalf("coursewareUrl looks wrong: %q", url)
	}
}

func TestStartLoginQsxt_NotInitialized(t *testing.T) {
	resetState()
	e := parseEnvelope(t, StartLogin("qingshuxuetang", `{"account":"u","password":"p"}`))
	if e.OK {
		t.Fatal("StartLogin before Init should fail")
	}
}

// --- qingshuxuetang RunTask tests ---

const qsxtRunSessJSON = `{"platform":"qingshuxuetang","token":"tok"}`
const qsxtRunTaskJSON = `{"platform":"qingshuxuetang","id":"node-1","raw":{"classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"}}`

func fakeQsxtStart(raw string, err error) func() {
	orig := qsxtStartStudyProvider
	qsxtStartStudyProvider = func(_, _, _, _, _, _, _ string) (string, error) { return raw, err }
	return func() { qsxtStartStudyProvider = orig }
}

func fakeQsxtSubmit(raw string, err error) func() {
	orig := qsxtSubmitStudyProvider
	qsxtSubmitStudyProvider = func(_ string, _ string, _ string, _ int, _ bool) (string, error) { return raw, err }
	return func() { qsxtSubmitStudyProvider = orig }
}

func fakeQsxtPullWorkList(raw string, err error) func() {
	orig := qsxtPullWorkListProvider
	qsxtPullWorkListProvider = func(_, _, _, _, _ string) (string, error) { return raw, err }
	return func() { qsxtPullWorkListProvider = orig }
}

func fakeQsxtPullWorkQuestions(raw string, err error) func() {
	orig := qsxtPullWorkQuestionsProvider
	qsxtPullWorkQuestionsProvider = func(_, _, _, _, _ string) (string, error) { return raw, err }
	return func() { qsxtPullWorkQuestionsProvider = orig }
}

func fakeQsxtSubmitAnswer(raw string, err error) func() {
	orig := qsxtSubmitAnswerProvider
	qsxtSubmitAnswerProvider = func(_, _, _, _, _ string) (string, error) { return raw, err }
	return func() { qsxtSubmitAnswerProvider = orig }
}

func fakeQsxtSaveAnswer(raw string, err error) func() {
	orig := qsxtSaveAnswerProvider
	qsxtSaveAnswerProvider = func(_, _ string) (string, error) { return raw, err }
	return func() { qsxtSaveAnswerProvider = orig }
}

func TestRunTaskQsxt_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"qingshuxuetang","id":"node-1","raw":{"classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
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

func TestRunTaskQsxt_SubmitSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":"record-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeQsxtSubmit(`{"hr":0,"data":true}`, nil)
	defer restoreSubmit()

	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, qsxtRunTaskJSON))
	if !e.OK {
		t.Fatalf("submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Platform != "qingshuxuetang" {
		t.Fatalf("platform=%q", res.Platform)
	}
	if res.TaskID != "node-1" {
		t.Fatalf("taskId=%q", res.TaskID)
	}
}

func TestRunTaskQsxt_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":"record-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeQsxtSubmit(`{"hr":0,"data":true}`, nil)
	defer restoreSubmit()

	taskJSON := `{"id":"node-1","raw":{"classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestRunTaskQsxt_ContentIdFallback(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":"record-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeQsxtSubmit(`{"hr":0,"data":true}`, nil)
	defer restoreSubmit()

	// raw.nodeId fallback
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","raw":{"nodeId":"n1","classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"}}`))
	if !e.OK {
		t.Fatalf("raw.nodeId fallback failed: %s", e.Error)
	}
	// raw.contentId fallback
	e = parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","raw":{"contentId":"n2","classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"}}`))
	if !e.OK {
		t.Fatalf("raw.contentId fallback failed: %s", e.Error)
	}
	// raw.id fallback
	e = parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","raw":{"id":"n3","classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"}}`))
	if !e.OK {
		t.Fatalf("raw.id fallback failed: %s", e.Error)
	}
}

func TestRunTaskQsxt_MissingContentId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","raw":{"classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"}}`))
	if e.OK {
		t.Fatal("missing contentId should fail")
	}
}

func TestRunTaskQsxt_MissingClassId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","id":"n1","raw":{"courseId":"co1","periodId":"p1","schoolId":"s1"}}`))
	if e.OK {
		t.Fatal("missing classId should fail")
	}
}

func TestRunTaskQsxt_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","id":"n1","raw":{"classId":"c1","periodId":"p1","schoolId":"s1"}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestRunTaskQsxt_PeriodIdFallback(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":"record-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeQsxtSubmit(`{"hr":0,"data":true}`, nil)
	defer restoreSubmit()

	// semesterId fallback should work
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","id":"n1","raw":{"classId":"c1","courseId":"co1","semesterId":"sem1","schoolId":"s1"}}`))
	if !e.OK {
		t.Fatalf("semesterId fallback failed: %s", e.Error)
	}

	// missing both periodId and semesterId should fail
	e = parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","id":"n1","raw":{"classId":"c1","courseId":"co1","schoolId":"s1"}}`))
	if e.OK {
		t.Fatal("missing periodId should fail")
	}
}

func TestRunTaskQsxt_MissingSchoolId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, `{"platform":"qingshuxuetang","id":"n1","raw":{"classId":"c1","courseId":"co1","periodId":"p1"}}`))
	if e.OK {
		t.Fatal("missing schoolId should fail")
	}
}

func TestRunTaskQsxt_StartProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart("", errors.New("network error"))
	defer restoreStart()
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, qsxtRunTaskJSON))
	if e.OK {
		t.Fatal("start provider error should fail")
	}
}

func TestRunTaskQsxt_StartHrNonZero(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":1,"data":null}`, nil)
	defer restoreStart()
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, qsxtRunTaskJSON))
	if e.OK {
		t.Fatal("start hr!=0 should fail")
	}
	if !strings.Contains(e.Error, "start study failed") {
		t.Errorf("expected 'start study failed', got: %q", e.Error)
	}
}

func TestRunTaskQsxt_StartMissingServerRecordId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":""}`, nil)
	defer restoreStart()
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, qsxtRunTaskJSON))
	if e.OK {
		t.Fatal("start missing serverRecordId should fail")
	}
	if !strings.Contains(e.Error, "serverRecordId") {
		t.Errorf("expected 'serverRecordId' in error, got: %q", e.Error)
	}
}

func TestRunTaskQsxt_SubmitProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":"record-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeQsxtSubmit("", errors.New("network error"))
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, qsxtRunTaskJSON))
	if e.OK {
		t.Fatal("submit provider error should fail")
	}
}

func TestRunTaskQsxt_SubmitHrNonZero(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":"record-1"}`, nil)
	defer restoreStart()
	restoreSubmit := fakeQsxtSubmit(`{"hr":1,"data":null}`, nil)
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, qsxtRunTaskJSON))
	if e.OK {
		t.Fatal("submit hr!=0 should fail")
	}
	if !strings.Contains(e.Error, "submit study time failed") {
		t.Errorf("expected 'submit study time failed', got: %q", e.Error)
	}
}

// --- host-driven action primitives ---

func TestRunTaskQsxt_ActionStart(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeQsxtStart(`{"hr":0,"data":"record-77"}`, nil)
	defer restoreStart()
	// Submit must NOT be called on action=start; error provider proves it.
	restoreSubmit := fakeQsxtSubmit("", errors.New("submit must not be called"))
	defer restoreSubmit()

	taskJSON := `{"platform":"qingshuxuetang","id":"node-1","raw":{"classId":"c1","courseId":"co1","periodId":"p1","schoolId":"s1"},"options":{"action":"start"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=start should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "started" {
		t.Fatalf("expected started, got %q", res.Status)
	}
	if res.Raw["serverRecordId"] != "record-77" {
		t.Fatalf("raw.serverRecordId=%v, want record-77", res.Raw["serverRecordId"])
	}
}

func TestRunTaskQsxt_ActionContinue(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Start must NOT be called on action=continue; error provider proves it.
	restoreStart := fakeQsxtStart("", errors.New("start must not be called"))
	defer restoreStart()
	restoreSubmit := fakeQsxtSubmit(`{"hr":0,"data":true}`, nil)
	defer restoreSubmit()

	taskJSON := `{"platform":"qingshuxuetang","id":"node-1","raw":{"schoolId":"s1","serverRecordId":"rec-9"},"options":{"action":"continue","position":60}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=continue should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Raw["serverRecordId"] != "rec-9" {
		t.Fatalf("raw.serverRecordId=%v, want rec-9", res.Raw["serverRecordId"])
	}
	if res.Raw["isEnd"] != false {
		t.Fatalf("raw.isEnd=%v, want false for continue", res.Raw["isEnd"])
	}
}

func TestRunTaskQsxt_ActionSubmitMissingServerRecordId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"qingshuxuetang","id":"node-1","raw":{"schoolId":"s1"},"options":{"action":"continue"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if e.OK {
		t.Fatal("action=continue without serverRecordId should fail")
	}
	if !strings.Contains(e.Error, "serverRecordId") {
		t.Errorf("expected 'serverRecordId' in error, got %q", e.Error)
	}
}

// --- work/exam (platform solution, no AI) ---

const qsxtWorkListRaw = `{"hr":0,"data":{"rows":[{"id":"quiz-1","title":"作业1","type":2,"totalTime":300,"answerStatus":-1,"courseId":"co1","passScore":60,"finalExam":false,"free":true,"outOfOrder":false,"viewDetail":true,"courseName":"课程","webDetailUrl":"http://x"}]}}`
const qsxtQuestionsRaw = `{"hr":0,"data":{"studentQuestions":[{"questionId":"qq1","solution":"B","score":10},{"questionId":"qq2","solution":"AC","score":10}]}}`

func TestRunTaskQsxt_PullWork(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeQsxtPullWorkList(qsxtWorkListRaw, nil)
	defer restore()

	taskJSON := `{"platform":"qingshuxuetang","raw":{"periodId":"p1","classId":"c1","schoolId":"s1","courseId":"co1"},"options":{"action":"pullWork"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=pullWork should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "done" {
		t.Fatalf("expected done, got %q", res.Status)
	}
	works, ok := res.Raw["works"].([]interface{})
	if !ok || len(works) != 1 {
		t.Fatalf("expected 1 work in raw.works, got %v", res.Raw["works"])
	}
	w0 := works[0].(map[string]interface{})
	if w0["quizId"] != "quiz-1" {
		t.Fatalf("work quizId=%v, want quiz-1", w0["quizId"])
	}
}

func TestRunTaskQsxt_PullWork_MissingFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"qingshuxuetang","raw":{"classId":"c1"},"options":{"action":"pullWork"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if e.OK {
		t.Fatal("pullWork without required fields should fail")
	}
}

func TestRunTaskQsxt_RunWork_Save(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreQ := fakeQsxtPullWorkQuestions(qsxtQuestionsRaw, nil)
	defer restoreQ()
	var submitCount int
	origSub := qsxtSubmitAnswerProvider
	qsxtSubmitAnswerProvider = func(_, _, _, _, _ string) (string, error) { submitCount++; return `{"hr":0}`, nil }
	defer func() { qsxtSubmitAnswerProvider = origSub }()
	var savedPayload string
	origSave := qsxtSaveAnswerProvider
	qsxtSaveAnswerProvider = func(_, answers string) (string, error) { savedPayload = answers; return `{"hr":0}`, nil }
	defer func() { qsxtSaveAnswerProvider = origSave }()

	taskJSON := `{"platform":"qingshuxuetang","raw":{"quizId":"quiz-1","classId":"45","schoolId":"s1","courseId":"co1","totalTime":300},"options":{"action":"work"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=work should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "saved" {
		t.Fatalf("expected saved (submit=false), got %q", res.Status)
	}
	if submitCount != 2 {
		t.Fatalf("expected 2 per-question submits, got %d", submitCount)
	}
	if res.Raw["questions"] != float64(2) {
		t.Fatalf("raw.questions=%v, want 2", res.Raw["questions"])
	}
	// save payload must carry action=0 and both answers
	if !strings.Contains(savedPayload, `"action":0`) {
		t.Fatalf("save payload should have action=0 (save), got: %s", savedPayload)
	}
	if !strings.Contains(savedPayload, `"answer":"B"`) || !strings.Contains(savedPayload, `"answer":"AC"`) {
		t.Fatalf("save payload missing solutions: %s", savedPayload)
	}
}

func TestRunTaskQsxt_RunWork_Submit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreQ := fakeQsxtPullWorkQuestions(qsxtQuestionsRaw, nil)
	defer restoreQ()
	restoreSub := fakeQsxtSubmitAnswer(`{"hr":0}`, nil)
	defer restoreSub()
	var savedPayload string
	origSave := qsxtSaveAnswerProvider
	qsxtSaveAnswerProvider = func(_, answers string) (string, error) { savedPayload = answers; return `{"hr":0}`, nil }
	defer func() { qsxtSaveAnswerProvider = origSave }()

	taskJSON := `{"platform":"qingshuxuetang","raw":{"quizId":"quiz-1","classId":"45","schoolId":"s1","courseId":"co1","totalTime":300},"options":{"action":"work","submit":true}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=work submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted (submit=true), got %q", res.Status)
	}
	if !strings.Contains(savedPayload, `"action":1`) {
		t.Fatalf("save payload should have action=1 (submit), got: %s", savedPayload)
	}
}

func TestRunTaskQsxt_RunWork_MissingSolution(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreQ := fakeQsxtPullWorkQuestions(`{"hr":0,"data":{"studentQuestions":[{"questionId":"qq1","solution":"","score":10}]}}`, nil)
	defer restoreQ()
	restoreSub := fakeQsxtSubmitAnswer(`{"hr":0}`, nil)
	defer restoreSub()
	restoreSave := fakeQsxtSaveAnswer(`{"hr":0}`, nil)
	defer restoreSave()

	taskJSON := `{"platform":"qingshuxuetang","raw":{"quizId":"quiz-1","classId":"45","schoolId":"s1","courseId":"co1"},"options":{"action":"work"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=work with missing solution should still succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["missingSolution"] != float64(1) {
		t.Fatalf("raw.missingSolution=%v, want 1", res.Raw["missingSolution"])
	}
}

func TestRunTaskQsxt_RunWork_MissingFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// no quizId/id
	taskJSON := `{"platform":"qingshuxuetang","raw":{"classId":"45","schoolId":"s1","courseId":"co1"},"options":{"action":"work"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if e.OK {
		t.Fatal("action=work without quizId should fail")
	}
}

func TestRunTaskQsxt_RunWork_QuestionsHrNonZero(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreQ := fakeQsxtPullWorkQuestions(`{"hr":1,"data":null}`, nil)
	defer restoreQ()
	taskJSON := `{"platform":"qingshuxuetang","raw":{"quizId":"quiz-1","classId":"45","schoolId":"s1","courseId":"co1"},"options":{"action":"work"}}`
	e := parseEnvelope(t, RunTask(qsxtRunSessJSON, taskJSON))
	if e.OK {
		t.Fatal("questions hr!=0 should fail")
	}
}
