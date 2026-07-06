package mobilecore

import (
	"encoding/json"
	"errors"
	"testing"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

func fakeXxtLogin(raw string, err error) func() {
	orig := xxtLoginProvider
	xxtLoginProvider = func(_ *xxtmobile.XxtClient) (string, error) { return raw, err }
	return func() { xxtLoginProvider = orig }
}

func fakeXxtChapter(raw string, err error) func() {
	orig := xxtChapterProvider
	xxtChapterProvider = func(_ *xxtmobile.XxtClient, _, _ int) (string, error) { return raw, err }
	return func() { xxtChapterProvider = orig }
}

// fakeXxtKnowledgeCards swaps the gas/knowledge cards provider. Pass an error to force
// getTasks onto the legacy attachment fallback.
func fakeXxtKnowledgeCards(raw string, err error) func() {
	orig := xxtKnowledgeCardsProvider
	xxtKnowledgeCardsProvider = func(_ *xxtmobile.XxtClient, _, _ int) (string, error) { return raw, err }
	return func() { xxtKnowledgeCardsProvider = orig }
}

// fakeXxtPointStatus swaps the myjobsnodesmap point-status provider.
func fakeXxtPointStatus(raw string, err error) func() {
	orig := xxtPointStatusProvider
	xxtPointStatusProvider = func(_ *xxtmobile.XxtClient, _ []int, _, _, _, _ int) (string, error) { return raw, err }
	return func() { xxtPointStatusProvider = orig }
}

// reuse existing fakeXxtCourseList from platform_xuexitong_test.go
// (same package, already defined)

func TestLoginXuexitong_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtLogin(`{"status":true,"msg2":"","url":"http://i.mooc.chaoxing.com"}`, nil)
	defer restore()

	e := parseEnvelope(t, Login("xuexitong", `{"account":"user","password":"pass"}`))
	if !e.OK {
		t.Fatalf("Login should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var sess SessionData
	json.Unmarshal(b, &sess)
	if sess.Platform != "xuexitong" {
		t.Fatalf("platform=%q", sess.Platform)
	}
}

func TestLoginXuexitong_Failure(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtLogin(`{"status":false,"msg2":"账号或密码错误"}`, nil)
	defer restore()

	e := parseEnvelope(t, Login("xuexitong", `{"account":"a","password":"b"}`))
	if e.OK {
		t.Fatal("failed login should return error")
	}
}

func TestLoginXuexitong_CaptchaRequired(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtLogin(`{"status":false,"msg2":"请输入验证码"}`, nil)
	defer restore()

	e := parseEnvelope(t, Login("xuexitong", `{"account":"a","password":"b"}`))
	if e.OK {
		t.Fatal("captcha case should fail")
	}
	if !contains(e.Error, "captcha") && !contains(e.Error, "验证码") {
		t.Errorf("expected captcha mention, got: %q", e.Error)
	}
}

func TestLoginXuexitong_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtLogin("", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, Login("xuexitong", `{"account":"a","password":"b"}`))
	if e.OK {
		t.Fatal("provider error should fail")
	}
}

// --- GetCourseDetail ---

const xxtFakeChapter = `{"course":{"data":[{"id":5001,"name":"课程A","knowledge":{"data":[{"id":111,"name":"第一章","parentnodeid":-1,"layer":1,"jobcount":3,"attachment":{"data":[{"id":"att1","type":"video","objectid":"obj1"}]}},{"id":222,"name":"第二章","parentnodeid":-1,"layer":1,"jobcount":2,"attachment":{"data":[]}}]}}]}}`
const xxtFakeChapterMixedAttachments = `{"course":{"data":[{"id":5001,"name":"课程A","knowledge":{"data":[{"id":111,"name":"第一章","parentnodeid":-1,"layer":1,"jobcount":3,"attachment":{"data":[{"id":"vid-att","type":"insertvideo","objectid":"obj1","extension":"mp4"},{"id":"bbs-att","type":"insertbbs","objectid":"","extension":""},{"id":"doc-att","type":"insertdoc","objectid":"doc1","extension":"pdf"}]}}]}}]}}`

const xxtCourseJSON = `{"platform":"xuexitong","id":"9001","raw":{"courseId":"9001","classId":"2001","cpi":"1001","key":"2001"}}`

func TestGetCourseDetailXuexitong_Success(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtChapter(xxtFakeChapter, nil)
	defer restore()

	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, xxtCourseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if detail.Platform != "xuexitong" || detail.ParentID != "9001" {
		t.Fatalf("unexpected: %+v", detail)
	}
	if len(detail.Items) < 2 {
		t.Fatalf("expected ≥2 items, got %d", len(detail.Items))
	}
	raw := detail.Items[0].Raw
	for _, f := range []string{"courseId", "classId", "cpi", "knowledgeId"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("chapter Raw missing %q", f)
		}
	}
}

