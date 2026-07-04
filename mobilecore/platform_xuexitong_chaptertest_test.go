package mobilecore

import (
	"encoding/json"
	"testing"

	xxtmobile "github.com/yatori-dev/yatori-go-mobile-core/api/xuexitong/mobile"
)

const xxtChapterPaperHTML = `
<input id="courseId" value="C1">
<input id="classId" value="CL1">
<input id="totalQuestionNum" value="1">
<input id="enc_work" value="EW1">
<input id="workRelationId" value="WR1">
<div class="chapter-title" workname="测验一"></div>
<div class="Py-mian1" data="q1">
  <div class="Py-m1-title"><span class="quesType">1.[单选题]</span><div class="workTextWrap"><p>1+1=?</p></div></div>
  <ul class="answerList singleChoice"><li><em class="choose-opt">A</em><cc>2</cc></li><li><em class="choose-opt">B</em><cc>3</cc></li></ul>
</div>`

func fakeXxtChapterFetch(raw string, err error) func() {
	orig := xxtChapterFetchProvider
	xxtChapterFetchProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, _, _, _, _, _ string) (string, error) { return raw, err }
	return func() { xxtChapterFetchProvider = orig }
}

func TestRunTaskXuexitong_PullChapterTest(t *testing.T) {
	resetState()
	Init("/tmp/test")
	defer fakeXxtChapterFetch(xxtChapterPaperHTML, nil)()

	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"C1","classId":"CL1","workId":"W1","knowledgeId":1,"cpi":"9"},"options":{"action":"pullChapterTest"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullChapterTest should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "questions" {
		t.Fatalf("status=%q want questions", res.Status)
	}
	meta, _ := res.Raw["meta"].(map[string]interface{})
	if meta["courseId"] != "C1" || meta["encWork"] != "EW1" {
		t.Fatalf("meta=%v", meta)
	}
	qs, _ := res.Raw["questions"].([]interface{})
	if len(qs) != 1 {
		t.Fatalf("expected 1 question, got %v", res.Raw["questions"])
	}
}

