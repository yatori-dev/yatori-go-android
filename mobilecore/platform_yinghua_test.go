package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	yhmobile "github.com/yatori-dev/yatori-go-mobile-core/api/yinghua/mobile"
)

const yhSessJSON = `{"platform":"yinghua","account":"test@test.com","token":"tok-abc","extra":{"sign":"sig-xyz","preUrl":"https://yh.example.com"}}`
const yhCourseJSON = `{"platform":"yinghua","id":"1001","raw":{"courseId":"1001","preUrl":"https://yh.example.com"}}`

const yhCourseListRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":1001,"name":"移动端测试课程","mode":0,"progress":1.0,"startDate":"2024-01-01","endDate":"2024-12-31","videoCount":5,"videoLearned":5}]}}`
const yhCourseDetailRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"data":{"id":1001,"name":"移动端测试课程","mode":0,"progress":1.0,"startDate":"2024-01-01","endDate":"2024-12-31","videoCount":5,"videoLearned":5}}}`
const yhChapterRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":100,"name":"第1章","nodeList":[{"id":200,"name":"第1节","videoDuration":"1200","nodeLock":0,"tabVideo":true,"tabFile":false,"tabVote":false,"tabExam":false,"tabWork":false,"videoState":2,"duration":"20分钟","index":"1.1"}]}]}}`
const yhVideoRecordRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":200,"name":"第1节","courseId":1001,"videoDuration":1200,"bid":"bid-1","duration":1200,"progress":100,"state":2,"viewCount":2,"viewedDuration":1200,"error":0,"errorMessage":""}],"pageInfo":{"page":1,"pageCount":1}}}`
const yhPCVideoRecordRaw = `{"list":[{"id":"200","error":0,"errorMessage":""}],"pageInfo":{"page":1,"pageCount":1}}`
const yhVideoStateRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"studyId":"study-001","videoDuration":1200}}`
const yhVideoStateNoStudyIDRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"videoDuration":1200}}`
const yhSubmitOKRaw = `{"_code":0,"status":true,"msg":"提交成功","result":{"data":{"studyId":12345}}}`

func fakeYhCaptcha(imgB64, cookieStr string, err error) func() {
	orig := yhCaptchaProvider
	yhCaptchaProvider = func(_ string) (string, string, error) { return imgB64, cookieStr, err }
	return func() { yhCaptchaProvider = orig }
}

func fakeYhLogin(token, sign string, err error) func() {
	orig := yhLoginProvider
	yhLoginProvider = func(_, _, _, _, _ string) (string, string, error) { return token, sign, err }
	return func() { yhLoginProvider = orig }
}

func fakeYhCourseList(raw string, err error) func() {
	orig := yhCourseListProvider
	yhCourseListProvider = func(_ *yhmobile.YingHuaClient) (string, error) { return raw, err }
	return func() { yhCourseListProvider = orig }
}

func fakeYhCourseDetail(raw string, err error) func() {
	orig := yhCourseDetailProvider
	yhCourseDetailProvider = func(_ *yhmobile.YingHuaClient, _ string) (string, error) { return raw, err }
	return func() { yhCourseDetailProvider = orig }
}

func fakeYhChapter(raw string, err error) func() {
	orig := yhCourseChapterProvider
	yhCourseChapterProvider = func(_ *yhmobile.YingHuaClient, _ string) (string, error) { return raw, err }
	return func() { yhCourseChapterProvider = orig }
}

func fakeYhVideoRecords(raw string, err error) func() {
	orig := yhVideoRecordProvider
	yhVideoRecordProvider = func(_ *yhmobile.YingHuaClient, _ string, _ int) (string, error) { return raw, err }
	return func() { yhVideoRecordProvider = orig }
}

func fakeYhPCVideoRecords(raw string, err error) func() {
	orig := yhPCVideoRecordProvider
	yhPCVideoRecordProvider = func(_ *yhmobile.YingHuaClient, _ string, _ int) (string, error) { return raw, err }
	return func() { yhPCVideoRecordProvider = orig }
}

func fakeYhVideoState(raw string, err error) func() {
	orig := yhVideoStudyStateProvider
	yhVideoStudyStateProvider = func(_ *yhmobile.YingHuaClient, _ string) (string, error) { return raw, err }
	return func() { yhVideoStudyStateProvider = orig }
}

