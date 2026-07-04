package mobilecore

import (
	"encoding/json"
	"errors"
	"testing"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

func fakeXxtCard(raw string, err error) func() {
	orig := xxtCardProvider
	xxtCardProvider = func(_ *xxtmobile.XxtClient, _, _, _, _ string) (string, error) { return raw, err }
	return func() { xxtCardProvider = orig }
}

func fakeXxtVideoDto(raw string, err error) func() {
	orig := xxtVideoDtoProvider
	xxtVideoDtoProvider = func(_ *xxtmobile.XxtClient, _ string, _ int) (string, error) { return raw, err }
	return func() { xxtVideoDtoProvider = orig }
}

func fakeXxtSubmit(raw string, err error) func() {
	orig := xxtSubmitStudyProvider
	xxtSubmitStudyProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _, _ string, _, _ int) (string, error) {
		return raw, err
	}
	return func() { xxtSubmitStudyProvider = orig }
}

func fakeXxtAudioSubmit(raw string, err error) func() {
	orig := xxtAudioSubmitProvider
	xxtAudioSubmitProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _ string, _, _, _ int) (string, error) {
		return raw, err
	}
	return func() { xxtAudioSubmitProvider = orig }
}

func fakeXxtHyperlinkSubmit(raw string, err error) func() {
	orig := xxtHyperlinkCompleteProvider
	xxtHyperlinkCompleteProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtHyperlinkCompleteProvider = orig }
}

func fakeXxtDocumentSubmit(raw string, err error) func() {
	orig := xxtDocumentCompleteProvider
	xxtDocumentCompleteProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtDocumentCompleteProvider = orig }
}

func fakeXxtLiveInfo(raw string, err error) func() {
	orig := xxtLiveInfoProvider
	xxtLiveInfoProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtLiveInfoProvider = orig }
}

func fakeXxtLiveRelation(raw string, err error) func() {
	orig := xxtLiveRelationProvider
	xxtLiveRelationProvider = func(_ *xxtmobile.XxtClient, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtLiveRelationProvider = orig }
}

func fakeXxtLiveSave(raw string, err error) func() {
	orig := xxtLiveSaveTimeProvider
	xxtLiveSaveTimeProvider = func(_ *xxtmobile.XxtClient, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtLiveSaveTimeProvider = orig }
}

func fakeXxtBbsInfo(raw string, err error) func() {
	orig := xxtBbsInfoProvider
	xxtBbsInfoProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtBbsInfoProvider = orig }
}

func fakeXxtBbsDetail(raw string, err error) func() {
	orig := xxtBbsDetailProvider
	xxtBbsDetailProvider = func(_ *xxtmobile.XxtClient, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtBbsDetailProvider = orig }
}

func fakeXxtBbsAnswer(raw string, err error) func() {
	orig := xxtBbsAnswerProvider
	xxtBbsAnswerProvider = func(_ *xxtmobile.XxtClient, _, _, _ string) (string, error) {
		return raw, err
	}
	return func() { xxtBbsAnswerProvider = orig }
}

func fakeXxtMonitor(raw string, err error) func() {
	orig := xxtMonitorProvider
	xxtMonitorProvider = func(_ *xxtmobile.XxtClient) (string, error) { return raw, err }
	return func() { xxtMonitorProvider = orig }
}

func fakeXxtBbsWeb(utEnc, id1, id2, info, answer string, err error) func() {
	origUt := xxtBbsUtEncProvider
	origCircle := xxtBbsCircleProvider
	origInfo := xxtBbsWebInfoProvider
	origAnswer := xxtBbsWebAnswerProvider
	xxtBbsUtEncProvider = func(_ *xxtmobile.XxtClient, _, _, _, _ string) (string, error) { return utEnc, err }
	xxtBbsCircleProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _ string, _ bool) (string, string, error) {
		return id1, id2, err
	}
	xxtBbsWebInfoProvider = func(_ *xxtmobile.XxtClient, _, _, _, _ string) (string, error) { return info, err }
	xxtBbsWebAnswerProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _ string) (string, error) { return answer, err }
	return func() {
		xxtBbsUtEncProvider = origUt
		xxtBbsCircleProvider = origCircle
		xxtBbsWebInfoProvider = origInfo
		xxtBbsWebAnswerProvider = origAnswer
	}
}

