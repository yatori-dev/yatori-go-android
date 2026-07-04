package mobilecore

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

const xxtWorkSessJSON = `{"platform":"xuexitong","account":"u","cookies":"fid=1; _uid=2","extra":{"userId":"U1"}}`

func fakeXxtWorkList(raw string, err error) func() {
	orig := xxtWorkListProvider
	xxtWorkListProvider = func(_ *xxtmobile.XxtClient, _, _, _ string) (string, error) { return raw, err }
	return func() { xxtWorkListProvider = orig }
}

func fakeXxtWorkEnter(html, referer string, err error) func() {
	orig := xxtWorkEnterProvider
	xxtWorkEnterProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _ string) (string, string, error) {
		return html, referer, err
	}
	return func() { xxtWorkEnterProvider = orig }
}

func fakeXxtWorkPaper(raw string, err error) func() {
	orig := xxtWorkPaperProvider
	xxtWorkPaperProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _, _ string) (string, error) { return raw, err }
	return func() { xxtWorkPaperProvider = orig }
}

func fakeXxtWorkQuestion(raw string, err error) func() {
	orig := xxtWorkQuestionProvider
	xxtWorkQuestionProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _, _ string, _ int) (string, error) { return raw, err }
	return func() { xxtWorkQuestionProvider = orig }
}

const xxtWorkQHTML = `
<input type="hidden" id="questionId" value="Q1">
<input type="hidden" name="typeQ1" value="0">
<input type="hidden" id="courseId" value="C1">
<input type="hidden" id="classId" value="CL1">
<input type="hidden" id="workId" value="W1">
<input type="hidden" id="enc" value="ENC">
<input type="hidden" id="source" value="0">
<input type="hidden" id="encWork" value="EW">
<input type="hidden" id="index" value="0">
<input type="hidden" name="scoreQ1" value="5.0">
<input type="hidden" id="testUserRelationId" value="AID">
<div class="singleQuesId" data="Q1">
  <div class="ans-cc workWrap"><p>中国的首都是？</p></div>
  <div class="centerSpan" id="A"><p>北京</p></div>
  <div class="centerSpan" id="B"><p>上海</p></div>
</div>`

func TestRunTaskXuexitong_PullWorkList(t *testing.T) {
	resetState()
	Init("/tmp/test")
	html := `<ul class="nav"><li data="/work?taskrefId=T1&courseId=C1&userId=U1&clazzId=CL1&type=2&enc_task=ET&msgId=0"><div><p>作业一</p><span>待做</span></div></li></ul>`
	defer fakeXxtWorkList(html, nil)()

	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"C1","classId":"CL1","cpi":"9"},"options":{"action":"pullWorkList"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullWorkList should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	works, ok := res.Raw["works"].([]interface{})
	if !ok || len(works) != 1 {
		t.Fatalf("expected 1 work, got %v", res.Raw["works"])
	}
	w := works[0].(map[string]interface{})
	if w["taskRefId"] != "T1" || w["classId"] != "CL1" || w["cpi"] != "9" {
		t.Fatalf("unexpected work: %v", w)
	}
}