func TestGetCourseDetailXuexitong_PreservesMixedAttachmentTypes(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtChapter(xxtFakeChapterMixedAttachments, nil)
	defer restore()
	// Force the attachment fallback so this test exercises mixed-type attachment parsing.
	defer fakeXxtKnowledgeCards("", errors.New("cards disabled in test"))()

	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, xxtCourseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if len(detail.Items) != 1 {
		t.Fatalf("expected 1 knowledge item, got %d", len(detail.Items))
	}
	atts, _ := detail.Items[0].Raw["attachments"].([]interface{})
	if len(atts) != 3 {
		t.Fatalf("expected 3 attachments, got %d: %+v", len(atts), detail.Items[0].Raw["attachments"])
	}
	courseJSONBytes, _ := json.Marshal(detail.Items[0])
	e = parseEnvelope(t, GetTasks(xxtSessJSON, string(courseJSONBytes)))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ = json.Marshal(e.Data)
	var tasks TaskListResult
	json.Unmarshal(b, &tasks)
	if len(tasks.Tasks) != 3 {
		t.Fatalf("expected 3 tasks, got %d", len(tasks.Tasks))
	}
	if tasks.Tasks[0].Type != "video" || tasks.Tasks[1].Type != "bbs" || tasks.Tasks[2].Type != "document" {
		t.Fatalf("unexpected task types: %+v", tasks.Tasks)
	}
	if tasks.Tasks[1].ID != "bbs-att" {
		t.Fatalf("bbs task should fall back to attachment id: %+v", tasks.Tasks[1])
	}
	if tasks.Tasks[0].Raw["extension"] != "mp4" {
		t.Fatalf("extension not preserved: %+v", tasks.Tasks[0].Raw)
	}
}

func TestGetCourseDetailXuexitong_ParsesWrappedDataResponse(t *testing.T) {
	resetState()
	Init("/tmp/test")
	raw := `{"data":[{"course":{"data":[{"id":5001,"name":"课程A","knowledge":{"data":[{"id":333,"name":"第三章","parentnodeid":111,"layer":2,"jobcount":1,"attachment":{"data":[{"id":"doc-att","type":"insertdoc","objectid":"doc1","extension":"pdf"}]}}]}}]}}]}`
	restore := fakeXxtChapter(raw, nil)
	defer restore()

	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, xxtCourseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if len(detail.Items) != 1 || detail.Items[0].ID != "333" {
		t.Fatalf("unexpected detail: %+v", detail)
	}
}

func TestGetCourseDetailXuexitong_RejectsHTMLResponse(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtChapter(`<!doctype html><html><head><title>用户登录</title></head><body>请先登录</body></html>`, nil)
	defer restore()

	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, xxtCourseJSON))
	if e.OK {
		t.Fatal("HTML response should fail")
	}
	if contains(e.Error, "invalid character") || !contains(e.Error, "重新登录") {
		t.Fatalf("unexpected error: %s", e.Error)
	}
}

func TestGetCourseDetailXuexitong_RejectsEmptyChapterJSON(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtChapter(`{"result":0,"msg":"not found"}`, nil)
	defer restore()

	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, xxtCourseJSON))
	if e.OK {
		t.Fatal("empty chapter JSON should fail")
	}
	if !contains(e.Error, "没拿到课程章节数据") {
		t.Fatalf("unexpected error: %s", e.Error)
	}
}

func TestGetCourseDetailXuexitong_PlatformFromSession(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtChapter(xxtFakeChapter, nil)
	defer restore()

	courseJSON := `{"id":"9001","raw":{"courseId":"9001","classId":"2001","cpi":"1001","key":"2001"}}`
	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("platform from session should work: %s", e.Error)
	}
}