const xxtRunTaskJSON = `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111}}`

const xxtCardHTML = `<script>window.attachmentSetting = {"attachments":[{"jobid":"job-123","objectid":"obj-1","otherInfo":"encoded-other-info-more-than-80-chars-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}]};</script>`
const xxtHyperlinkCardHTML = `<script>window.attachmentSetting = {"attachments":[{"jobid":"link-job","jtoken":"jt-1","property":{"title":"Link Title","jobid":"link-job"}}]};</script>`
const xxtDocumentCardHTML = `<script>window.attachmentSetting = {"attachments":[{"type":"document","jtoken":"jt-doc","job":true,"property":{"name":"Doc Name","objectid":"obj-doc","jobid":"doc-job"}}]};</script>`
const xxtLiveCardHTML = `<script>window.attachmentSetting = {"attachments":[{"type":"live","jobid":"live-job","aid":1001,"job":true,"property":{"module":"insertlive","title":"Live Title","userId":"u-live","liveId":6001,"streamName":"stream-1","vdoid":"vdo-1","live":true,"liveStatus":"回看","jobid":"live-job"}}]};</script>`
const xxtBbsCardHTML = `<script>window.attachmentSetting = {"enc":"root-enc","attachments":[{"type":"bbs","jobid":"bbs-job","authEnc":"auth","otherInfo":"info","job":true,"property":{"module":"insertbbs","title":"BBS Title","detail":"Discuss","mid":"mid-1","jobid":"bbs-job","allowViewReply":1,"replytimes":"2","replywordnum":"20","endtime":"2026"}}]};</script>`
const xxtBbsInfoHTML = `<input id="groupId" value="g1"><input id="bbsId" value="b1"><input id="topicId" value="topic-1"><script>classId:"2001";courseId:"9001";classChatId:"chat";role:"student";</script>`
const xxtBbsDetailJSON = `{"data":{"title":"Topic Title","text_content":"Topic body","uuid":"topic-uuid"}}`
const xxtBbsWebInfoHTML = `<script>var pageData={topic:{"uuid":"web-topic-uuid","bbsid":"web-bbs","title":"Web Topic","content":"Web body","id":99},course:{}}; var urlToken:'web-token';</script>`
const xxtLiveInfoActive = `{"status":true,"temp":{"data":{"percentValue":20,"duration":300,"liveStatus":4}}}`
const xxtLiveInfoDone = `{"status":true,"temp":{"data":{"percentValue":91,"duration":300,"liveStatus":4}}}`
const xxtLiveInfoNotStarted = `{"status":true,"temp":{"data":{"percentValue":0,"duration":300,"liveStatus":0}}}`

func TestRunTaskXuexitong_DryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	called := false
	orig := xxtCardProvider
	xxtCardProvider = func(_ *xxtmobile.XxtClient, _, _, _, _ string) (string, error) {
		called = true
		return "", nil
	}
	defer func() { xxtCardProvider = orig }()

	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"dryRun":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
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
		t.Fatal("dryRun must not call card provider")
	}
}