func fakeYhSubmit(raw string, err error) func() {
	orig := yhSubmitStudyTimeProvider
	yhSubmitStudyTimeProvider = func(_ *yhmobile.YingHuaClient, _, _ string, _ int) (string, error) { return raw, err }
	return func() { yhSubmitStudyTimeProvider = orig }
}

func fakeYhKeepAlive(raw string, err error) func() {
	orig := yhKeepAliveProvider
	yhKeepAliveProvider = func(_ *yhmobile.YingHuaClient) (string, error) { return raw, err }
	return func() { yhKeepAliveProvider = orig }
}

func fakeYhWorkDetail(raw string, err error) func() {
	orig := yhWorkDetailProvider
	yhWorkDetailProvider = func(_ *yhmobile.YingHuaClient, _ string) (string, error) { return raw, err }
	return func() { yhWorkDetailProvider = orig }
}

func fakeYhExamDetail(raw string, err error) func() {
	orig := yhExamDetailProvider
	yhExamDetailProvider = func(_ *yhmobile.YingHuaClient, _ string) (string, error) { return raw, err }
	return func() { yhExamDetailProvider = orig }
}

func fakeYhStartWork(raw string, err error) func() {
	orig := yhStartWorkProvider
	yhStartWorkProvider = func(_ *yhmobile.YingHuaClient, _, _, _ string) (string, error) { return raw, err }
	return func() { yhStartWorkProvider = orig }
}

func fakeYhGetWork(raw string, err error) func() {
	orig := yhGetWorkProvider
	yhGetWorkProvider = func(_ *yhmobile.YingHuaClient, _, _ string) (string, error) { return raw, err }
	return func() { yhGetWorkProvider = orig }
}

func fakeYhStartExam(raw string, err error) func() {
	orig := yhStartExamProvider
	yhStartExamProvider = func(_ *yhmobile.YingHuaClient, _, _, _ string) (string, error) { return raw, err }
	return func() { yhStartExamProvider = orig }
}

func fakeYhGetExamTopic(raw string, err error) func() {
	orig := yhGetExamTopicProvider
	yhGetExamTopicProvider = func(_ *yhmobile.YingHuaClient, _, _ string) (string, error) { return raw, err }
	return func() { yhGetExamTopicProvider = orig }
}

// --- StartLogin ---

func TestStartLoginYinghua_MissingURL(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, StartLogin("yinghua", `{"account":"u","password":"p"}`))
	if e.OK {
		t.Fatal("missing URL should fail")
	}
}

func TestStartLoginYinghua_CaptchaError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCaptcha("", "", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, StartLogin("yinghua", `{"account":"u","password":"p","url":"https://yh.example.com"}`))
	if e.OK {
		t.Fatal("captcha error should fail")
	}
}

func TestStartLoginYinghua_Challenge(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCaptcha("aW1nYjY0", "session=abc", nil)
	defer restore()
	e := parseEnvelope(t, StartLogin("yinghua", `{"account":"u","password":"p","url":"https://yh.example.com"}`))
	if !e.OK {
		t.Fatalf("challenge should succeed: %s", e.Error)
	}
}

// --- ContinueLogin ---

func TestContinueLoginYinghua_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCaptcha("aW1n", "sess=x", nil)
	e := parseEnvelope(t, StartLogin("yinghua", `{"account":"user","password":"pass","url":"https://yh.example.com"}`))
	restore()
	if !e.OK {
		t.Fatalf("StartLogin: %s", e.Error)
	}
	taskID := e.Data.(map[string]interface{})["taskId"].(string)

	restoreLogin := fakeYhLogin("tok-xyz", "sig-abc", nil)
	defer restoreLogin()
	e2 := parseEnvelope(t, ContinueLogin(taskID, `{"type":"image_ocr","text":"4321"}`))
	if !e2.OK {
		t.Fatalf("ContinueLogin: %s", e2.Error)
	}
}

func TestContinueLoginYinghua_WrongCode(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCaptcha("aW1n", "sess=x", nil)
	e := parseEnvelope(t, StartLogin("yinghua", `{"account":"user","password":"pass","url":"https://yh.example.com"}`))
	restore()
	if !e.OK {
		t.Fatalf("StartLogin: %s", e.Error)
	}
	taskID := e.Data.(map[string]interface{})["taskId"].(string)

	restoreLogin := fakeYhLogin("", "", errors.New("yinghua: login failed: 验证码有误！"))
	defer restoreLogin()
	e2 := parseEnvelope(t, ContinueLogin(taskID, `{"type":"image_ocr","text":"0000"}`))
	if e2.OK {
		t.Fatal("wrong code should fail")
	}
}