func TestRunTaskXuexitong_PullChapterTestFromCard(t *testing.T) {
	resetState()
	Init("/tmp/test")
	cardHTML := `<script>window.attachmentSetting = {"defaults":{"ktoken":"K1"},"attachments":[{"enc":"ENC1","job":true,"property":{"workid":"W2","schoolid":"S1","_jobid":"J2"}}]};</script>`
	defer fakeXxtCard(cardHTML, nil)()
	orig := xxtChapterFetchProvider
	var gotWorkId, gotSchoolId, gotJobId, gotKToken, gotEnc string
	xxtChapterFetchProvider = func(_ *xxtmobile.XxtClient, _, _, _, _, workId, schoolId, jobId, ktoken, enc string) (string, error) {
		gotWorkId, gotSchoolId, gotJobId, gotKToken, gotEnc = workId, schoolId, jobId, ktoken, enc
		return xxtChapterPaperHTML, nil
	}
	defer func() { xxtChapterFetchProvider = orig }()

	taskJSON := `{"platform":"xuexitong","raw":{"courseId":"C1","classId":"CL1","knowledgeId":1,"cpi":"9"},"options":{"action":"pullChapterTest"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("pullChapterTest from card should succeed: %s", e.Error)
	}
	if gotWorkId != "W2" || gotSchoolId != "S1" || gotJobId != "J2" || gotKToken != "K1" || gotEnc != "ENC1" {
		t.Fatalf("card fields not passed: work=%q school=%q job=%q ktoken=%q enc=%q", gotWorkId, gotSchoolId, gotJobId, gotKToken, gotEnc)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.TaskID != "W2" || res.Status != "questions" {
		t.Fatalf("unexpected result: task=%q status=%q", res.TaskID, res.Status)
	}
}

func TestRunTaskXuexitong_ChapterTestSave(t *testing.T) {
	resetState()
	Init("/tmp/test")
	var captured bool
	orig := xxtChapterSubmitProvider
	xxtChapterSubmitProvider = func(_ *xxtmobile.XxtClient, meta *xxtmobile.ChapterTestMeta, answers []xxtmobile.ChapterTestAnswer, isSubmit bool) (string, error) {
		captured = true
		if isSubmit {
			t.Fatal("default should be save (isSubmit=false)")
		}
		if len(answers) != 1 || answers[0].Qid != "q1" {
			t.Fatalf("answers wrong: %+v", answers)
		}
		return `{"status":true}`, nil
	}
	defer func() { xxtChapterSubmitProvider = orig }()

	paper := `{"meta":{"courseId":"C1","classId":"CL1","totalQuestionNum":"1","enc_Work":"EW1","workRelationId":"WR1"},"answers":[{"qid":"q1","typeCode":"0","options":["2","3"],"answers":["3"]}]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"chapterTest","paper":` + paper + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("chapterTest save should succeed: %s", e.Error)
	}
	if !captured {
		t.Fatal("submit provider should be called")
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "saved" {
		t.Fatalf("status=%q want saved", res.Status)
	}
}

func TestRunTaskXuexitong_ChapterTestCaptcha(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtChapterSubmitProvider
	xxtChapterSubmitProvider = func(_ *xxtmobile.XxtClient, _ *xxtmobile.ChapterTestMeta, _ []xxtmobile.ChapterTestAnswer, _ bool) (string, error) {
		return "", xxtmobile.ErrChapterCaptcha
	}
	defer func() { xxtChapterSubmitProvider = orig }()
	origImg := xxtChapterCaptchaImgProvider
	xxtChapterCaptchaImgProvider = func(_ *xxtmobile.XxtClient) (string, error) { return "BASE64IMG", nil }
	defer func() { xxtChapterCaptchaImgProvider = origImg }()

	paper := `{"meta":{"courseId":"C1","classId":"CL1","workRelationId":"WR1"},"answers":[{"qid":"q1","typeCode":"0","answers":["3"]}]}`
	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"chapterTest","isSubmit":true,"paper":` + paper + `}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("chapterTest captcha should return result (not error): %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "captcha" {
		t.Fatalf("status=%q want captcha", res.Status)
	}
	if res.Raw["captchaImage"] != "BASE64IMG" {
		t.Fatalf("captchaImage=%v", res.Raw["captchaImage"])
	}
}

func TestRunTaskXuexitong_PassChapterCaptcha(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtChapterCaptchaPassProvider
	xxtChapterCaptchaPassProvider = func(_ *xxtmobile.XxtClient, code string) (bool, error) {
		if code != "AB12" {
			t.Fatalf("code=%q want AB12", code)
		}
		return true, nil
	}
	defer func() { xxtChapterCaptchaPassProvider = orig }()

	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"passChapterCaptcha","code":"AB12"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("passChapterCaptcha should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	if res.Status != "done" || res.Raw["passed"] != true {
		t.Fatalf("unexpected: status=%q raw=%v", res.Status, res.Raw)
	}
}

func TestRunTaskXuexitong_PassChapterCaptcha_Rejected(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtChapterCaptchaPassProvider
	xxtChapterCaptchaPassProvider = func(_ *xxtmobile.XxtClient, _ string) (bool, error) { return false, nil }
	defer func() { xxtChapterCaptchaPassProvider = orig }()

	taskJSON := `{"platform":"xuexitong","raw":{},"options":{"action":"passChapterCaptcha","code":"WRONG"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if e.OK {
		t.Fatal("rejected captcha should fail")
	}
}

func TestRunTaskXuexitong_XxtAI(t *testing.T) {
	resetState()
	Init("/tmp/test")
	orig := xxtAIProvider
	xxtAIProvider = func(_ *xxtmobile.XxtClient, classId, courseId, cpi, content string) (string, error) {
		if classId != "CL1" || courseId != "C1" {
			t.Fatalf("ids wrong: %q %q", classId, courseId)
		}
		return `["北京"]`, nil
	}
	defer func() { xxtAIProvider = orig }()

	taskJSON := `{"platform":"xuexitong","raw":{"classId":"CL1","courseId":"C1","cpi":"9"},"options":{"action":"xxtAI","content":"中国首都?"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if !e.OK {
		t.Fatalf("xxtAI should succeed: %s", e.Error)
	}
	b, _ := json.Marshal(e.Data)
	var res RunTaskResult
	json.Unmarshal(b, &res)
	answers, _ := res.Raw["answers"].([]interface{})
	if len(answers) != 1 || answers[0] != "北京" {
		t.Fatalf("answers=%v", res.Raw["answers"])
	}
}

func TestRunTaskXuexitong_XxtAI_MissingContent(t *testing.T) {
	resetState()
	Init("/tmp/test")
	taskJSON := `{"platform":"xuexitong","raw":{"classId":"CL1","courseId":"C1"},"options":{"action":"xxtAI"}}`
	e := parseEnvelope(t, RunTask(xxtWorkSessJSON, taskJSON))
	if e.OK {
		t.Fatal("xxtAI without content should fail")
	}
}