func TestRunTaskXuexitong_SubmitSuccess(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreCard := fakeXxtCard(xxtCardHTML, nil)
	defer restoreCard()
	restoreDto := fakeXxtVideoDto(`{"status":"success","dtoken":"tok-abc","duration":300}`, nil)
	defer restoreDto()
	restoreSubmit := fakeXxtSubmit(`{"result":1}`, nil)
	defer restoreSubmit()

	e := parseEnvelope(t, RunTask(xxtSessJSON, xxtRunTaskJSON))
	if !e.OK {
		t.Fatalf("submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("expected submitted, got %q", res.Status)
	}
	if res.Platform != "xuexitong" || res.TaskID != "obj-1" {
		t.Fatalf("unexpected: %+v", res)
	}
}

func TestRunTaskXuexitong_MissingFields(t *testing.T) {
	resetState()
	Init("/tmp/test")
	cases := []string{
		`{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111}}`, // no objectId
		`{"platform":"xuexitong","id":"obj-1","raw":{"classId":"2001","cpi":"1001","knowledgeId":111}}`,      // no courseId
		`{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","cpi":"1001","knowledgeId":111}}`,     // no classId
		`{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","knowledgeId":111}}`, // no cpi
		`{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001"}}`,      // no knowledgeId
	}
	for i, tj := range cases {
		e := parseEnvelope(t, RunTask(xxtSessJSON, tj))
		if e.OK {
			t.Fatalf("case %d: missing field should fail", i)
		}
	}
}

func TestRunTaskXuexitong_CardProviderError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtCard("", errors.New("network error"))
	defer restore()
	e := parseEnvelope(t, RunTask(xxtSessJSON, xxtRunTaskJSON))
	if e.OK {
		t.Fatal("card provider error should fail")
	}
}

func TestRunTaskXuexitong_CardHTMLMissingJobId(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restore := fakeXxtCard("<html>no job info here</html>", nil)
	defer restore()
	e := parseEnvelope(t, RunTask(xxtSessJSON, xxtRunTaskJSON))
	if e.OK {
		t.Fatal("missing jobId in card HTML should fail")
	}
}

func TestRunTaskXuexitong_VideoDtoError(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreCard := fakeXxtCard(xxtCardHTML, nil)
	defer restoreCard()
	restoreDto := fakeXxtVideoDto("", errors.New("dto error"))
	defer restoreDto()
	e := parseEnvelope(t, RunTask(xxtSessJSON, xxtRunTaskJSON))
	if e.OK {
		t.Fatal("dto error should fail")
	}
}

func TestRunTaskXuexitong_SubmitFailed(t *testing.T) {
	resetState()
	Init("/tmp/test")
	restoreCard := fakeXxtCard(xxtCardHTML, nil)
	defer restoreCard()
	restoreDto := fakeXxtVideoDto(`{"status":"success","dtoken":"tok","duration":300}`, nil)
	defer restoreDto()
	restoreSubmit := fakeXxtSubmit(`{"result":0,"msg":"invalid session"}`, nil)
	defer restoreSubmit()
	e := parseEnvelope(t, RunTask(xxtSessJSON, xxtRunTaskJSON))
	if e.OK {
		t.Fatal("result=0 should fail")
	}
}

func TestRunTaskXuexitong_VideoPrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtCardHTML, nil)()
	defer fakeXxtVideoDto(`{"status":"success","dtoken":"tok-video","duration":240}`, nil)()
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"videoPrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("videoPrepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.Raw["submitMode"] != "video" || res.Raw["intervalSeconds"] != float64(58) {
		t.Fatalf("unexpected video prepare: %+v", res)
	}
	if res.Raw["jobId"] != "job-123" || res.Raw["dtoken"] != "tok-video" || res.Raw["duration"] != float64(240) {
		t.Fatalf("video raw missing metadata: %+v", res.Raw)
	}
}

func TestRunTaskXuexitong_VideoTickDryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"job-123","otherInfo":"info","dtoken":"tok","duration":240},"options":{"action":"videoTick","playingTime":58,"dryRun":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("videoTick dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" || res.Raw["playingTime"] != float64(58) || res.Raw["realSubmit"] != false {
		t.Fatalf("unexpected video dry-run: %+v", res)
	}
}

func TestRunTaskXuexitong_VideoTickRealSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtSubmit(`{"result":1}`, nil)()
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"job-123","otherInfo":"info","dtoken":"tok","duration":240},"options":{"action":"videoTick","playingTime":58}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("videoTick real submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["realSubmit"] != true {
		t.Fatalf("unexpected video submit: %+v", res)
	}
}

