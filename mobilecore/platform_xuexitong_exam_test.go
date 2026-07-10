package mobilecore

import (
	"encoding/json"
	"errors"
	"testing"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

func fakeXxtExamList(raw string, err error) func() {
	orig := xxtExamListProvider
	xxtExamListProvider = func(_ *xxtmobile.XxtClient, _, _, _ string) (string, error) { return raw, err }
	return func() { xxtExamListProvider = orig }
}
func fakeXxtExamEnter(html, referer string, err error) func() {
	orig := xxtExamEnterProvider
	xxtExamEnterProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _ string) (string, string, error) {
		return html, referer, err
	}
	return func() { xxtExamEnterProvider = orig }
}
func fakeXxtExamPaper(raw string, err error) func() {
	orig := xxtExamPaperProvider
	xxtExamPaperProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _, _ string) (string, error) { return raw, err }
	return func() { xxtExamPaperProvider = orig }
}
func fakeXxtExamQuestion(raw string, err error) func() {
	orig := xxtExamQuestionProvider
	xxtExamQuestionProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _, _ string, _ int) (string, error) { return raw, err }
	return func() { xxtExamQuestionProvider = orig }
}

const xxtExamQHTML = `
<input type="hidden" id="questionId" value="Q9">
<input type="hidden" name="typeQ9" value="0">
<input type="hidden" name="typeNameQ9" value="单选题">
<input type="hidden" id="courseId" value="C1">
<input type="hidden" id="testPaperId" value="TP1">
<input type="hidden" id="testUserRelationId" value="UR1">
<input type="hidden" id="classId" value="CL1">
<input type="hidden" id="enc" value="E">
<input type="hidden" id="encRemainTime" value="ER">
<input type="hidden" id="encLastUpdateTime" value="EL">
<input type="hidden" id="userId" value="U1">
<input type="hidden" name="scoreQ9" value="4">
<div class="questionWrap" data="Q9">
  <div class="tit"><p>1+1=?</p></div>
  <div class="singleChoice" name="A"><div class="answerInfo"><cc>2</cc></div></div>
  <div class="singleChoice" name="B"><div class="answerInfo"><cc>3</cc></div></div>
</div>`

func TestRunTaskXuexitong_PullExamList(t *testing.T) {
	resetState()
	Init("/tmp/test")
	html := `<ul class="nav"><li data="/exam?taskrefId=E1&courseId=C1&userId=U1&clazzId=CL1&type=2&enc_task=ET&msgId=0"><div><p>期末</p><span>待做</span></div></li></ul>`
	defer fakeXxtExamList(html, nil)()

	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"C1","classId":"CL1","cpi":"9"},"options":{"action":"pullExamList"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullExamList should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	exams, ok := res.Raw["exams"].([]interface{})
	if !ok || len(exams) != 1 {
		t.Fatalf("expected 1 exam, got %v", res.Raw["exams"])
	}
}