// --- GetCourses ---

func TestGetCoursesYinghua_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCourseList(yhCourseListRaw, nil)
	defer restore()
	e := parseEnvelope(t, GetCourses(yhSessJSON))
	if !e.OK {
		t.Fatalf("GetCourses: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res CourseListResult
	json.Unmarshal(b, &res)
	if len(res.Courses) != 1 {
		t.Fatalf("expected 1 course, got %d", len(res.Courses))
	}
	if res.Courses[0].Progress != 100 {
		t.Fatalf("progress=%v, want 100", res.Courses[0].Progress)
	}
}

func TestGetCoursesYinghua_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCourseList("", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, GetCourses(yhSessJSON))
	if e.OK {
		t.Fatal("provider error should fail")
	}
}

// --- GetCourseDetail ---

func TestGetCourseDetailYinghua_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCourseDetail(yhCourseDetailRaw, nil)
	defer restore()
	e := parseEnvelope(t, GetCourseDetail(yhSessJSON, yhCourseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if len(detail.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(detail.Items))
	}
	if detail.Items[0].Progress != 100 {
		t.Fatalf("detail progress=%v, want 100", detail.Items[0].Progress)
	}
}

func TestGetCourseDetailYinghua_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhCourseDetail("", errors.New("detail error"))
	defer restore()
	e := parseEnvelope(t, GetCourseDetail(yhSessJSON, yhCourseJSON))
	if e.OK {
		t.Fatal("detail error should fail")
	}
}

func TestGetCourseDetailYinghua_MissingID(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(yhSessJSON, `{"platform":"yinghua"}`))
	if e.OK {
		t.Fatal("missing id should fail")
	}
}

// --- GetTasks ---

func TestGetTasksYinghua_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhChapter(yhChapterRaw, nil)
	defer restore()
	restoreRecords := fakeYhVideoRecords(yhVideoRecordRaw, nil)
	defer restoreRecords()
	restorePC := fakeYhPCVideoRecords(yhPCVideoRecordRaw, nil)
	defer restorePC()
	e := parseEnvelope(t, GetTasks(yhSessJSON, yhCourseJSON))
	if !e.OK {
		t.Fatalf("GetTasks: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 1 {
		t.Fatalf("expected 1 task, got %d", len(res.Tasks))
	}
	task := res.Tasks[0]
	if task.Status != "completed" || task.Progress != 100 {
		t.Fatalf("task status/progress = %s/%v, want completed/100", task.Status, task.Progress)
	}
	if task.Raw["viewedDuration"] != float64(1200) {
		t.Fatalf("viewedDuration=%v, want 1200", task.Raw["viewedDuration"])
	}
}

func TestGetTasksYinghua_VideoRecordExpiryPropagates(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhChapter(yhChapterRaw, nil)
	defer restore()
	restoreRecords := fakeYhVideoRecords(`{"_code":1,"status":false,"msg":"账号登录超时，请重新登录","result":{}}`, nil)
	defer restoreRecords()

	e := parseEnvelope(t, GetTasks(yhSessJSON, yhCourseJSON))
	if e.OK {
		t.Fatal("video-record authentication expiry should fail the task pull")
	}
	if !strings.Contains(e.Error, "账号登录超时，请重新登录") {
		t.Fatalf("expiry message was lost: %s", e.Error)
	}
}

func TestGetTasksYinghua_PCRecordExpiryPropagates(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhChapter(yhChapterRaw, nil)
	defer restore()
	restoreRecords := fakeYhVideoRecords(yhVideoRecordRaw, nil)
	defer restoreRecords()
	restorePC := fakeYhPCVideoRecords(`{"_code":1,"status":false,"msg":"账号登录超时，请重新登录"}`, nil)
	defer restorePC()

	e := parseEnvelope(t, GetTasks(yhSessJSON, yhCourseJSON))
	if e.OK || !strings.Contains(e.Error, "账号登录超时，请重新登录") {
		t.Fatalf("pc-record expiry must propagate, ok=%v error=%s", e.OK, e.Error)
	}
}