func TestRunTaskXuexitong_VideoTickRealSubmitRequiresProgress(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"job-123","otherInfo":"info","dtoken":"tok","duration":240},"options":{"action":"videoTick","realSubmit":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if e.OK {
		t.Fatal("video real submit without playingTime should fail")
	}
}

func TestRunTaskXuexitong_AudioPrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtCardHTML, nil)()
	defer fakeXxtVideoDto(`{"status":"success","dtoken":"tok","duration":180}`, nil)()
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"audioPrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("audioPrepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.Raw["submitMode"] != "audio" || res.Raw["intervalSeconds"] != float64(58) {
		t.Fatalf("unexpected audio prepare: %+v", res)
	}
	if res.Raw["jobId"] != "job-123" || res.Raw["duration"] != float64(180) {
		t.Fatalf("audio raw missing metadata: %+v", res.Raw)
	}
}

func TestRunTaskXuexitong_AudioTickDryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"job-123","otherInfo":"info","duration":180},"options":{"action":"audioTick","playingTime":58,"dryRun":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("audioTick dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" || res.Raw["playingTime"] != float64(58) || res.Raw["realSubmit"] != false {
		t.Fatalf("unexpected audio dry-run: %+v", res)
	}
}

func TestRunTaskXuexitong_AudioTickRealSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtAudioSubmit(`{"isPassed":false}`, nil)()
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"job-123","otherInfo":"info","duration":180},"options":{"action":"audioTick","playingTime":58}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("audioTick real submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["realSubmit"] != true {
		t.Fatalf("unexpected audio submit: %+v", res)
	}
}

func TestRunTaskXuexitong_AudioTickRealSubmitRequiresProgress(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","id":"obj-1","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"job-123","otherInfo":"info","duration":180},"options":{"action":"audioTick","realSubmit":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if e.OK {
		t.Fatal("audio real submit without playingTime should fail")
	}
}

func TestRunTaskXuexitong_HyperlinkPrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtHyperlinkCardHTML, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"link-job"},"options":{"action":"hyperlinkPrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("hyperlinkPrepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.TaskID != "link-job" || res.Raw["submitMode"] != "hyperlink" {
		t.Fatalf("unexpected hyperlink prepare: %+v", res)
	}
	if res.Raw["jobId"] != "link-job" || res.Raw["jtoken"] != "jt-1" || res.Raw["title"] != "Link Title" {
		t.Fatalf("hyperlink raw missing metadata: %+v", res.Raw)
	}
}