func TestGetCourseDetailXuexitong_MissingCourseId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, `{"platform":"xuexitong","raw":{"classId":"2001","cpi":"1001"}}`))
	if e.OK {
		t.Fatal("missing courseId should fail")
	}
}

func TestGetCourseDetailXuexitong_ProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtChapter("", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, xxtCourseJSON))
	if e.OK {
		t.Fatal("provider error should fail")
	}
}

// --- GetTasks ---

func TestGetTasksXuexitong_FromAttachments(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtKnowledgeCards("", errors.New("cards disabled in test"))()
	// courseJSON from GetCourseDetail Item[0] — has attachments in Raw
	courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"attachments":[{"id":"att1","type":"video","objectid":"obj1"}]}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
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
		t.Fatalf("type=%q, want video", res.Tasks[0].Type)
	}
	raw := res.Tasks[0].Raw
	for _, f := range []string{"courseId", "classId", "cpi", "objectId"} {
		if _, ok := raw[f]; !ok {
			t.Errorf("task Raw missing %q", f)
		}
	}
}

func TestGetTasksXuexitong_AttachmentIDFallbackForNonVideoTasks(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtKnowledgeCards("", errors.New("cards disabled in test"))()
	courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"attachments":[{"id":"bbs-att","type":"insertbbs","objectid":""},{"id":"live-att","type":"insertlive"}]}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks should keep objectid-less attachments: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 2 {
		t.Fatalf("expected 2 tasks, got %d", len(res.Tasks))
	}
	if res.Tasks[0].ID != "bbs-att" || res.Tasks[0].Type != "bbs" {
		t.Fatalf("unexpected bbs fallback task: %+v", res.Tasks[0])
	}
	if res.Tasks[1].ID != "live-att" || res.Tasks[1].Type != "live" {
		t.Fatalf("unexpected live fallback task: %+v", res.Tasks[1])
	}
}