func TestRunTaskXuexitong_EnterExam_WithSlider(t *testing.T) {
	resetState()
	Init("/tmp/test")
	enterHTML := `<div>本试卷共 1 题</div><input type="hidden" id="captchaCaptchaId" value="CID"><input type="hidden" id="testPaperId" value="TP1"><input type="hidden" id="testUserRelationId" value="UR1"><input type="hidden" id="cpi" value="9">`
	defer fakeXxtExamEnter(enterHTML, "http://ref", nil)()
	defer fakeXxtExamPaper(xxtExamQHTML, nil)()
	var sliderValidate string
	origSlider := xxtSliderProvider
	xxtSliderProvider = func(_ *xxtmobile.XxtClient, captchaId, referer string) (string, error) {
		if captchaId != "CID" {
			t.Fatalf("slider captchaId=%q", captchaId)
		}
		sliderValidate = "VALIDATE"
		return sliderValidate, nil
	}
	defer func() { xxtSliderProvider = origSlider }()

	taskJSON := `{"platform":"xuexitong","raw":{"taskRefId":"E1","courseId":"C1","clazzId":"CL1","cpi":"9","msgId":"0"},"options":{"action":"enterExam"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("enterExam should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "entered" || res.Raw["examRelationId"] != "TP1" || res.Raw["answerId"] != "UR1" {
		t.Fatalf("ctx wrong: %v", res.Raw)
	}
	if res.Raw["enc"] != "E" || res.Raw["encRemainTime"] != "ER" {
		t.Fatalf("enc fields not threaded from paper: %v", res.Raw)
	}
}

func TestRunTaskXuexitong_ExamQuestion(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtExamQuestion(xxtExamQHTML, nil)()

	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"C1","classId":"CL1","examRelationId":"TP1","answerId":"UR1","cpi":"9","enc":"E","encRemainTime":"ER","encLastUpdateTime":"EL"},"options":{"action":"examQuestion","index":1}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("examQuestion should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "question" {
		t.Fatalf("status=%q want question", res.Status)
	}
	sub, _ := res.Raw["submit"].(map[string]interface{})
	if sub["testPaperId"] != "TP1" || sub["questionId"] != "Q9" {
		t.Fatalf("submit=%v", sub)
	}
}

// exam default = DRY-RUN; the submit provider must NOT be called.
func TestRunTaskXuexitong_ExamDryRunDefault(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtExamSubmitProvider
	xxtExamSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.ExamSubmitEntity, _, _ []string, _ bool) (string, error) {
		return "", errors.New("exam submit must not be called in dry-run")
	}
	defer func() { xxtExamSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q9","typeCode":"0","courseId":"C1","testPaperId":"TP1","testUserRelationId":"UR1","userId":"U1"},"options":["A2","B3"],"answers":["3"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"exam","question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam dry-run should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "dry_run" || res.Raw["realSubmit"] != false {
		t.Fatalf("expected dry_run/realSubmit=false, got status=%q raw=%v", res.Status, res.Raw)
	}
}

func TestRunTaskXuexitong_ExamRealSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var called bool
	orig := xxtExamSubmitProvider
	xxtExamSubmitProvider = func(_ *xxtmobile.XxtClient, e *xxtmobile.ExamSubmitEntity, options, answers []string, tempSave bool) (string, error) {
		called = true
		if tempSave {
			t.Fatal("isSubmit=true should map to tempSave=false")
		}
		return `{"status":true,"msg":"ok"}`, nil
	}
	defer func() { xxtExamSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q9","typeCode":"0","userId":"U1"},"options":["A2","B3"],"answers":["3"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"exam","realSubmit":true,"isSubmit":true,"question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("exam realSubmit should succeed: %s", e.Error)
	}
	if !called {
		t.Fatal("submit provider should be called with realSubmit=true")
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("status=%q want submitted", res.Status)
	}
}

func TestRunTaskXuexitong_ExamMinimumSubmitWait(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtExamSubmitProvider
	xxtExamSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.ExamSubmitEntity, _, _ []string, _ bool) (string, error) {
		return `{"status":false,"msg":"考试5分钟内不允许提交考试"}`, nil
	}
	defer func() { xxtExamSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q9","typeCode":"0","userId":"U1"},"options":["A2","B3"],"answers":["3"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"exam","realSubmit":true,"isSubmit":true,"question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("minimum submit window should be returned as a retryable result: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	_ = json.Unmarshal(b, &res)
	if res.Status != "submit_wait" || res.Raw["retryAfterMinutes"] != float64(5) {
		t.Fatalf("status=%q raw=%v, want submit_wait/5", res.Status, res.Raw)
	}
}

func TestRunTaskXuexitong_ExamRejectsUnparseableResponse(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtExamSubmitProvider
	xxtExamSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.ExamSubmitEntity, _, _ []string, _ bool) (string, error) {
		return `<html>verification failed</html>`, nil
	}
	defer func() { xxtExamSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q9","typeCode":"0","userId":"U1"},"options":["A2","B3"],"answers":["3"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"exam","realSubmit":true,"isSubmit":true,"question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if e.OK {
		t.Fatal("an unparseable server response must not be reported as submitted")
	}
}
func TestRunTaskXuexitong_ExamRejectsStringFalseStatus(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtExamSubmitProvider
	xxtExamSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.ExamSubmitEntity, _, _ []string, _ bool) (string, error) {
		return `{"status":"false","msg":"submit rejected"}`, nil
	}
	defer func() { xxtExamSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q9","typeCode":"0","userId":"U1"},"options":["A2","B3"],"answers":["3"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"exam","realSubmit":true,"isSubmit":true,"question":` + q + `}}`
	if e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON)); e.OK {
		t.Fatal("string status=false must not be reported as submitted")
	}
}
func TestRunTaskXuexitong_ExamAcceptsRealSuccessStatusForQuestionSave(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var tempSaveSeen bool
	orig := xxtExamSubmitProvider
	xxtExamSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.ExamSubmitEntity, _, _ []string, tempSave bool) (string, error) {
		tempSaveSeen = tempSave
		return `{"msg":"提交成功","realRemainTime":1758,"data":"1783683156124|1766|aaea24612dfbdf3ff0224720122937df","monitorparam":{"duration":7951,"submitTime":1783683156124,"questionId":"885624922","answer":"A","questionNo":1,"beginTime":1783683148173,"questionType":0},"status":"success"}`, nil
	}
	defer func() { xxtExamSubmitProvider = orig }()

	q := `{"submit":{"questionId":"885624922","typeCode":"0","userId":"U1"},"options":["A","B"],"answers":["A"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"exam","realSubmit":true,"isSubmit":false,"question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("status=success question save should succeed: %s", e.Error)
	}
	if !tempSaveSeen {
		t.Fatal("non-final question must use tempSave=true")
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	_ = json.Unmarshal(b, &res)
	if res.Status != "saved" || res.Message != "提交成功" {
		t.Fatalf("status=%q message=%q, want saved/提交成功", res.Status, res.Message)
	}
}

func TestRunTaskXuexitong_ExamAcceptsRealSuccessStatusForFinalSubmit(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtExamSubmitProvider
	xxtExamSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.ExamSubmitEntity, _, _ []string, tempSave bool) (string, error) {
		if tempSave {
			t.Fatal("final question must use tempSave=false")
		}
		return `{"msg":"提交成功","status":"success"}`, nil
	}
	defer func() { xxtExamSubmitProvider = orig }()

	q := `{"submit":{"questionId":"Q10","typeCode":"0","userId":"U1"},"options":["A","B"],"answers":["A"]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"exam","realSubmit":true,"isSubmit":true,"question":` + q + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("status=success final submit should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	_ = json.Unmarshal(b, &res)
	if res.Status != "submitted" {
		t.Fatalf("status=%q, want submitted", res.Status)
	}
}