func TestRunTaskXuexitong_HyperlinkDryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"link-job","jtoken":"jt-1"},"options":{"action":"hyperlink","dryRun":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("hyperlink dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" || res.Raw["realSubmit"] != false {
		t.Fatalf("unexpected hyperlink dry-run: %+v", res)
	}
}

func TestRunTaskXuexitong_HyperlinkRealSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtHyperlinkSubmit(`{"status":true,"msg":"ok"}`, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"link-job","jtoken":"jt-1"},"options":{"action":"hyperlink"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("hyperlink real submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["realSubmit"] != true {
		t.Fatalf("unexpected hyperlink submit: %+v", res)
	}
}

func TestRunTaskXuexitong_HyperlinkRejected(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtHyperlinkSubmit(`{"status":false,"msg":"no"}`, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"link-job","jtoken":"jt-1"},"options":{"action":"hyperlink","realSubmit":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("hyperlink rejected response should return result: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "rejected" || res.Message != "no" || res.Raw["realSubmit"] != true {
		t.Fatalf("unexpected hyperlink rejection: %+v", res)
	}
}

func TestRunTaskXuexitong_DocumentPrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtDocumentCardHTML, nil)()
	taskJSON := `{"platform":"xuexitong","id":"obj-doc","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"documentPrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("documentPrepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.TaskID != "obj-doc" || res.Raw["submitMode"] != "document" {
		t.Fatalf("unexpected document prepare: %+v", res)
	}
	if res.Raw["jobId"] != "doc-job" || res.Raw["jtoken"] != "jt-doc" || res.Raw["title"] != "Doc Name" || res.Raw["isJob"] != true {
		t.Fatalf("document raw missing metadata: %+v", res.Raw)
	}
}

func TestRunTaskXuexitong_DocumentDefaultSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtDocumentSubmit(`{"status":true,"msg":"ok"}`, nil)()
	taskJSON := `{"platform":"xuexitong","id":"obj-doc","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"doc-job","jtoken":"jt-doc"},"options":{"action":"document"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("document default submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["realSubmit"] != true {
		t.Fatalf("unexpected document submit: %+v", res)
	}
}

func TestRunTaskXuexitong_DocumentDryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","id":"obj-doc","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"doc-job","jtoken":"jt-doc"},"options":{"action":"document","dryRun":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("document dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" || res.Raw["realSubmit"] != false {
		t.Fatalf("unexpected document dry-run: %+v", res)
	}
}

func TestRunTaskXuexitong_ReadV2DefaultSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtDocumentSubmit(`{"status":true,"msg":"ok"}`, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"read-job","jtoken":"jt-read"},"options":{"action":"read"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("read default submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["documentType"] != "insertreadV2" {
		t.Fatalf("unexpected read submit: %+v", res)
	}
}

func TestRunTaskXuexitong_LivePrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtLiveCardHTML, nil)()
	defer fakeXxtLiveInfo(xxtLiveInfoActive, nil)()
	defer fakeXxtLiveRelation(`{"status":true,"msg":"ok"}`, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"livePrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("livePrepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.Raw["submitMode"] != "live" || res.Raw["intervalSeconds"] != float64(30) {
		t.Fatalf("unexpected live prepare: %+v", res)
	}
	if res.Raw["jobId"] != "live-job" || res.Raw["percent"] != float64(20) || res.Raw["realSubmit"] != true {
		t.Fatalf("live raw missing metadata: %+v", res.Raw)
	}
}

func TestRunTaskXuexitong_LivePrepareNotStarted(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtLiveCardHTML, nil)()
	defer fakeXxtLiveInfo(xxtLiveInfoNotStarted, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"livePrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("livePrepare not-started should return result: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "skipped" || res.Raw["statusCode"] != float64(0) {
		t.Fatalf("unexpected live skipped: %+v", res)
	}
}

func TestRunTaskXuexitong_LiveTickDone(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtLiveSave("@success", nil)()
	defer fakeXxtLiveInfo(xxtLiveInfoDone, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"live-job","liveId":"6001","userId":"u-live","streamName":"stream-1","vdoid":"vdo-1","aid":"1001"},"options":{"action":"liveTick"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("liveTick should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "done" || res.Raw["percent"] != float64(91) || res.Raw["realSubmit"] != true {
		t.Fatalf("unexpected live tick: %+v", res)
	}
}

func TestRunTaskXuexitong_LiveTickDryRun(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"live-job","liveId":"6001","userId":"u-live","streamName":"stream-1","vdoid":"vdo-1","aid":"1001"},"options":{"action":"liveTick","dryRun":true}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("liveTick dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" || res.Raw["realSubmit"] != false {
		t.Fatalf("unexpected live dry-run: %+v", res)
	}
}

func TestRunTaskXuexitong_BbsPrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtBbsCardHTML, nil)()
	defer fakeXxtBbsInfo(xxtBbsInfoHTML, nil)()
	defer fakeXxtBbsDetail(xxtBbsDetailJSON, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"bbsPrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("bbsPrepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.TaskID != "bbs-job" || res.Raw["submitMode"] != "bbs" {
		t.Fatalf("unexpected bbs prepare: %+v", res)
	}
	topic, _ := res.Raw["topic"].(map[string]interface{})
	if topic["topicUUID"] != "topic-uuid" || topic["title"] != "Topic Title" || res.Raw["requiresHostAI"] != true {
		t.Fatalf("bbs raw missing topic: %+v", res.Raw)
	}
}