func TestGetTasksXuexitong_EmptyAttachments(t *testing.T) {
	resetState()
	Init("/tmp/test")
	courseJSON := `{"platform":"xuexitong","id":"222","raw":{"courseId":"9001","classId":"2001","cpi":"1001","attachments":[]}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("empty attachments should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 0 {
		t.Fatalf("expected 0 tasks, got %d", len(res.Tasks))
	}
}

func TestGetTasksXuexitong_MissingRequiredFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	e := parseEnvelope(t, GetTasks(xxtSessJSON, `{"platform":"xuexitong","id":"111","raw":{"classId":"2001"}}`))
	if e.OK {
		t.Fatal("missing courseId+cpi should fail")
	}
}

// --- GetTasks: card-based discovery (gas/knowledge → iframe task points) ---

// One card with a video iframe, one card with a work (chapter-test) iframe.
const xxtFakeKnowledgeCards = `{"data":[{"card":{"data":[{"cardorder":0,"knowledgeid":111,"description":"<iframe module=\"insertvideo\" data=\"{&quot;objectid&quot;:&quot;obj-vid&quot;,&quot;jobid&quot;:&quot;video-1&quot;,&quot;name&quot;:&quot;1.1%20intro&quot;}\"></iframe>"},{"cardorder":1,"knowledgeid":111,"description":"<iframe module=\"work\" data=\"{&quot;workid&quot;:&quot;w-1&quot;,&quot;schoolid&quot;:&quot;0&quot;,&quot;_jobid&quot;:&quot;work-1&quot;}\"></iframe>"}]}}]}`

func TestGetTasksXuexitong_FromCards(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtKnowledgeCards(xxtFakeKnowledgeCards, nil)()
	// Attachment data is present but must be ignored in favour of card discovery.
	courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"attachments":[{"id":"ignore","type":"video","objectid":"ignore"}]}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 2 {
		t.Fatalf("expected 2 card tasks, got %d: %+v", len(res.Tasks), res.Tasks)
	}
	if res.Tasks[0].Type != "video" || res.Tasks[0].ID != "obj-vid" {
		t.Fatalf("unexpected video task: %+v", res.Tasks[0])
	}
	if res.Tasks[0].Raw["jobId"] != "video-1" {
		t.Fatalf("video jobId not carried: %+v", res.Tasks[0].Raw)
	}
	if res.Tasks[0].Name != "1.1 intro" {
		t.Fatalf("video title not URL-decoded: %+v", res.Tasks[0])
	}
	if res.Tasks[1].Type != "quiz" || res.Tasks[1].ID != "work-1" {
		t.Fatalf("unexpected work/quiz task: %+v", res.Tasks[1])
	}
	if res.Tasks[1].Raw["workId"] != "w-1" || res.Tasks[1].Raw["schoolId"] != "0" {
		t.Fatalf("work ids not carried: %+v", res.Tasks[1].Raw)
	}
}

func TestGetTasksXuexitong_CardErrorFallsBackToAttachments(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtKnowledgeCards("", errors.New("network")) ()
	courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"attachments":[{"id":"att1","type":"video","objectid":"obj1"}]}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 1 || res.Tasks[0].ID != "obj1" {
		t.Fatalf("expected attachment fallback task, got %+v", res.Tasks)
	}
}

func TestGetTasksXuexitong_EmptyCardsYieldsNoTasks(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Cards fetch succeeds but the node is a container with no points: must NOT fall
	// back to attachments (a genuine 0-task node stays 0-task).
	defer fakeXxtKnowledgeCards(`{"data":[{"card":{"data":[]}}]}`, nil)()
	courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"attachments":[{"id":"att1","type":"video","objectid":"obj1"}]}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 0 {
		t.Fatalf("empty cards should yield 0 tasks (no attachment fallback), got %+v", res.Tasks)
	}
}

// --- point-status pre-filter (job/myjobsnodesmap) ---

func TestGetCourseDetailXuexitong_AnnotatesPointStatus(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtChapter(xxtFakeChapter, nil)()
	// nodes 111 (complete 3/3) and 222 (partial 1/2).
	defer fakeXxtPointStatus(`{"111":{"totalcount":3,"finishcount":3,"unfinishcount":0},"222":{"totalcount":2,"finishcount":1,"unfinishcount":1}}`, nil)()
	// Numeric userId so the status fetch actually runs.
	courseJSON := `{"platform":"xuexitong","id":"9001","raw":{"courseId":"9001","classId":"2001","cpi":"1001","key":"2001","userId":"7001"}}`
	e := parseEnvelope(t, GetCourseDetail(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetCourseDetail failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var detail CourseDetailResult
	json.Unmarshal(b, &detail)
	if len(detail.Items) != 2 {
		t.Fatalf("expected 2 items, got %d", len(detail.Items))
	}
	if detail.Items[0].Raw["pointTotal"] != float64(3) || detail.Items[0].Raw["pointFinished"] != float64(3) {
		t.Fatalf("node 111 status not annotated: %+v", detail.Items[0].Raw)
	}
	if detail.Items[1].Raw["pointTotal"] != float64(2) || detail.Items[1].Raw["pointFinished"] != float64(1) {
		t.Fatalf("node 222 status not annotated: %+v", detail.Items[1].Raw)
	}
}

func TestGetTasksXuexitong_SkipsCompletedNode(t *testing.T) {
	resetState()
	Init("/tmp/test")
	// Cards would yield 2 tasks if fetched — proves the completed node skips the fetch.
	defer fakeXxtKnowledgeCards(xxtFakeKnowledgeCards, nil)()
	courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"pointTotal":3,"pointFinished":3}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 0 {
		t.Fatalf("completed node should yield 0 tasks without fetching cards, got %+v", res.Tasks)
	}
}

func TestGetTasksXuexitong_IncompleteNodeFetchesCards(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtKnowledgeCards(xxtFakeKnowledgeCards, nil)()
	// 1 of 3 done → not complete → cards are fetched (2 tasks).
	courseJSON := `{"platform":"xuexitong","id":"111","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"pointTotal":3,"pointFinished":1}}`
	e := parseEnvelope(t, GetTasks(xxtSessJSON, courseJSON))
	if !e.OK {
		t.Fatalf("GetTasks failed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res TaskListResult
	json.Unmarshal(b, &res)
	if len(res.Tasks) != 2 {
		t.Fatalf("incomplete node should fetch cards (2 tasks), got %d: %+v", len(res.Tasks), res.Tasks)
	}
}

// helper
func contains(s, sub string) bool {
	return len(sub) > 0 && len(s) >= len(sub) && (s == sub || len(s) > 0 && containsStr(s, sub))
}

func containsStr(s, sub string) bool {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