func TestGetTasksYinghua_OrdinaryPCRecordFailureIsBestEffort(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhChapter(yhChapterRaw, nil)
	defer restore()
	restoreRecords := fakeYhVideoRecords(yhVideoRecordRaw, nil)
	defer restoreRecords()
	restorePC := fakeYhPCVideoRecords("", errors.New("pc endpoint unavailable"))
	defer restorePC()

	e := parseEnvelope(t, GetTasks(yhSessJSON, yhCourseJSON))
	if !e.OK {
		t.Fatalf("optional PC record failure must not block normal videos: %s", e.Error)
	}
}

func TestGetTasksYinghua_MissingCourseID(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetTasks(yhSessJSON, `{"platform":"yinghua"}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestGetTasksYinghua_ChapterError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhChapter("", errors.New("chapter error"))
	defer restore()
	e := parseEnvelope(t, GetTasks(yhSessJSON, yhCourseJSON))
	if e.OK {
		t.Fatal("chapter error should fail")
	}
}

// --- RunTask ---

const yhTaskJSON = `{"platform":"yinghua","id":"200","raw":{"nodeId":"200","courseId":"1001","studyId":"study-001","videoDuration":"1200","preUrl":"https://yh.example.com"}}`

func TestRunTaskYinghua_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"yinghua","id":"200","raw":{"courseId":"1001"},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("dry run: %s", e.Error)
	}
}

func TestRunTaskYinghua_SubmitSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhSubmit(yhSubmitOKRaw, nil)
	defer restore()
	e := parseEnvelope(t, RunTask(yhSessJSON, yhTaskJSON))
	if !e.OK {
		t.Fatalf("submit: %s", e.Error)
	}
}

func TestRunTaskYinghua_FetchStudyId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// No studyId in raw → must fetch via VideoStudyState
	taskJSON := `{"platform":"yinghua","id":"200","raw":{"courseId":"1001","preUrl":"https://yh.example.com"}}`
	restoreState := fakeYhVideoState(yhVideoStateRaw, nil)
	defer restoreState()
	restoreSubmit := fakeYhSubmit(yhSubmitOKRaw, nil)
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("fetch studyId path: %s", e.Error)
	}
}

func TestRunTaskYinghua_SubmitWithZeroStudyIDFallback(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var capturedStudyID string
	restoreState := fakeYhVideoState(yhVideoStateNoStudyIDRaw, nil)
	defer restoreState()
	orig := yhSubmitStudyTimeProvider
	yhSubmitStudyTimeProvider = func(_ *yhmobile.YingHuaClient, _, studyID string, _ int) (string, error) {
		capturedStudyID = studyID
		return yhSubmitOKRaw, nil
	}
	defer func() { yhSubmitStudyTimeProvider = orig }()

	taskJSON := `{"platform":"yinghua","id":"200","raw":{"courseId":"1001","preUrl":"https://yh.example.com","videoDuration":"1200"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("zero studyId fallback should succeed: %s", e.Error)
	}
	if capturedStudyID != "0" {
		t.Fatalf("captured studyId=%q, want 0", capturedStudyID)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["studyId"] != "12345" {
		t.Fatalf("returned studyId=%v, want 12345", res.Raw["studyId"])
	}
}

func TestRunTaskYinghua_MissingNodeID(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(yhSessJSON, `{"platform":"yinghua","raw":{"courseId":"1001"}}`))
	if e.OK {
		t.Fatal("missing nodeId should fail")
	}
}

func TestRunTaskYinghua_MissingCourseID(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(yhSessJSON, `{"platform":"yinghua","id":"200"}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestRunTaskYinghua_SubmitFailed(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhSubmit(`{"_code":1,"status":false,"msg":"提交失败"}`, nil)
	defer restore()
	e := parseEnvelope(t, RunTask(yhSessJSON, yhTaskJSON))
	if e.OK {
		t.Fatal("submit failure should fail")
	}
}

// --- host-driven action primitives ---

func TestRunTaskYinghua_ActionState(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreState := fakeYhVideoState(yhVideoStateRaw, nil)
	defer restoreState()
	// Submit must NOT be called on action=state; error provider proves it.
	restoreSubmit := fakeYhSubmit("", errors.New("submit must not be called"))
	defer restoreSubmit()

	taskJSON := `{"platform":"yinghua","id":"200","raw":{"nodeId":"200","courseId":"1001","preUrl":"https://yh.example.com"},"options":{"action":"state"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("action=state should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "started" {
		t.Fatalf("expected started, got %q", res.Status)
	}
	if res.Raw["studyId"] != "study-001" {
		t.Fatalf("raw.studyId=%v, want study-001", res.Raw["studyId"])
	}
	if res.Raw["videoDuration"] != float64(1200) {
		t.Fatalf("raw.videoDuration=%v, want 1200", res.Raw["videoDuration"])
	}
}

func TestRunTaskYinghua_HostStudyTime(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var capturedStudyTime int
	orig := yhSubmitStudyTimeProvider
	yhSubmitStudyTimeProvider = func(_ *yhmobile.YingHuaClient, _, _ string, studyTime int) (string, error) {
		capturedStudyTime = studyTime
		return yhSubmitOKRaw, nil
	}
	defer func() { yhSubmitStudyTimeProvider = orig }()

	// Host supplies a cumulative studyTime (5s-accumulating loop), not the full duration.
	taskJSON := `{"platform":"yinghua","id":"200","raw":{"nodeId":"200","courseId":"1001","studyId":"study-001","videoDuration":"1200"},"options":{"studyTime":45}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("host studyTime submit should succeed: %s", e.Error)
	}
	if capturedStudyTime != 45 {
		t.Fatalf("submit got studyTime=%d, want host-supplied 45", capturedStudyTime)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["message"] != "提交成功" {
		t.Fatalf("raw.message=%v, want 提交成功 (红标 passthrough)", res.Raw["message"])
	}
}

func TestRunTaskYinghua_KeepAliveAlive(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhKeepAlive(`{"status":true,"_code":0}`, nil)
	defer restore()

	// No nodeId/courseId required for session-level keepAlive.
	taskJSON := `{"platform":"yinghua","options":{"action":"keepAlive"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("keepAlive should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["alive"] != true {
		t.Fatalf("raw.alive=%v, want true", res.Raw["alive"])
	}
	if res.Raw["expired"] != false {
		t.Fatalf("raw.expired=%v, want false", res.Raw["expired"])
	}
}

func TestRunTaskYinghua_KeepAliveExpired(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhKeepAlive(`{"status":false,"_code":500}`, nil)
	defer restore()

	taskJSON := `{"platform":"yinghua","options":{"action":"keepAlive"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("keepAlive call itself should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["alive"] != false {
		t.Fatalf("raw.alive=%v, want false", res.Raw["alive"])
	}
	if res.Raw["expired"] != true {
		t.Fatalf("raw.expired=%v, want true (host should re-login)", res.Raw["expired"])
	}
}

func TestRunTaskYinghua_KeepAliveProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhKeepAlive("", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, RunTask(yhSessJSON, `{"platform":"yinghua","options":{"action":"keepAlive"}}`))
	if e.OK {
		t.Fatal("keepAlive provider error should fail")
	}
}

// --- exam/work action primitives ---

const yhWorkListRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":11,"title":"作业1","score":100,"courseId":1001,"nodeId":200,"url":"/work?workId=W7&token=abc","allow":"1","frequency":"3"}]}}`
const yhExamListRaw = `{"_code":0,"status":true,"msg":"获取数据成功","result":{"list":[{"id":22,"title":"考试1","score":100,"limitedTime":60,"courseId":1001,"nodeId":201,"url":"/exam?examId=E9&token=abc"}]}}`
const yhStartOKRaw = `{"_code":0,"status":true,"msg":"ok","result":{}}`
const yhStartRefusedRaw = `{"_code":9,"status":false,"msg":"您已完成作业，该作业仅可答题1次","result":{}}`
const yhWorkHTML = `<ul class="nav"><li><a data-id="ans-100" href="#" class="nav" id="t1" data-index="0" onclick="go()">1</a></li></ul>` +
	`<form method="post" action="/api/work/submit"><span class="num">1.</span><span class="tag">单选</span><span class="txt">本题5分</span>` +
	`<div class="content" style="x"><p>新中国成立时间</p></div><ul>` +
	`<li><label><input type="radio" value="1" checked="checked" class="opt" name="q1"><span class="num">A</span><span class="txt">1949年10月1日</span></label></li>` +
	`<li><label><input type="radio" value="2" checked="checked" class="opt" name="q1"><span class="num">B</span><span class="txt">1950年</span></label></li>` +
	`</ul></form>`

func TestRunTaskYinghua_PullWork(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhWorkDetail(yhWorkListRaw, nil)
	defer restore()
	taskJSON := `{"platform":"yinghua","id":"200","raw":{"nodeId":"200"},"options":{"action":"pullWork"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullWork should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	works, ok := res.Raw["works"].([]interface{})
	if !ok || len(works) != 1 {
		t.Fatalf("raw.works=%v", res.Raw["works"])
	}
	if works[0].(map[string]interface{})["workId"] != "W7" {
		t.Fatalf("workId=%v, want W7", works[0])
	}
}

func TestRunTaskYinghua_PullExam(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeYhExamDetail(yhExamListRaw, nil)
	defer restore()
	taskJSON := `{"platform":"yinghua","id":"201","raw":{"nodeId":"201"},"options":{"action":"pullExam"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullExam should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	exams, ok := res.Raw["exams"].([]interface{})
	if !ok || len(exams) != 1 {
		t.Fatalf("raw.exams=%v", res.Raw["exams"])
	}
	if exams[0].(map[string]interface{})["examId"] != "E9" {
		t.Fatalf("examId=%v, want E9", exams[0])
	}
}

func TestRunTaskYinghua_WorkQuestions(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeYhStartWork(yhStartOKRaw, nil)
	defer restoreStart()
	restoreGet := fakeYhGetWork(yhWorkHTML, nil)
	defer restoreGet()
	taskJSON := `{"platform":"yinghua","id":"W7","raw":{"workId":"W7","courseId":"1001","nodeId":"200"},"options":{"action":"workQuestions"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("workQuestions should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	qs, ok := res.Raw["questions"].([]interface{})
	if !ok || len(qs) != 1 {
		t.Fatalf("raw.questions=%v", res.Raw["questions"])
	}
	q := qs[0].(map[string]interface{})
	if q["answerId"] != "ans-100" || q["type"] != "单选题" {
		t.Fatalf("question=%v", q)
	}
}

func TestRunTaskYinghua_WorkQuestions_StartRefused(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreStart := fakeYhStartWork(yhStartRefusedRaw, nil)
	defer restoreStart()
	// GetWork must not matter; start refusal aborts first.
	restoreGet := fakeYhGetWork("", errors.New("should not be called"))
	defer restoreGet()
	taskJSON := `{"platform":"yinghua","id":"W7","raw":{"workId":"W7","courseId":"1001","nodeId":"200"},"options":{"action":"workQuestions"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if e.OK {
		t.Fatal("start refusal (_code 9) should fail")
	}
}

func TestRunTaskYinghua_WorkSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var finishes []string
	orig := yhSubmitWorkProvider
	yhSubmitWorkProvider = func(_ *yhmobile.YingHuaClient, _, _, _ string, _ []string, finish string) (string, error) {
		finishes = append(finishes, finish)
		return `{"_code":0,"status":true,"msg":"答题保存成功"}`, nil
	}
	defer func() { yhSubmitWorkProvider = orig }()

	taskJSON := `{"platform":"yinghua","id":"W7","options":{"action":"work","answers":[{"answerId":"a1","type":"单选题","options":["北京","上海"],"answers":["上海"]}]}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("work submit should succeed: %s", e.Error)
	}
	// one save (finish=0) + one finalize (finish=1)
	if len(finishes) != 2 || finishes[0] != "0" || finishes[1] != "1" {
		t.Fatalf("finish sequence=%v, want [0 1]", finishes)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["finalized"] != true {
		t.Fatalf("status=%q finalized=%v", res.Status, res.Raw["finalized"])
	}
}

func TestRunTaskYinghua_WorkSubmit_MissingAnswers(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, RunTask(yhSessJSON, `{"platform":"yinghua","id":"W7","options":{"action":"work"}}`))
	if e.OK {
		t.Fatal("missing options.answers should fail")
	}
}

func TestRunTaskYinghua_ExamDryRunDefault(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Submit provider errors to prove it is NOT called when realSubmit is absent.
	orig := yhSubmitExamProvider
	yhSubmitExamProvider = func(_ *yhmobile.YingHuaClient, _, _, _ string, _ []string, _ string) (string, error) {
		return "", errors.New("exam submit must not be called by default")
	}
	defer func() { yhSubmitExamProvider = orig }()

	taskJSON := `{"platform":"yinghua","id":"E9","options":{"action":"exam","answers":[{"answerId":"a1","type":"单选题","options":["甲","乙"],"answers":["甲"]}]}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam dry-run should succeed without submitting: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" {
		t.Fatalf("status=%q, want dry_run (safety default)", res.Status)
	}
	if res.Raw["realSubmit"] != false {
		t.Fatalf("raw.realSubmit=%v, want false", res.Raw["realSubmit"])
	}
}

func TestRunTaskYinghua_ExamRealSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var finishes []string
	orig := yhSubmitExamProvider
	yhSubmitExamProvider = func(_ *yhmobile.YingHuaClient, _, _, _ string, _ []string, finish string) (string, error) {
		finishes = append(finishes, finish)
		return `{"_code":0,"status":true,"msg":"答题保存成功"}`, nil
	}
	defer func() { yhSubmitExamProvider = orig }()

	taskJSON := `{"platform":"yinghua","id":"E9","options":{"action":"exam","realSubmit":true,"answers":[{"answerId":"a1","type":"单选题","options":["甲","乙"],"answers":["甲"]}]}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam realSubmit should succeed: %s", e.Error)
	}
	if len(finishes) != 2 || finishes[0] != "0" || finishes[1] != "1" {
		t.Fatalf("finish sequence=%v, want [0 1]", finishes)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["realSubmit"] != true {
		t.Fatalf("status=%q realSubmit=%v", res.Status, res.Raw["realSubmit"])
	}
}
func TestRunTaskYinghua_WorkSaveOnly(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var finishes []string
	orig := yhSubmitWorkProvider
	yhSubmitWorkProvider = func(_ *yhmobile.YingHuaClient, _, _, _ string, _ []string, finish string) (string, error) {
		finishes = append(finishes, finish)
		return `{"_code":0,"status":true,"msg":"答题保存成功"}`, nil
	}
	defer func() { yhSubmitWorkProvider = orig }()

	taskJSON := `{"platform":"yinghua","id":"W7","options":{"action":"work","finalize":false,"answers":[{"answerId":"a1","type":"单选题","options":["甲","乙"],"answers":["乙"]}]}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("work save-only should succeed: %s", e.Error)
	}
	if len(finishes) != 1 || finishes[0] != "0" {
		t.Fatalf("finish sequence=%v, want [0]", finishes)
	}
}

func TestRunTaskYinghua_ExamSaveOnly(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var finishes []string
	orig := yhSubmitExamProvider
	yhSubmitExamProvider = func(_ *yhmobile.YingHuaClient, _, _, _ string, _ []string, finish string) (string, error) {
		finishes = append(finishes, finish)
		return `{"_code":0,"status":true,"msg":"答题保存成功"}`, nil
	}
	defer func() { yhSubmitExamProvider = orig }()

	taskJSON := `{"platform":"yinghua","id":"E9","options":{"action":"exam","realSubmit":true,"finalize":false,"answers":[{"answerId":"a1","type":"单选题","options":["甲","乙"],"answers":["乙"]}]}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam save-only should succeed: %s", e.Error)
	}
	if len(finishes) != 1 || finishes[0] != "0" {
		t.Fatalf("finish sequence=%v, want [0]", finishes)
	}
}
func TestRunTaskYinghua_ExamFinalScore(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := yhExamFinalDetailProvider
	yhExamFinalDetailProvider = func(_ *yhmobile.YingHuaClient, nodeID, examID string) (string, error) {
		if nodeID != "201" || examID != "E9" {
			t.Fatalf("nodeID=%q examID=%q", nodeID, examID)
		}
		return "<div>最终得分： 92 分</div>", nil
	}
	defer func() { yhExamFinalDetailProvider = orig }()

	taskJSON := `{"platform":"yinghua","id":"E9","raw":{"examId":"E9","nodeId":"201"},"options":{"action":"examScore"}}`
	e := parseEnvelope(t, RunTask(yhSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam score should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Raw["score"] != "92" {
		t.Fatalf("score=%v, want 92", res.Raw["score"])
	}
}