func TestRunTaskXuexitong_BbsDefaultSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtBbsAnswer(`{"result":1,"msg":"ok"}`, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"bbs-job","mid":"mid-1","topicUUID":"topic-uuid","topicClassId":"2001"},"options":{"action":"bbs","content":"host reply"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("bbs submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["realSubmit"] != true || res.Raw["content"] != "host reply" {
		t.Fatalf("unexpected bbs submit: %+v", res)
	}
}

func TestRunTaskXuexitong_BbsRequiresHostContent(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"bbs-job","mid":"mid-1","topicUUID":"topic-uuid","topicClassId":"2001"},"options":{"action":"bbs"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if e.OK {
		t.Fatal("bbs without host content should fail")
	}
}

func TestRunTaskXuexitong_BbsRejected(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtBbsAnswer(`{"result":0,"msg":"no"}`, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"bbs-job","mid":"mid-1","topicUUID":"topic-uuid","topicClassId":"2001"},"options":{"action":"bbs","content":"host reply"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("bbs rejected response should return result: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "rejected" || res.Message != "no" {
		t.Fatalf("unexpected bbs rejection: %+v", res)
	}
}

func TestRunTaskXuexitong_MonitorAlive(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtMonitor(`jsonp1({"status":true})`, nil)()
	taskJSON := `{"platform":"xuexitong","options":{"action":"monitor"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("monitor should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "alive" || res.Raw["alive"] != true || res.Raw["expired"] != false {
		t.Fatalf("unexpected monitor result: %+v", res)
	}
}

func TestRunTaskXuexitong_BbsWebPrepare(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtBbsCardHTML, nil)()
	defer fakeXxtBbsWeb("ut-1", "id1", "id2", xxtBbsWebInfoHTML, "", nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"bbsWebPrepare"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("bbsWebPrepare should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "prepared" || res.Raw["submitMode"] != "bbsWeb" || res.Raw["requiresHostAI"] != true || res.Raw["requiresHostOCR"] != false {
		t.Fatalf("unexpected bbs web prepare: %+v", res)
	}
	topic, _ := res.Raw["topic"].(map[string]interface{})
	if topic["topicUUID"] != "web-topic-uuid" || topic["urlToken"] != "web-token" || topic["bbsId"] != "web-bbs" {
		t.Fatalf("web topic missing metadata: %+v", topic)
	}
}

func TestRunTaskXuexitong_BbsWebCaptcha(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtCard(xxtBbsCardHTML, nil)()
	origUt := xxtBbsUtEncProvider
	xxtBbsUtEncProvider = func(_ *xxtmobile.XxtClient, _, _, _, _ string) (string, error) {
		return "", xxtmobile.ErrBbsCaptcha
	}
	defer func() { xxtBbsUtEncProvider = origUt }()
	origImg := xxtChapterCaptchaImgProvider
	xxtChapterCaptchaImgProvider = func(_ *xxtmobile.XxtClient) (string, error) { return "BASE64IMG", nil }
	defer func() { xxtChapterCaptchaImgProvider = origImg }()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111},"options":{"action":"pullBbsWeb"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("bbs web captcha should return result: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "captcha" || res.Raw["captchaImage"] != "BASE64IMG" || res.Raw["requiresHostOCR"] != true {
		t.Fatalf("unexpected captcha result: %+v", res)
	}
}

func TestRunTaskXuexitong_BbsWebDefaultSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtBbsWeb("", "", "", "", `{"status":true,"msg":"ok"}`, nil)()
	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"9001","classId":"2001","cpi":"1001","knowledgeId":111,"jobId":"bbs-job","mid":"mid-1","enc":"enc-1","topicUUID":"web-topic-uuid","urlToken":"web-token","bbsId":"web-bbs"},"options":{"action":"bbsWeb","content":"host reply"}}`
	e := parseEnvelope(t, RunTask(xxtSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("bbs web submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" || res.Raw["realSubmit"] != true || res.Raw["content"] != "host reply" {
		t.Fatalf("unexpected bbs web submit: %+v", res)
	}
}