func TestRunTaskXuexitong_EnterWork_NoCaptcha(t *testing.T) {
	resetState()
	Init("/tmp/test")
	enterHTML := `<div>本次作业共包含 2 道题目</div><input type="hidden" id="testUserRelationId" value="AID"><script>cpi=9&workAnswerId=55&enc=deadbeef</script>`
	defer fakeXxtWorkEnter(enterHTML, "http://referer", nil)()
	defer fakeXxtWorkPaper(`<html>ok</html>`, nil)()
	// slider provider must NOT be called when no captchaId; make it error to prove it.
	origSlider := xxtSliderProvider
	xxtSliderProvider = func(_ *xxtmobile.XxtClient, _, _ string) (string, error) {
		return "", errors.New("slider must not be called")
	}
	defer func() { xxtSliderProvider = origSlider }()

	taskJSON := `{"platform":"xuexitong","raw":{"taskRefId":"T1","courseId":"C1","clazzId":"CL1","cpi":"9","msgId":"0","userId":"U1"},"options":{"action":"enterWork"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("enterWork should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "entered" {
		t.Fatalf("status=%q want entered", res.Status)
	}
	if res.Raw["questionTotal"] != float64(2) || res.Raw["workId"] != "T1" || res.Raw["answerId"] != "AID" {
		t.Fatalf("ctx wrong: %v", res.Raw)
	}
}

func TestRunTaskXuexitong_EnterWork_WithSlider(t *testing.T) {
	resetState()
	Init("/tmp/test")
	enterHTML := `<div>共包含 1 道题目</div><input type="hidden" id="captchaCaptchaId" value="CID"><input type="hidden" id="testUserRelationId" value="AID"><script>cpi=9&workAnswerId=55&enc=dead</script>`
	defer fakeXxtWorkEnter(enterHTML, "http://referer", nil)()
	defer fakeXxtWorkPaper(`<html>ok</html>`, nil)()
	var sliderCalled bool
	origSlider := xxtSliderProvider
	xxtSliderProvider = func(_ *xxtmobile.XxtClient, captchaId, referer string) (string, error) {
		sliderCalled = true
		if captchaId != "CID" || referer != "http://referer" {
			t.Fatalf("slider got captchaId=%q referer=%q", captchaId, referer)
		}
		return "validate-token", nil
	}
	defer func() { xxtSliderProvider = origSlider }()

	taskJSON := `{"platform":"xuexitong","raw":{"taskRefId":"T1","courseId":"C1","clazzId":"CL1","cpi":"9","msgId":"0"},"options":{"action":"enterWork"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("enterWork(slider) should succeed: %s", e.Error)
	}
	if !sliderCalled {
		t.Fatal("slider should have been called when captchaId present")
	}
}

func TestRunTaskXuexitong_WorkQuestion(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtWorkQuestion(xxtWorkQHTML, nil)()

	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"C1","classId":"CL1","workId":"W1","msgId":"0","cpi":"9","answerId":"AID","enc":"ENC"},"options":{"action":"workQuestion","index":0}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("workQuestion should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "question" {
		t.Fatalf("status=%q want question", res.Status)
	}
	opts, _ := res.Raw["options"].([]interface{})
	if len(opts) != 2 || opts[0] != "北京" {
		t.Fatalf("options=%v", res.Raw["options"])
	}
	sub, _ := res.Raw["submit"].(map[string]interface{})
	if sub["questionId"] != "Q1" || sub["typeCode"] != "0" {
		t.Fatalf("submit=%v", sub)
	}
}

func TestRunTaskXuexitong_WorkSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var captured map[string]string
	orig := xxtWorkSubmitProvider
	xxtWorkSubmitProvider = func(_ *xxtmobile.XxtClient, entity *xxtmobile.WorkSubmitEntity, options, answers []string, tempSave bool) (string, error) {
		captured = map[string]string{
			"questionId": entity.QuestionId,
			"answer":     strings.Join(answers, ","),
			"options":    strings.Join(options, ","),
			"tempSave":   map[bool]string{true: "t", false: "f"}[tempSave],
		}
		return `{"status":true,"msg":"success!"}`, nil
	}
	defer func() { xxtWorkSubmitProvider = orig }()

	q := `{"submit":{"courseId":"C1","workId":"W1","classId":"CL1","answerId":"AID","questionId":"Q1","typeCode":"0","score":"5.0","index":"0","enc":"ENC","source":"0"},"options":["北京","上海"],"answers":["上海"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"work","isSubmit":true,"question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("work submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("status=%q want submitted (isSubmit=true)", res.Status)
	}
	if captured["tempSave"] != "f" {
		t.Fatalf("isSubmit=true should map to tempSave=false, got %q", captured["tempSave"])
	}
	if captured["answer"] != "上海" {
		t.Fatalf("answers passthrough wrong: %q", captured["answer"])
	}
}

func TestRunTaskXuexitong_WorkSubmit_Save(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtWorkSubmitProvider
	xxtWorkSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.WorkSubmitEntity, _, _ []string, tempSave bool) (string, error) {
		if !tempSave {
			t.Fatal("default (isSubmit omitted) should tempSave=true")
		}
		return `{"status":true,"msg":"ok"}`, nil
	}
	defer func() { xxtWorkSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q1","typeCode":"0","courseId":"C1"},"options":["x"],"answers":["x"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"work","question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("work save should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "saved" {
		t.Fatalf("status=%q want saved", res.Status)
	}
}

func TestRunTaskXuexitong_WorkSubmit_Rejected(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtWorkSubmitProvider
	xxtWorkSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.WorkSubmitEntity, _, _ []string, _ bool) (string, error) {
		return `{"status":false,"msg":"作业提交失败！"}`, nil
	}
	defer func() { xxtWorkSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q1","typeCode":"0"},"options":["x"],"answers":["x"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"work","isSubmit":true,"question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if e.OK {
		t.Fatal("status:false submit should fail")
	}
}

func TestRunTaskXuexitong_WorkSubmit_MissingQuestion(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"work"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if e.OK {
		t.Fatal("work without options.question should fail")
	}
}
